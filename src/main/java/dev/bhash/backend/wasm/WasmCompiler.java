// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.wasm;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.Licensing;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.frontend.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Checked C# compiled into a browser application using the .NET WebAssembly runtime. */
public final class WasmCompiler {
    private static final String MARKER = ".bhash-wasm-output";
    private static final String STAMP = "Bhash browser WebAssembly output v1";
    private static final String PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk.WebAssembly">
              <PropertyGroup>
                <TargetFramework>net10.0</TargetFramework>
                <OutputType>Exe</OutputType>
                <RuntimeIdentifier>browser-wasm</RuntimeIdentifier>
                <AssemblyName>BhashProgram</AssemblyName>
                <StartupObject>BhashProgram</StartupObject>
                <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
                <DefineConstants>$(DefineConstants);BHASH_BROWSER</DefineConstants>
                <ImplicitUsings>disable</ImplicitUsings>
                <Nullable>disable</Nullable>
                <CheckForOverflowUnderflow>false</CheckForOverflowUnderflow>
                <WasmEnableHotReload>false</WasmEnableHotReload>
                <WasmBuildNative>false</WasmBuildNative>
              </PropertyGroup>
            </Project>
            """;

    public void compile(String sourceName, String source, Path output) throws IOException, CompilationException {
        compile(List.of(new SourceFile(sourceName,source)),output);
    }

    public void compile(List<SourceFile> sources, Path output) throws IOException, CompilationException {
        String code = ManagedEmitter.emit(sources, Target.CLR);
        Path destination = output.toAbsolutePath().normalize();
        validateDestination(destination);
        Files.createDirectories(destination.getParent());
        Path work = Files.createTempDirectory("bhash-wasm-build-");
        Path stage = Files.createTempDirectory(destination.getParent(), ".bhash-wasm-stage-");
        try {
            Files.writeString(work.resolve("Program.cs"), code, StandardCharsets.UTF_8);
            Files.writeString(work.resolve("BhashProgram.csproj"), PROJECT, StandardCharsets.UTF_8);
            // Runtime packs may need restoring; ignore machine/user NuGet configuration.
            Files.writeString(work.resolve("NuGet.Config"), Boolean.parseBoolean(System.getenv("BHASH_WASM_OFFLINE"))
                    ? "<configuration><packageSources><clear /></packageSources></configuration>" : """
                    <configuration><packageSources><clear />
                    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
                    </packageSources></configuration>
                    """, StandardCharsets.UTF_8);
            resource("/dev/bhash/backend/clr/BhashRuntime.cs.txt", work.resolve("BhashRuntime.cs"));
            resource("/dev/bhash/backend/wasm/BrowserHost.cs.txt", work.resolve("BrowserHost.cs"));
            var builder = new ProcessBuilder(ClrCompiler.executable(), "publish", "BhashProgram.csproj",
                    "-c", "Release", "-o", work.resolve("publish").toString(), "--nologo", "--verbosity", "quiet",
                    "--disable-build-servers", "-p:UseSharedCompilation=false",
                    "-p:ImportDirectoryBuildProps=false", "-p:ImportDirectoryBuildTargets=false")
                    .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("build.log").toFile());
            builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            builder.environment().put("DOTNET_NOLOGO", "1");
            builder.environment().put("DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE", "true");
            builder.environment().put("DOTNET_CLI_UI_LANGUAGE", "en-US");
            Process process;
            try { process = builder.start(); }
            catch (IOException missing) { throw new CompilationException(requirements()); }
            try {
                if (!process.waitFor(600, TimeUnit.SECONDS)) throw new CompilationException("Browser WASM compilation timed out after 600 seconds.");
                if (process.exitValue() != 0) throw new CompilationException(requirements() + "\n"
                        + Files.readString(work.resolve("build.log"), StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Browser WASM compilation interrupted.", interrupted);
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            Path published = work.resolve("publish");
            // SDK releases can wrap browser assets in wwwroot.
            if (Files.isDirectory(published.resolve("wwwroot/_framework"))) published = published.resolve("wwwroot");
            if (!Files.isRegularFile(published.resolve("_framework/dotnet.js")))
                throw new CompilationException("Browser WASM backend did not produce _framework/dotnet.js.");
            try (var paths = Files.walk(published)) {
                for (Path path : paths.toList()) {
                    Path target = stage.resolve(published.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target);
                    else Files.copy(path, target);
                }
            }
            for (String asset : List.of("index.html", "main.js", "worker.js"))
                resource("/dev/bhash/backend/wasm/" + asset, stage.resolve(asset));
            Licensing.copyTo(stage);
            Files.writeString(stage.resolve(MARKER), manifest(stage), StandardCharsets.UTF_8);
            install(stage, destination);
        } finally {
            ClrCompiler.deleteTree(stage);
            ClrCompiler.deleteTree(work);
        }
    }

    private static String requirements() {
        return "Browser WASM compilation requires the .NET 10 SDK and wasm-tools workload. "
                + "Run 'dotnet workload install wasm-tools'; put dotnet on PATH or set BHASH_DOTNET. "
                + "Initial builds may require access to NuGet to restore browser runtime packs.";
    }

    private static void resource(String name, Path output) throws IOException, CompilationException {
        try (var input = WasmCompiler.class.getResourceAsStream(name)) {
            if (input == null) throw new CompilationException("Compiler installation is missing " + name);
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Hash every generated asset, including nested paths. Refuse to remove user-added or modified files.
    private static String manifest(Path directory) throws IOException {
        var result = new StringBuilder(STAMP).append('\n');
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Browser output contains a symbolic link: " + path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (relative.equals(MARKER)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected browser output entry: " + path);
                try {
                    result.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))))
                            .append("  ").append(relative).append('\n');
                } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            }
        }
        return result.toString();
    }

    private static void validateDestination(Path destination) throws IOException {
        if (destination.getParent() == null || Files.isSymbolicLink(destination)) throw new IOException("Browser output must be a new or Bhash-managed directory.");
        if (!Files.exists(destination)) return;
        Path marker = destination.resolve(MARKER);
        if (!Files.isDirectory(destination) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker, StandardCharsets.UTF_8).equals(manifest(destination)))
            throw new IOException("Refusing to replace unmanaged or modified browser output; choose a new directory: " + destination);
    }

    static void install(Path stage, Path destination) throws IOException {
        validateDestination(destination);
        // Temp directories default to 0700 on Linux. Publish static assets with
        // read/traverse access for the HTTP server, including under a strict umask.
        // Windows retains its inherited ACLs; never change the private build workspace.
        if (Files.getFileAttributeView(stage, PosixFileAttributeView.class) != null) {
            var directories = PosixFilePermissions.fromString("rwxr-xr-x");
            var files = PosixFilePermissions.fromString("rw-r--r--");
            try (var paths = Files.walk(stage)) {
                for (Path path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Browser output contains a symbolic link: " + path);
                    Files.setPosixFilePermissions(path, Files.isDirectory(path) ? directories : files);
                }
            }
        }
        Path backup = null;
        if (Files.exists(destination)) {
            backup = Files.createTempDirectory(destination.getParent(), ".bhash-wasm-backup-");
            Files.delete(backup);
            Files.move(destination, backup);
        }
        try { Files.move(stage, destination); }
        catch (IOException failure) {
            if (backup != null) {
                try { Files.move(backup, destination); }
                catch (IOException restore) {
                    failure.addSuppressed(restore);
                    throw new IOException("Could not replace browser output. Previous output is preserved at " + backup, failure);
                }
            }
            throw failure;
        }
        if (backup != null) ClrCompiler.deleteTree(backup);
    }
}
