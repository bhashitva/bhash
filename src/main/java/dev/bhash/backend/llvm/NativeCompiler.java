// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.*;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Experimental Linux/Windows x86-64 executable backend using LLVM 18 directly. */
public final class NativeCompiler {

    private final boolean sanitize, debug;
    private final Path sourceRoot;
    public NativeCompiler(){this(false);}
    /** Internal conformance-build option; normal CLI builds have no sanitizer dependency. */
    public NativeCompiler(boolean sanitize){this(sanitize,false);}
    public NativeCompiler(boolean sanitize,boolean debug){this(sanitize,debug,null);}
    public NativeCompiler(boolean sanitize,boolean debug,Path sourceRoot){this.sanitize=sanitize;this.debug=debug;this.sourceRoot=sourceRoot;}
    public static List<SourceFile> normalizeSources(List<SourceFile> sources,Path root)throws IOException{
        Path base=root.toRealPath();var mapped=new ArrayList<SourceFile>();
        for(var source:sources){Path file=Path.of(source.name()).toRealPath();if(!file.startsWith(base))throw new IOException("Native source is outside --source-root: "+source.name());mapped.add(new SourceFile("/bhash/source/"+base.relativize(file).toString().replace('\\','/'),source.text()));}
        return List.copyOf(mapped);
    }
    private static List<String> mapped(Path work,List<String> command){
        var flags=new ArrayList<>(command);flags.add("-ffile-prefix-map="+work+"=/bhash/build");flags.add("-fdebug-prefix-map="+work+"=/bhash/build");flags.add("-fdebug-compilation-dir=/bhash/build");return flags;
    }
    private List<String> clangCommand(List<String> args){
        var command=new ArrayList<>(args);
        command.addAll(List.of("--target="+NativeBuildInfo.TARGET,"-march=x86-64","-mtune=generic"));
        if(debug)command.addAll(List.of("-g","-fno-omit-frame-pointer","-fno-optimize-sibling-calls"));
        if(NativePlatform.windows())command.addAll(List.of("-gno-record-command-line","-fno-record-command-line"));
        if(sanitize)command.addAll(List.of("-fsanitize=address,undefined","-fno-omit-frame-pointer"));
        return command;
    }
    public void compile(List<SourceFile> sources,Path output) throws IOException,CompilationException {
        var core=ManagedEmitter.core(sources); // Diagnose source before invoking external tools.
        NativeToolchain.requirePlatform();
        if(sourceRoot!=null)core=ManagedEmitter.core(normalizeSources(sources,sourceRoot));
        Path destination=output.toAbsolutePath().normalize();
        if(destination.getParent()==null || Files.isSymbolicLink(destination) || Files.isDirectory(destination))
            throw new IOException("Native output must be an executable file, not a directory or symbolic link.");
        Path work=Files.createTempDirectory("bhash-native-"); Path staged=null;
        try {
            var tools=NativeToolchain.require(work);String clang=tools.clang(),opt=tools.opt();
            String buildInfo=NativeBuildInfo.describe(debug,sanitize,tools.version(),tools.linkerVersion(),tools.libcVersion());
            if(tools.managed()){
                var metadata=com.google.gson.JsonParser.parseString(buildInfo).getAsJsonObject();metadata.addProperty("toolchain_id",tools.toolchainId());metadata.addProperty("toolchain_sha256",tools.toolchainSha256());buildInfo=NativeBuildInfo.JSON.toJson(metadata);
            }
            StringBuilder notices=new StringBuilder();
            for(String name:Licensing.APPLICATION_FILES)try(var in=NativeCompiler.class.getResourceAsStream("/META-INF/bhash/"+name)){
                if(in==null)throw new IOException("Missing native licensing resource: "+name);
                notices.append("\n--- ").append(name).append(" ---\n").append(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
            if(NativePlatform.windows())notices.append("\n--- MinGW-w64 runtime notices ---\n").append(new String(NativeBuildInfo.resource("/dev/bhash/backend/native/MINGW_RUNTIME_NOTICES.txt"),StandardCharsets.UTF_8));
            Files.writeString(work.resolve("program.ll"),LlvmEmitter.emit(core,notices.toString(),debug,buildInfo),StandardCharsets.UTF_8);
            for(String name:List.of("bhash_runtime.c","bhash_runtime.h"))try(var in=NativeCompiler.class.getResourceAsStream("/dev/bhash/backend/native/"+name)){
                if(in==null)throw new IOException("Missing native runtime: "+name);
                Files.copy(in,work.resolve(name));
            }
            if(!NativePlatform.windows())NativeToolchain.run(work,List.of(opt,"-passes=verify","-disable-output","program.ll"));
            NativeToolchain.run(work,mapped(work,clangCommand(List.of(clang,debug?"-O0":"-O2","-Xclang","-llvm-verify-each","-c","program.ll","-o","program.o"))));
            NativeToolchain.run(work,mapped(work,clangCommand(List.of(clang,"-std=c17",debug?"-O0":"-O2","-Wall","-Wextra","-Werror","-c","bhash_runtime.c","-o","runtime.o"))));
            NativeToolchain.run(work,clangCommand(List.of(clang,"program.o","runtime.o","-lm",NativePlatform.windows()?"-Wl,--no-insert-timestamp":"-Wl,--build-id=sha1","-o","program"+NativePlatform.executableSuffix())));
            Files.createDirectories(destination.getParent());
            staged=Files.createTempFile(destination.getParent(),".bhash-native-",".tmp");
            Files.copy(work.resolve("program"+NativePlatform.executableSuffix()),staged,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            if(!NativePlatform.windows() && !staged.toFile().setExecutable(true,false))throw new IOException("Cannot set executable permissions: "+staged);
            try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(staged,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {
            if(staged!=null)Files.deleteIfExists(staged);
            try(var files=Files.walk(work)){
                for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
            }
        }
    }
}
