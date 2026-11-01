// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.wasm;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.Licensing;
import dev.bhash.backend.clr.ClrCompiler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Immutable, administrator-prepared browser runtime. Never executes submitted code on the host. */
public final class PrebuiltBrowserHost {
    private final Path root;
    private final String id;
    private final Properties metadata;
    private static final List<String> SOURCES=List.of("/dev/bhash/backend/clr/BhashRuntime.cs.txt",
            "/dev/bhash/backend/clr/BhashStandard.cs.txt", "/dev/bhash/backend/wasm/PrebuiltBrowserHost.cs.txt");
    private static final String PROJECT="""
            <Project Sdk="Microsoft.NET.Sdk.WebAssembly">
              <PropertyGroup>
                <TargetFramework>net10.0</TargetFramework><RuntimeIdentifier>browser-wasm</RuntimeIdentifier>
                <OutputType>Exe</OutputType><AssemblyName>BhashBrowserHost</AssemblyName>
                <AllowUnsafeBlocks>true</AllowUnsafeBlocks><DefineConstants>BHASH_BROWSER</DefineConstants>
                <ImplicitUsings>disable</ImplicitUsings><Nullable>disable</Nullable>
                <WasmBuildNative>false</WasmBuildNative><WasmEnableHotReload>false</WasmEnableHotReload>
                <!-- Changing programs are loaded after publishing. Trimming cannot see their BCL uses. -->
                <PublishTrimmed>false</PublishTrimmed><RunAOTCompilation>false</RunAOTCompilation>
              </PropertyGroup>
              <Target Name="RecordCompilerInputs" AfterTargets="ResolveReferences">
                <WriteLinesToFile File="compiler-references.txt" Lines="@(ReferencePath)" Overwrite="true" />
                <WriteLinesToFile File="compiler-path.txt" Lines="$(MSBuildToolsPath)/Roslyn/bincore/csc.dll" Overwrite="true" />
              </Target>
            </Project>
            """;

