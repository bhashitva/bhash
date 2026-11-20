// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.jvm;

import dev.bhash.frontend.SourceFile;
import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.Target;
import dev.bhash.backend.Licensing;
import dev.bhash.backend.SqliteAssets;
import dev.bhash.backend.PostgresAssets;
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
        compileJava(ManagedEmitter.emit(sources, Target.JVM), output, false,sources);
    }

    public void compileNotebook(List<SourceFile> sources, ManagedEmitter.NotebookCell cell, Path output)
            throws IOException, CompilationException {
        compileJava(ManagedEmitter.emitNotebook(sources, cell), output, true,sources);
    }

    private static void copyNativeRuntime(Class<?> type,Path classes)throws IOException {
        String name=type.getName().replace('.','/')+".class";Path destination=classes.resolve(name);Files.createDirectories(destination.getParent());
        try(var input=type.getResourceAsStream("/"+name)){if(input==null)throw new IOException("Missing JVM FFI runtime class: "+name);Files.copy(input,destination);}
        for(var nested:type.getDeclaredClasses())copyNativeRuntime(nested,classes);
    }
    private void compileJava(String java, Path output, boolean disposable,List<SourceFile> sources) throws IOException, CompilationException {
        boolean sqlite=java.contains("BhashStandard.db");
        boolean postgres=java.contains("BhashStandard.pg")||java.contains("BhashStandard.dbAsyncStart");
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
            Path standard=temporary.resolve("BhashStandard.java");
            try(var resource=JvmCompiler.class.getResourceAsStream("BhashStandard.java.txt")) {
                if(resource==null)throw new CompilationException("Missing JVM standard library runtime.");
                String text=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
                for(String helper:List.of("Postgres.java.inc","AsyncDatabase.java.inc","SqlServer.java.inc"))try(var pg=JvmCompiler.class.getResourceAsStream(helper)) {
                    if(pg==null)throw new CompilationException("Missing PostgreSQL runtime source.");
                    int end=text.lastIndexOf('}');
                    text=text.substring(0,end)+new String(pg.readAllBytes(),StandardCharsets.UTF_8)+text.substring(end);
                }
                Files.writeString(standard,text,StandardCharsets.UTF_8);
            }
            Path classes = Files.createDirectory(temporary.resolve("classes"));
            var javaFiles=new ArrayList<Path>(List.of(program,runtime,standard));
            if(java.contains("dev.bhash.ffi.NativeModules")){
                copyNativeRuntime(dev.bhash.ffi.NativeModules.class,classes);
                var modules=new LinkedHashMap<String,dev.bhash.ffi.ForeignManifest>();
                for(var source:sources)if(source.foreign()!=null)modules.putIfAbsent(source.foreign().fingerprint(),source.foreign());
                var bundled=dev.bhash.ffi.ForeignCatalog.compression();if(java.contains(dev.bhash.ffi.ForeignJniGenerator.className(bundled)))modules.putIfAbsent(bundled.fingerprint(),bundled);
                for(var module:modules.values()){
                    boolean used=java.contains("/*bhash-ffi:"+module.fingerprint()+"*/");
                    String sha="0".repeat(64),suffix=System.getProperty("os.name").startsWith("Windows")?".dll":System.getProperty("os.name").contains("Mac")?".dylib":".so";
                    String resource="/dev/bhash/ffi/assets/"+module.fingerprint()+suffix;
                    if(used){
                        var artifact=dev.bhash.ffi.ForeignArtifacts.select(module,dev.bhash.ffi.ForeignArtifacts.host(),"shared");
                        Path library=dev.bhash.ffi.ForeignArtifacts.resolve(module,dev.bhash.ffi.ForeignArtifacts.host(),"shared",temporary);sha=artifact.sha256();
                        Path asset=classes.resolve(resource.substring(1));Files.createDirectories(asset.getParent());Files.copy(library,asset);
                        Path license=classes.resolve("META-INF/bhash/ffi/"+module.fingerprint()+"/LICENSE.txt");Files.createDirectories(license.getParent());Files.write(license,dev.bhash.ffi.ForeignArtifacts.license(module,artifact));
                    }
                    Path adapter=temporary.resolve(dev.bhash.ffi.ForeignJniGenerator.className(module)+".java");
                    Files.writeString(adapter,dev.bhash.ffi.ForeignJniGenerator.adapterJava(module,sha,resource,suffix),StandardCharsets.UTF_8);javaFiles.add(adapter);
                }
            }
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var messages = new StringWriter();
            try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                var units = manager.getJavaFileObjectsFromPaths(javaFiles);
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
            stagedJar = disposable ? Files.createFile(destination) : Files.createTempFile(destination.getParent(), ".bhash-", ".jar");
            var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "BhashProgram");
            if(sqlite || java.contains("dev.bhash.ffi.NativeModules"))manifest.getMainAttributes().putValue("Enable-Native-Access","ALL-UNNAMED");
            try (var jar = new JarOutputStream(Files.newOutputStream(stagedJar), manifest);
                 var paths = Files.walk(classes)) {
                Licensing.addToJar(jar);
                if(sqlite)SqliteAssets.addJdbc(jar);
                if(postgres)PostgresAssets.addJdbc(jar);
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                    entry.setTime(0);
                    jar.putNextEntry(entry);
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
            if (!disposable) {
                try { Files.move(stagedJar, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(stagedJar, destination, StandardCopyOption.REPLACE_EXISTING); }
            }
            stagedJar = null;
        } finally {
            if (stagedJar != null) Files.deleteIfExists(stagedJar);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
