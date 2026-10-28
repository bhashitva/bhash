// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import dev.bhash.backend.*;
import dev.bhash.frontend.SourceFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Experimental Linux x86-64 executable backend using LLVM 18 directly. */
public final class NativeCompiler {
    private static final int TIMEOUT_SECONDS=120, LOG_LIMIT=65536;
    private final boolean sanitize;
    public NativeCompiler(){this(false);}
    /** Internal conformance-build option; normal CLI builds have no sanitizer dependency. */
    public NativeCompiler(boolean sanitize){this.sanitize=sanitize;}
    private List<String> clangCommand(List<String> args){
        var command=new ArrayList<>(args);
        if(sanitize)command.addAll(List.of("-fsanitize=address,undefined","-fno-omit-frame-pointer"));
        return command;
    }
    public void compile(List<SourceFile> sources,Path output) throws IOException,CompilationException {
        var core=ManagedEmitter.core(sources); // Diagnose source before invoking external tools.
        if(!System.getProperty("os.name").equals("Linux") ||
                !Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))
            throw new CompilationException("Native compilation currently requires Linux x86-64 with LLVM 18 (clang-18 and opt-18).");
        Path destination=output.toAbsolutePath().normalize();
        if(destination.getParent()==null || Files.isSymbolicLink(destination) || Files.isDirectory(destination))
            throw new IOException("Native output must be an executable file, not a directory or symbolic link.");
        String clang=executable("BHASH_CLANG","clang-18"),opt=executable("BHASH_LLVM_OPT","opt-18");
        Path work=Files.createTempDirectory("bhash-native-"); Path staged=null;
        try {
            for(String tool:List.of(clang,opt)) {
                String version=command(work,List.of(tool,"--version"));
                if(!version.matches("(?s).*\\bversion\\s+18\\..*"))
                    throw new CompilationException("Native compilation requires LLVM 18 tools; incompatible tool: "+tool);
            }
            StringBuilder notices=new StringBuilder();
            for(String name:Licensing.APPLICATION_FILES)try(var in=NativeCompiler.class.getResourceAsStream("/META-INF/bhash/"+name)){
                if(in==null)throw new IOException("Missing native licensing resource: "+name);
                notices.append("\n--- ").append(name).append(" ---\n").append(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
            Files.writeString(work.resolve("program.ll"),LlvmEmitter.emit(core,notices.toString()),StandardCharsets.UTF_8);
            for(String name:List.of("bhash_runtime.c","bhash_runtime.h"))try(var in=NativeCompiler.class.getResourceAsStream("/dev/bhash/backend/native/"+name)){
                if(in==null)throw new IOException("Missing native runtime: "+name);
                Files.copy(in,work.resolve(name));
            }
            command(work,List.of(opt,"-passes=verify","-disable-output","program.ll"));
            command(work,clangCommand(List.of(clang,"-O2","-c","program.ll","-o","program.o")));
            command(work,clangCommand(List.of(clang,"-std=c17","-O2","-Wall","-Wextra","-Werror","-c","bhash_runtime.c","-o","runtime.o")));
            command(work,clangCommand(List.of(clang,"program.o","runtime.o","-lm","-o","program")));
            Files.createDirectories(destination.getParent());
            staged=Files.createTempFile(destination.getParent(),".bhash-native-",".tmp");
            Files.copy(work.resolve("program"),staged,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            if(!staged.toFile().setExecutable(true,false))throw new IOException("Cannot set executable permissions: "+staged);
            try{Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(staged,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {
            if(staged!=null)Files.deleteIfExists(staged);
            try(var files=Files.walk(work)){
                for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
            }
        }
    }
    private static String executable(String name,String fallback){
        String value=System.getenv(name);return value==null || value.isBlank()?fallback:value;
    }
    private static String command(Path work,List<String> args)throws IOException,CompilationException{
        Process process;
        try{process=new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true).start();}
        catch(IOException e){throw new CompilationException("Cannot start native tool "+args.get(0)+". Install LLVM 18 or configure BHASH_CLANG/BHASH_LLVM_OPT.");}
        var captured=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{
            try(var input=process.getInputStream()){
                byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1) synchronized(captured){captured.write(chunk,0,Math.min(n,LOG_LIMIT-captured.size()));}
            }catch(IOException ignored){/* Failure/timeout is reported by the process boundary. */}
        },"bhash-native-output");
        reader.setDaemon(true);reader.start();
        try{
            if(!process.waitFor(TIMEOUT_SECONDS,TimeUnit.SECONDS))throw new CompilationException("Native tool timed out after "+TIMEOUT_SECONDS+" seconds: "+args.get(0));
            reader.join(2000);
            String message; synchronized(captured){message=captured.toString(StandardCharsets.UTF_8);}
            if(process.exitValue()!=0)throw new CompilationException("Native tool failed: "+args.get(0)+"\n"+message);
            return message;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Native compilation interrupted.",e);}
        finally{
            if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
        }
    }
}
