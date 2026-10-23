// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.clr;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.Licensing;
import dev.bhash.frontend.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bhash -> checked C# -> .NET 10 managed DLL and deployment metadata. */
public final class ClrCompiler {
    private static final String MARKER = ".bhash-clr-output";
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
        Path destination = output.toAbsolutePath().normalize();
        validateDestination(destination);
        Files.createDirectories(destination.getParent());
        Path work = Files.createTempDirectory("bhash-clr-build-");
        Path stage = Files.createTempDirectory(destination.getParent(), ".bhash-clr-stage-");
        try {
            Files.writeString(work.resolve("Program.cs"), code, StandardCharsets.UTF_8);
            Files.writeString(work.resolve("BhashProgram.csproj"), PROJECT, StandardCharsets.UTF_8);
            // There are no NuGet dependencies: use SDK-installed reference packs, without external feeds.
            Files.writeString(work.resolve("NuGet.Config"), "<configuration><packageSources><clear /></packageSources></configuration>", StandardCharsets.UTF_8);
            try (var runtime = ClrCompiler.class.getResourceAsStream("BhashRuntime.cs.txt")) {
                if (runtime == null) throw new CompilationException("Compiler installation is missing its CLR runtime source.");
                Files.copy(runtime, work.resolve("BhashRuntime.cs"));
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
            for (String name : FILES) {
                if (!name.equals(MARKER) && !Files.isRegularFile(stage.resolve(name)))
                    throw new CompilationException("CLR backend did not produce " + name);
            }
            Files.writeString(stage.resolve(MARKER), STAMP, StandardCharsets.UTF_8);
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
        if (!stamp.equals(STAMP) && !stamp.equals(LEGACY_STAMP))
            throw new IOException("Refusing to replace a directory not created by the Bhash CLR compiler: " + destination);
        Set<String> allowed=stamp.equals(LEGACY_STAMP)?LEGACY_FILES:FILES;
        try (var entries = Files.list(destination)) {
            for (Path entry : entries.toList()) {
                if (!allowed.contains(entry.getFileName().toString()) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("CLR output contains unrelated files; choose a new output directory: " + destination);
            }
        }
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
