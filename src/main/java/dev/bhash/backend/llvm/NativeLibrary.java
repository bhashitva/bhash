// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.*;
import dev.bhash.backend.CoreProgram.Type;
import dev.bhash.frontend.SourceFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Builds an ELF library with a deliberately small C export surface. */
public final class NativeLibrary {
    private NativeLibrary() {}
    private static final String RESOURCE = "/dev/bhash/backend/native/";
    private record Tools(String clang, String objcopy, String target, String version, boolean android) {}

    public static Path build(List<SourceFile> sources, Path exports, Path output, Path ndk, String abi, int api)
            throws IOException, CompilationException {
        var library = ManagedEmitter.libraryCore(sources, NativeExports.read(exports));
        var core = library.program();
        if (core.networking() || core.postgresql() || core.server() || core.authentication())
            throw new CompilationException("Native libraries currently support portable computation and SQLite; networking, PostgreSQL, HTTP servers and authentication are unavailable.");

        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native library output directory must not exist: " + output);
        Files.createDirectories(destination.getParent());
        Path work = Files.createTempDirectory(destination.getParent(), ".bhash-library-");
        try {
            Path distribution = Files.createDirectory(work.resolve("distribution"));
            var tools = tools(work, ndk, abi, api);
            String metadata = manifest(library, tools, abi, api);
            Files.writeString(work.resolve("program.ll"), LlvmEmitter.emitLibrary(library, tools.target(), metadata));
            for (String file : List.of("bhash_runtime.c", "bhash_runtime.h", "bhash_embed.h", "bhash_embed_internal.h",
                    "bhash_embed.inc", "bhash_sqlite.inc", "bhash_postgres.inc", "bhash_async_database.inc"))
                Files.write(work.resolve(file), NativeBuildInfo.resource(RESOURCE + file));
            Files.copy(work.resolve("bhash_embed.h"), distribution.resolve("bhash_embed.h"));
            writeWrappers(library, distribution, work);
            if (core.database()) SqliteAssets.copySource(work);
            compile(work, tools, List.of("-c", "program.ll", "-o", "program.o"));
            var runtime = new ArrayList<>(List.of("-std=c17", "-Wall", "-Wextra", "-Werror", "-DBHASH_EMBEDDED=1",
                "-c", "bhash_runtime.c", "-o", "runtime.o"));
            if (core.database()) runtime.add("-DBHASH_SQLITE=1");
            compile(work, tools, runtime);
            compile(work, tools, List.of("-std=c17", "-Wall", "-Wextra", "-Werror", "-c", "exports.c", "-o", "exports.o"));

            var link = new ArrayList<>(List.of("-shared", "-Wl,--no-undefined", "-Wl,--build-id=sha1",
                "-Wl,--version-script=exports.map", "-Wl,-soname,libbhash_app.so",
                "program.o", "runtime.o", "exports.o", "-lm", "-pthread"));
            if (tools.android()) link.add("-Wl,-z,max-page-size=16384");
            if (core.database()) {
                var sqlite = new ArrayList<>(List.of("-std=c17", "-g0", "-c", "sqlite3.c", "-o", "sqlite3.o"));
                for (var define : SqliteAssets.lock().getAsJsonArray("defines")) sqlite.add("-D" + define.getAsString());
                NativeToolchain.runProcess(work, command(work, tools, sqlite), 360);
                link.addAll(List.of("sqlite3.o", "-ldl"));
            }
            link.addAll(List.of("-o", "libbhash_app.so"));
            compile(work, tools, link);
            NativeToolchain.runProcess(work, List.of(tools.objcopy(), "--only-keep-debug", "libbhash_app.so", "libbhash_app.so.debug"));
            NativeToolchain.runProcess(work, List.of(tools.objcopy(), "--strip-debug",
                "--add-gnu-debuglink=libbhash_app.so.debug", "libbhash_app.so", "distribution/libbhash_app.so"));
            Files.move(work.resolve("libbhash_app.so.debug"), distribution.resolve("libbhash_app.so.debug"));
            Files.writeString(distribution.resolve("bhash-abi.json"), metadata + "\n");
            Licensing.copyTo(distribution);
            Files.move(distribution, destination);
            return destination.resolve("libbhash_app.so");
        } finally {
            try (var files = Files.walk(work)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static Tools tools(Path work, Path ndk, String abi, int api) throws IOException, CompilationException {
        if (abi == null) {
            if (!System.getProperty("os.name").equals("Linux") || !NativePlatform.supported())
                throw new IOException("Desktop native library builds require a Linux x86-64 host.");
            String clang = System.getenv().getOrDefault("BHASH_CLANG", "clang-18");
            String objcopy = System.getenv().getOrDefault("BHASH_OBJCOPY", "llvm-objcopy-18");
            String version = NativeToolchain.runProcess(work, List.of(clang, "--version")).lines().findFirst().orElseThrow();
            return new Tools(clang, objcopy, NativePlatform.LINUX_TARGET, version, false);
        }
        if (ndk == null) throw new IOException("Specify the Android NDK with --ndk or ANDROID_NDK_HOME.");
        if (api < 29 || api > 99) throw new IOException("Android native libraries require API level 29 or later.");
        String cpu = switch (abi) {
            case "arm64-v8a" -> "aarch64";
            case "x86_64" -> "x86_64";
            default -> throw new IOException("Supported Android ABIs: arm64-v8a, x86_64");
        };
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(ndk.resolve("source.properties"))) { properties.load(reader); }
        String version = properties.getProperty("Pkg.Revision", "");
        if (!version.matches("(?:2[89]|[3-9][0-9])\\.[0-9.]+"))
            throw new IOException("An Android NDK r28 or newer is required: " + ndk);
        String host = NativePlatform.windows() ? "windows-x86_64" : NativePlatform.macos() ? "darwin-x86_64" : "linux-x86_64";
        Path bin = ndk.toAbsolutePath().normalize().resolve("toolchains/llvm/prebuilt/" + host + "/bin");
        String suffix = NativePlatform.windows() ? ".exe" : "";
        Path clang = bin.resolve("clang" + suffix);
        Path objcopy = bin.resolve("llvm-objcopy" + suffix);
        if (!Files.isRegularFile(clang) || !Files.isRegularFile(objcopy))
            throw new IOException("Android NDK installation is missing clang or llvm-objcopy: " + bin);
        return new Tools(clang.toString(), objcopy.toString(), cpu + "-linux-android" + api, version, true);
    }

    private static void compile(Path work, Tools tools, List<String> arguments) throws IOException, CompilationException {
        NativeToolchain.runProcess(work, command(work, tools, arguments));
    }

    private static List<String> command(Path work, Tools tools, List<String> arguments) {
        var command = new ArrayList<>(List.of(tools.clang(), "--target=" + tools.target(),
            "-fPIC", "-fvisibility=hidden", "-O2", "-g", "-Wno-override-module",
            "-ffile-prefix-map=" + work + "=/bhash/build"));
        command.addAll(arguments);
        return command;
    }

    private static String manifest(CoreProgram.Library library, Tools tools, String abi, int api) throws IOException {
        var result = new com.google.gson.JsonObject();
        result.addProperty("embedding_abi", 1);
        result.addProperty("target", tools.target());
        result.addProperty("toolchain", tools.version());
        if (tools.android()) {
            result.addProperty("android_abi", abi);
            result.addProperty("android_api", api);
        }
        if (library.program().database()) {
            result.addProperty("sqlite_version", SqliteAssets.VERSION);
            result.add("sqlite_source_sha3_256", SqliteAssets.lock().get("source_sha3_256"));
        }
        var signatures = new com.google.gson.JsonArray();
        for (var export : library.exports()) signatures.add(prototype(export));
        result.add("exports", signatures);
        return NativeBuildInfo.JSON.toJson(result);
    }

    private static String prototype(CoreProgram.Export export) {
        var function = export.function();
        var parameters = new ArrayList<String>();
        for (int i = 0; i < function.parameters().size(); i++)
            parameters.add(cType(function.parameters().get(i).type(), false) + " arg" + i);
        if (function.result() != Type.VOID) parameters.add(cType(function.result(), true) + " *out");
        parameters.add("bhash_error *error");
        return "BHASH_API bhash_status " + export.symbol() + "(" + String.join(", ", parameters) + ")";
    }

    private static String cType(Type type, boolean output) {
        return switch (type) {
            case INT -> "int64_t";
            case BOOL -> "uint8_t";
            case TEXT -> output ? "bhash_bytes" : "bhash_utf8_view";
            case OBJECT -> "bhash_handle";
            default -> throw new IllegalArgumentException("Unsupported C ABI type " + type);
        };
    }

    private static String kind(Type type, int handle) {
        return switch (type) {
            case INT -> "BH_EMBED_INT";
            case BOOL -> "BH_EMBED_BOOL";
            case TEXT -> "BH_EMBED_TEXT";
            case VOID -> "BH_EMBED_VOID";
            case OBJECT -> Integer.toString(handle);
            default -> throw new IllegalArgumentException("Unsupported C ABI type " + type);
        };
    }

    private static void writeWrappers(CoreProgram.Library library, Path distribution, Path work) throws IOException {
        var header = new StringBuilder("#ifndef BHASH_APP_H\n#define BHASH_APP_H\n#include \"bhash_embed.h\"\n#ifdef __cplusplus\nextern \"C\" {\n#endif\n");
        var source = new StringBuilder("#include \"distribution/bhash_app.h\"\n#include \"bhash_embed_internal.h\"\n");
        var symbols = new StringBuilder("{ global: bhash_embed_abi_version; bhash_runtime_start; bhash_runtime_stop; bhash_handle_release; bhash_bytes_free;\n");
        for (var export : library.exports()) {
            var f = export.function();
            var thunk = "bhash_internal_call_" + f.id();
            header.append(prototype(export)).append(";\n");
            symbols.append("  ").append(export.symbol()).append(";\n");
            source.append("extern void ").append(thunk).append("(uint64_t *, void **);\n")
                .append(prototype(export)).append(" {\n");
            var kinds = new ArrayList<String>();
            var values = new ArrayList<String>();
            for (int i = 0; i < f.parameters().size(); i++) {
                Type type = f.parameters().get(i).type();
                kinds.add(kind(type, export.handleTypes().get(i)));
                String field = switch (type) {
                    case INT -> "integer";
                    case BOOL -> "boolean";
                    case TEXT -> "text";
                    case OBJECT -> "handle";
                    default -> throw new IllegalArgumentException("Unsupported C ABI type " + type);
                };
                values.add("{." + field + " = arg" + i + "}");
            }
            if (!kinds.isEmpty()) {
                source.append("    const int32_t kinds[] = {").append(String.join(", ", kinds)).append("};\n")
                    .append("    const bh_embed_arg args[] = {").append(String.join(", ", values)).append("};\n");
            }
            source.append("    return bh_embed_call(").append(thunk).append(", ").append(kinds.size()).append(", ")
                .append(kinds.isEmpty() ? "NULL, NULL" : "kinds, args").append(", ")
                .append(kind(f.result(), export.resultHandleType())).append(", ")
                .append(f.result() == Type.VOID ? "NULL" : "out").append(", error);\n}\n");
        }
        Files.writeString(distribution.resolve("bhash_app.h"), header.append("#ifdef __cplusplus\n}\n#endif\n#endif\n"));
        Files.writeString(work.resolve("exports.c"), source);
        Files.writeString(work.resolve("exports.map"), symbols.append("local: *; };\n"));
    }
}
