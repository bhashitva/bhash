// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.clr;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.Licensing;
import dev.bhash.backend.SqliteAssets;
import dev.bhash.frontend.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bhash -> checked C# -> .NET 10 managed DLL and deployment metadata. */
public final class ClrCompiler {
    private static final String MARKER = ".bhash-clr-output";
    private static final String SQLITE_STAMP = "Bhash CLR output v3 SQLite\n";
    private static final String FFI_STAMP = "Bhash CLR output v4 FFI\n";
    private static final String STAMP = "Bhash CLR output v2\n";
    private static final String LEGACY_STAMP = "Bhash CLR output v1\n";
    private static final Set<String> LEGACY_FILES = Set.of("BhashProgram.dll", "BhashProgram.deps.json", "BhashProgram.runtimeconfig.json", MARKER);
    private static final Set<String> FILES;
    static {
        var files = new HashSet<>(LEGACY_FILES);
        files.addAll(Licensing.APPLICATION_FILES);
        FILES = Set.copyOf(files);
    }
    private static final String PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net10.0</TargetFramework>
                <AssemblyName>BhashProgram</AssemblyName>
                <UseAppHost>false</UseAppHost>
                <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
                <ImplicitUsings>disable</ImplicitUsings>
                <Nullable>disable</Nullable>
                <DebugType>none</DebugType>
                <CheckForOverflowUnderflow>false</CheckForOverflowUnderflow>
              </PropertyGroup>
              <ItemGroup>
                <Compile Include="Program.cs" />
                <Compile Include="BhashRuntime.cs" />
                <Compile Include="BhashStandard.cs" />
                <Compile Include="BhashMysql.cs" />
              </ItemGroup>
            </Project>
            """;

    public static String executable() {
        String configured = System.getenv("BHASH_DOTNET");
        return configured == null || configured.isBlank() ? "dotnet" : configured;
    }

    public void compile(String sourceName, String source, Path output) throws IOException, CompilationException {
        compile(List.of(new SourceFile(sourceName,source)),output);
    }

    public void compile(List<SourceFile> sources, Path output) throws IOException, CompilationException {
        String code = ManagedEmitter.emit(sources, Target.CLR);
        boolean sqlite=code.contains("BhashStandard.db");
        boolean postgres=(code.contains("BhashStandard.pg")||code.contains("BhashStandard.dbAsyncStart"));
        boolean ffi=code.contains("BhashForeign");
        Path destination = output.toAbsolutePath().normalize();
        validateDestination(destination);
        Files.createDirectories(destination.getParent());
        Path work = Files.createTempDirectory("bhash-clr-build-");
        Path stage = Files.createTempDirectory(destination.getParent(), ".bhash-clr-stage-");
        try {
            Files.writeString(work.resolve("Program.cs"), code, StandardCharsets.UTF_8);
            String project=ffi?PROJECT.replace("</ItemGroup>","<Compile Include=\"BhashForeign.cs\" /><Compile Include=\"BhashForeignModules.cs\" /></ItemGroup>"):PROJECT;
            Files.writeString(work.resolve("BhashProgram.csproj"), project, StandardCharsets.UTF_8);
            if(ffi){
                try(var input=ClrCompiler.class.getResourceAsStream("BhashForeign.cs.txt")){if(input==null)throw new IOException("Missing CLR FFI runtime");Files.copy(input,work.resolve("BhashForeign.cs"));}
                var adapters=new StringBuilder();var modules=new LinkedHashMap<String,dev.bhash.ffi.ForeignManifest>();
                var inventory=new TreeMap<String,String>();Path assets=Files.createDirectories(stage.resolve("bhash-ffi"));
                for(var source:sources)if(source.foreign()!=null)modules.putIfAbsent(source.foreign().fingerprint(),source.foreign());
                var bundled=dev.bhash.ffi.ForeignCatalog.compression();if(code.contains(dev.bhash.ffi.ForeignJniGenerator.className(bundled)))modules.putIfAbsent(bundled.fingerprint(),bundled);
                for(var module:modules.values()){
                    boolean used=code.contains("/*bhash-ffi:"+module.fingerprint()+"*/");
                    String sha="0".repeat(64),suffix=System.getProperty("os.name").startsWith("Windows")?".dll":System.getProperty("os.name").contains("Mac")?".dylib":".so";
                    if(used){
                        var artifact=dev.bhash.ffi.ForeignArtifacts.select(module,dev.bhash.ffi.ForeignArtifacts.host(),"shared");
                        Path library=dev.bhash.ffi.ForeignArtifacts.resolve(module,dev.bhash.ffi.ForeignArtifacts.host(),"shared",work);sha=artifact.sha256();
                        String name=module.fingerprint()+suffix;Files.copy(library,assets.resolve(name));inventory.put(name,sha);
                        String license=module.fingerprint()+".LICENSE.txt";Files.write(assets.resolve(license),dev.bhash.ffi.ForeignArtifacts.license(module,artifact));inventory.put(license,dev.bhash.backend.llvm.NativeBuildInfo.fileHash(assets.resolve(license)));
                    }
                    adapters.append(dev.bhash.ffi.ForeignClrGenerator.adapter(module,sha,suffix));
                }
                Files.writeString(assets.resolve("files.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inventory)+"\n",StandardCharsets.UTF_8);
                Files.writeString(work.resolve("BhashForeignModules.cs"),adapters,StandardCharsets.UTF_8);
            }
            // There are no NuGet dependencies: use SDK-installed reference packs, without external feeds.
            Files.writeString(work.resolve("NuGet.Config"), "<configuration><packageSources><clear /></packageSources></configuration>", StandardCharsets.UTF_8);
            try (var runtime = ClrCompiler.class.getResourceAsStream("BhashRuntime.cs.txt")) {
                if (runtime == null) throw new CompilationException("Compiler installation is missing its CLR runtime source.");
                Files.copy(runtime, work.resolve("BhashRuntime.cs"));
            }
            try(var standard=ClrCompiler.class.getResourceAsStream("BhashStandard.cs.txt")) {
                if(standard==null)throw new CompilationException("Missing CLR standard library runtime.");
                String text=new String(standard.readAllBytes(),StandardCharsets.UTF_8);
                Files.writeString(work.resolve("BhashStandard.cs"),sqlite?SqliteAssets.clrSource(text):text,StandardCharsets.UTF_8);
            }
            try(var mysql=ClrCompiler.class.getResourceAsStream("BhashMysql.cs.txt")) {
                if(mysql==null)throw new CompilationException("Missing CLR MySQL runtime source.");
                Files.copy(mysql,work.resolve("BhashMysql.cs"));
            }
            var command = List.of(executable(), "publish", "BhashProgram.csproj", "--configuration", "Release",
                    "--output", stage.toString(), "--no-self-contained", "--nologo", "--verbosity", "quiet",
                    "--disable-build-servers", "-p:UseSharedCompilation=false",
                    "-p:ImportDirectoryBuildProps=false", "-p:ImportDirectoryBuildTargets=false");
            var builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true)
                    .redirectOutput(work.resolve("build.log").toFile());
            builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            builder.environment().put("DOTNET_NOLOGO", "1");
            builder.environment().put("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1");
            builder.environment().put("DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE", "true");
            builder.environment().put("DOTNET_CLI_UI_LANGUAGE", "en-US");
            Process process;
            try { process = builder.start(); }
            catch (IOException missing) { throw new CompilationException("CLR compilation requires the .NET 10 SDK. Put dotnet on PATH or set BHASH_DOTNET to its executable path."); }
            try {
                if (!process.waitFor(120, TimeUnit.SECONDS)) throw new CompilationException("CLR compilation timed out after 120 seconds.");
                if (process.exitValue() != 0) throw new CompilationException("CLR backend requires the .NET 10 SDK and could not publish the program:\n"
                        + Files.readString(work.resolve("build.log"), StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("CLR compilation interrupted.", interrupted);
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            Licensing.copyTo(stage);
            if(sqlite)SqliteAssets.copyLibraries(stage);
            for (String name : FILES) {
                if (!name.equals(MARKER) && !Files.isRegularFile(stage.resolve(name)))
                    throw new CompilationException("CLR backend did not produce " + name);
            }
            Files.writeString(stage.resolve(MARKER), ffi?FFI_STAMP:sqlite?SQLITE_STAMP:STAMP, StandardCharsets.UTF_8);
            if(postgres)Files.writeString(stage.resolve("POSTGRESQL_RUNTIME.txt"),"Requires PostgreSQL libpq 14+ client runtime and its dependencies. Set BHASH_LIBPQ to an absolute library path if needed. The PostgreSQL server/client runtime is not bundled. TLS defaults to verify-full; configure the application CA certificate.\n",StandardCharsets.UTF_8);
            install(stage, destination);
        } finally {
            deleteTree(stage);
            deleteTree(work);
        }
    }

    private static void validateDestination(Path destination) throws IOException {
        if (destination.getParent() == null || Files.isSymbolicLink(destination))
            throw new IOException("CLR output must be a new directory or a Bhash-managed output directory.");
        if (!Files.exists(destination)) return;
        if (!Files.isDirectory(destination) || !Files.isRegularFile(destination.resolve(MARKER), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to replace a directory not created by the Bhash CLR compiler: " + destination);
        String stamp=Files.readString(destination.resolve(MARKER), StandardCharsets.UTF_8);
        if (!stamp.equals(STAMP) && !stamp.equals(LEGACY_STAMP) && !stamp.equals(SQLITE_STAMP) && !stamp.equals(FFI_STAMP))
            throw new IOException("Refusing to replace a directory not created by the Bhash CLR compiler: " + destination);
        Set<String> allowed=new HashSet<>(stamp.equals(LEGACY_STAMP)?LEGACY_FILES:FILES);
        if(stamp.equals(SQLITE_STAMP) || stamp.equals(FFI_STAMP))allowed.addAll(SqliteAssets.APPLICATION_FILES);
        allowed.add("POSTGRESQL_RUNTIME.txt");
        try (var entries = Files.list(destination)) {
            for (Path entry : entries.toList()) {
                if(stamp.equals(FFI_STAMP) && entry.getFileName().toString().equals("bhash-ffi")){validateForeignAssets(entry);continue;}
                if (!allowed.contains(entry.getFileName().toString()) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("CLR output contains unrelated files; choose a new output directory: " + destination);
            }
        }
    }
    private static void validateForeignAssets(Path directory)throws IOException{
        if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid CLR native binding directory: "+directory);
        Path file=directory.resolve("files.json");if(Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>1048576)throw new IOException("Missing CLR binding inventory: "+file);
        com.google.gson.JsonObject inventory;
        try{inventory=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();}catch(RuntimeException error){throw new IOException("Invalid CLR binding inventory",error);}
        if(inventory.size()>1024)throw new IOException("Oversized CLR binding inventory");
        for(var value:inventory.entrySet())if(!value.getValue().isJsonPrimitive() || !value.getValue().getAsJsonPrimitive().isString() || !value.getValue().getAsString().matches("[a-f0-9]{64}"))throw new IOException("Invalid CLR binding inventory checksum");
        try(var entries=Files.list(directory)){
            for(Path entry:entries.toList()){
                String name=entry.getFileName().toString();if(name.equals("files.json"))continue;
                if(!name.matches("[a-f0-9]{64}\\.(?:dll|so|dylib|LICENSE\\.txt)") || !inventory.has(name) || !Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)
                        || !dev.bhash.backend.llvm.NativeBuildInfo.fileHash(entry).equals(inventory.get(name).getAsString()))throw new IOException("CLR binding output contains unrelated or modified files: "+entry);
            }
        }
        for(String name:inventory.keySet())if(!name.matches("[a-f0-9]{64}\\.(?:dll|so|dylib|LICENSE\\.txt)") || !Files.isRegularFile(directory.resolve(name),LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid CLR binding inventory entry: "+name);
    }

    private static void install(Path stage, Path destination) throws IOException {
        validateDestination(destination);
        Path backup = null;
        if (Files.exists(destination)) {
            backup = Files.createTempDirectory(destination.getParent(), ".bhash-clr-backup-");
            Files.delete(backup);
            Files.move(destination, backup);
        }
        try { Files.move(stage, destination); }
        catch (IOException failure) {
            if (backup != null) {
                try { Files.move(backup, destination); }
                catch (IOException restore) {
                    failure.addSuppressed(restore);
                    throw new IOException("Could not replace CLR output. Previous output is preserved at " + backup, failure);
                }
            }
            throw failure;
        }
        if (backup != null) deleteTree(backup);
    }

    public static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