    private PrebuiltBrowserHost(Path root, String id, Properties metadata) {this.root=root;this.id=id;this.metadata=metadata;}
    public String id() {return id;}
    public Path web() {return root.resolve("web");}
    public static PrebuiltBrowserHost open(Path path) throws IOException {
        Path root=path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Browser host must not be a symbolic link.");
        String manifest=WasmCompiler.manifest(root);
        if (!Files.readString(root.resolve(".bhash-wasm-output")).equals(manifest))
            throw new IOException("Prebuilt browser host is modified or incomplete; prepare it again.");
        Properties properties=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve("host.properties"))) {properties.load(reader);}
        if (!fingerprint().equals(properties.getProperty("runtime")))
            throw new IOException("Prebuilt browser host does not match this compiler runtime; prepare it again.");
        Path compiler=Path.of(properties.getProperty("csc", ""));
        if (!Files.isRegularFile(compiler) || !hash(Files.readAllBytes(compiler)).equals(properties.getProperty("cscSha256")))
            throw new IOException("Prebuilt browser host SDK compiler changed or is missing; prepare it again.");
        return new PrebuiltBrowserHost(root,hash(manifest.getBytes(StandardCharsets.UTF_8)),properties);
    }

    /** Explicit image-construction operation; never called by a public compilation request. */
    public static void prepare(Path output) throws Exception {
        Path destination=output.toAbsolutePath().normalize(); WasmCompiler.validateDestination(destination);
        Files.createDirectories(destination.getParent());
        Path work=Files.createTempDirectory("bhash-browser-host-");
        Path stage=Files.createTempDirectory(destination.getParent(),".bhash-host-stage-");
        try {
            Files.writeString(work.resolve("Host.csproj"),PROJECT);
            Files.writeString(work.resolve("NuGet.Config"),"<configuration><packageSources><clear/>"
                    +(Boolean.parseBoolean(System.getenv("BHASH_WASM_OFFLINE"))?"":"<add key=\"nuget.org\" value=\"https://api.nuget.org/v3/index.json\"/>")
                    +"</packageSources></configuration>");
            for(int i=0;i<SOURCES.size();i++)WasmCompiler.resource(SOURCES.get(i),work.resolve("Source"+i+".cs"));
            run(List.of(ClrCompiler.executable(),"publish","Host.csproj","-c","Release","-o","publish","--nologo",
                    "--disable-build-servers","-p:UseSharedCompilation=false","-p:ImportDirectoryBuildProps=false",
                    "-p:ImportDirectoryBuildTargets=false","-clp:PerformanceSummary"),work);
            Path published=work.resolve("publish");
            if(Files.isDirectory(published.resolve("wwwroot/_framework")))published=published.resolve("wwwroot");
            if(!Files.isRegularFile(published.resolve("_framework/dotnet.js")))throw new IOException("Missing published browser runtime.");
            copyTree(published,stage.resolve("web"));
            Licensing.copyTo(stage.resolve("web"));
            Path references=stage.resolve("references");Files.createDirectories(references);
            for(String line:Files.readAllLines(work.resolve("compiler-references.txt"))) {
                Path reference=Path.of(line.strip());
                if(!reference.getFileName().toString().endsWith(".dll"))throw new IOException("Invalid SDK reference.");
                Files.copy(reference,references.resolve(reference.getFileName()));
            }
            Files.copy(work.resolve("bin/Release/net10.0/browser-wasm/BhashBrowserHost.dll"),references.resolve("BhashBrowserHost.dll"));
            Path csc=Path.of(Files.readString(work.resolve("compiler-path.txt")).strip()).toAbsolutePath();
            Properties properties=new Properties();properties.setProperty("runtime",fingerprint());
            properties.setProperty("csc",csc.toString());properties.setProperty("cscSha256",hash(Files.readAllBytes(csc)));
            var text=new StringWriter();properties.store(text,null);
            Files.write(stage.resolve("host.properties"),text.toString().lines().filter(line->!line.startsWith("#")).toList());
            Files.writeString(stage.resolve(".bhash-wasm-output"),WasmCompiler.manifest(stage));
            WasmCompiler.install(stage,destination);
        } finally {
            String profile=System.getenv("BHASH_WASM_PROFILE");
            if(profile!=null&&!profile.isBlank()&&Files.exists(work.resolve("build.log"))) {
                Path logs=Path.of(profile);Files.createDirectories(logs);
                Files.copy(work.resolve("build.log"),logs.resolve("host-publish.log"),StandardCopyOption.REPLACE_EXISTING);
            }
            ClrCompiler.deleteTree(stage);ClrCompiler.deleteTree(work);
        }
    }

    void compile(String code, Path output) throws IOException, CompilationException {
        Path destination=output.toAbsolutePath().normalize();WasmCompiler.validateDestination(destination);
        Files.createDirectories(destination.getParent());
        Path work=Files.createTempDirectory("bhash-browser-program-");
        Path stage=Files.createTempDirectory(destination.getParent(),".bhash-wasm-stage-");
        long started=System.nanoTime();
        try {
            Files.writeString(work.resolve("Program.cs"),code);
            var arguments=new ArrayList<>(List.of("-nologo","-nostdlib+","-target:library","-optimize+","-deterministic+",
                    "-checked-","-define:BHASH_BROWSER","-out:"+quoted(work.resolve("BhashProgram.dll")),quoted(work.resolve("Program.cs"))));
            try(var files=Files.list(root.resolve("references"))) {
                for(Path file:files.sorted().toList())arguments.add("-reference:"+quoted(file));
            }
            Files.write(work.resolve("compile.rsp"),arguments,StandardCharsets.UTF_8);
            run(List.of(ClrCompiler.executable(),"exec",metadata.getProperty("csc"),"-noconfig","@compile.rsp"),work);
            Files.copy(work.resolve("BhashProgram.dll"),stage.resolve("BhashProgram.dll"));
            // The service uses shared immutable URLs. Standalone CLI output remains self-contained.
            boolean shared=Boolean.parseBoolean(System.getenv("BHASH_BROWSER_SHARED"));
            if(!shared)copyTree(web(),stage);
            Files.writeString(stage.resolve("browser-host-config.js"),"export const runtimeBase="
                    +(shared?"\"/browser-host/"+id+"/\"":"\"./\"")+";\nexport const reportRunning="+shared+";\n");
            for(String asset:List.of("index.html","main.js"))WasmCompiler.resource("/dev/bhash/backend/wasm/"+asset,stage.resolve(asset));
            WasmCompiler.resource("/dev/bhash/backend/wasm/prebuilt-worker.js",stage.resolve("worker.js"));
            Licensing.copyTo(stage);
            Files.writeString(stage.resolve(".bhash-wasm-output"),WasmCompiler.manifest(stage));
            WasmCompiler.install(stage,destination);
        } finally {
            String profile=System.getenv("BHASH_WASM_PROFILE");
            if(profile!=null&&!profile.isBlank()) {
                Path logs=Path.of(profile);Files.createDirectories(logs);
                if(Files.exists(work.resolve("build.log")))Files.copy(work.resolve("build.log"),logs.resolve("program.log"),StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(logs.resolve("program-phases.json"),"{\"programCompileAndBundleMs\":"+(System.nanoTime()-started)/1_000_000.0+"}\n");
            }
            ClrCompiler.deleteTree(stage);ClrCompiler.deleteTree(work);
        }
    }
    private static String quoted(Path path) throws IOException {
        String value=path.toAbsolutePath().toString();
        if(value.indexOf('"')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new IOException("Unsupported toolchain path.");
        return "\""+value+"\"";
    }
    private static void run(List<String> command,Path work) throws IOException,CompilationException {
        var builder=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("build.log").toFile());
        builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT","1");builder.environment().put("DOTNET_NOLOGO","1");
        builder.environment().put("DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE","true");builder.environment().put("DOTNET_CLI_UI_LANGUAGE","en-US");
        Process process=builder.start();
        try {
            if(!process.waitFor(600,TimeUnit.SECONDS))throw new CompilationException("Browser compiler timed out.");
            if(process.exitValue()!=0)throw new CompilationException(Files.readString(work.resolve("build.log")));
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException("Browser compilation interrupted.",error);}
        finally {
            var children=process.descendants().toList();
            for(var child:children)if(child.isAlive())child.destroyForcibly();
            if(process.isAlive())process.destroyForcibly();
            boolean interrupted=Thread.interrupted();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                var handles=new ArrayList<ProcessHandle>(children);handles.add(process.toHandle());
                for(var handle:handles)while(handle.isAlive()) {
                    long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Browser compiler cleanup timed out.");
                    try{handle.onExit().get(left,TimeUnit.NANOSECONDS);}
                    catch(InterruptedException error){interrupted=true;}
                    catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException error){throw new IOException("Browser compiler cleanup failed.",error);}
                }
            } finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private static String fingerprint() throws IOException {
        var bytes=new ByteArrayOutputStream();
        for(String name:SOURCES)try(var input=PrebuiltBrowserHost.class.getResourceAsStream(name)) {
            if(input==null)throw new IOException("Missing browser host resource.");input.transferTo(bytes);
        }
        return hash(bytes.toByteArray());
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void copyTree(Path source,Path destination) throws IOException {
        try(var paths=Files.walk(source)) {for(Path path:paths.toList()) {
            if(Files.isSymbolicLink(path))throw new IOException("Symbolic link in browser host.");
            Path target=destination.resolve(source.relativize(path));
            if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
        }}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: PrebuiltBrowserHost OUTPUT_DIRECTORY");
        prepare(Path.of(args[0]));System.out.println("Prepared browser host "+open(Path.of(args[0])).id());
    }
}
