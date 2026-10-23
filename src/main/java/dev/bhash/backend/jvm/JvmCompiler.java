// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.jvm;

import dev.bhash.frontend.SourceFile;
import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.Licensing;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

/** Bhash -> checked Java -> Java 17 bytecode -> self-contained executable JAR. */
public final class JvmCompiler {
    public void compile(String sourceName, String source, Path output) throws IOException, CompilationException {
        compile(List.of(new SourceFile(sourceName,source)),output);
    }

    public void compile(List<SourceFile> sources, Path output) throws IOException, CompilationException {
        String java = ManagedEmitter.emit(sources, Target.JVM);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new CompilationException("JVM compilation requires a full JDK 17 or newer (not a JRE).");
        Path temporary = Files.createTempDirectory("bhash-jvm-");
        Path stagedJar = null;
        try {
            Path program = temporary.resolve("BhashProgram.java");
            Files.writeString(program, java, StandardCharsets.UTF_8);
            Path runtime = temporary.resolve("BhashRuntime.java");
            try (var resource = JvmCompiler.class.getResourceAsStream("BhashRuntime.java.txt")) {
                if (resource == null) throw new CompilationException("Compiler installation is missing its JVM runtime source.");
                Files.copy(resource, runtime);
            }
            Path classes = Files.createDirectory(temporary.resolve("classes"));
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var messages = new StringWriter();
            try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                var units = manager.getJavaFileObjects(program.toFile(), runtime.toFile());
                boolean success = compiler.getTask(messages, manager, diagnostics,
                        List.of("--release", "17", "-encoding", "UTF-8", "-proc:none", "-classpath", classes.toString(),
                                "-d", classes.toString()), null, units).call();
                if (!success) {
                    String details = String.join("\n", diagnostics.getDiagnostics().stream()
                            .map(d -> d.getMessage(Locale.ROOT)).toList());
                    throw new CompilationException("JVM backend could not compile the lowered program:\n" + details + messages);
                }
            }
            Path destination = output.toAbsolutePath();
            Files.createDirectories(destination.getParent());
            // Stage beside the destination so successful compilation replaces it atomically when supported.
            stagedJar = Files.createTempFile(destination.getParent(), ".bhash-", ".jar");
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "BhashProgram");
            try (var jar = new JarOutputStream(Files.newOutputStream(stagedJar), manifest);
                 var paths = Files.walk(classes)) {
                Licensing.addToJar(jar);
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(0);
                    jar.putNextEntry(entry);
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
            try { Files.move(stagedJar, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(stagedJar, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            if (stagedJar != null) Files.deleteIfExists(stagedJar);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
