// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import dev.bhash.backend.CompilationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Checks the actual configured tool versions, target and system C/linker installation. */
public final class NativeToolchain {
    private static final int TIMEOUT_SECONDS=120,LOG_LIMIT=65536;
    public record Report(String target,String version,String clang,String opt,int runtimeAbi,String runtimeSha256,String linkerVersion,String libcVersion){}
    private NativeToolchain(){}
    public static void requirePlatform()throws CompilationException{
        if(!System.getProperty("os.name").equals("Linux") || !Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))
            throw new CompilationException("[BH9101] Native compilation currently requires Linux x86-64 with LLVM 18 (clang-18 and opt-18). Use x86-64 Linux/WSL; cross-compilation is not enabled.");
    }
    public static String version(String output)throws CompilationException{
        var match=Pattern.compile("\\bversion\\s+(18\\.\\d+\\.\\d+)\\b").matcher(output);
        if(!match.find())throw new CompilationException("[BH9103] Native compilation requires LLVM 18 tools; incompatible tool version: "+output.lines().findFirst().orElse("<empty>"));return match.group(1);
    }
    public static Report require(Path work)throws IOException,CompilationException{
        requirePlatform();var identity=NativeBuildInfo.identity();String clang=System.getenv().getOrDefault("BHASH_CLANG","clang-18"),opt=System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18");
        String cv=version(run(work,List.of(clang,"--version"))),ov=version(run(work,List.of(opt,"--version")));
        if(!cv.equals(ov))throw new CompilationException("[BH9103] clang and opt versions differ: "+cv+" / "+ov+". Select a matching LLVM 18 installation using BHASH_CLANG and BHASH_LLVM_OPT.");
        String target=run(work,List.of(clang,"-dumpmachine")).strip();if(!target.matches("x86_64-(?:[^-]+-)?linux-gnu"))throw new CompilationException("[BH9104] clang targets "+target+"; native releases require x86-64 Linux glibc.");
        String linker=run(work,List.of(clang,"--print-prog-name=ld")).strip();
        String lv=run(work,List.of(linker,"--version")).lines().findFirst().orElse("");
        String libc=run(work,List.of("getconf","GNU_LIBC_VERSION")).strip();
        return new Report(NativeBuildInfo.TARGET,cv,clang,opt,NativeBuildInfo.ABI,(String)identity.get("runtime_sha256"),lv,libc);
    }
    public static Report doctor()throws IOException,CompilationException{
        requirePlatform();
        Path work=Files.createTempDirectory("bhash-doctor-");try{
            Report report=require(work);Files.writeString(work.resolve("probe.c"),"#include <math.h>\n#include <time.h>\n#include <stdio.h>\nint main(void){struct timespec t;volatile double x=2;return !timespec_get(&t,TIME_UTC) || !isfinite(log(x));}\n");
            try{run(work,List.of(report.clang(),"--target="+report.target(),"-std=c17","probe.c","-lm","-o","probe"));}
            catch(CompilationException error){throw new CompilationException("[BH9105] LLVM is available but the C17/system-linker probe failed. Install build-essential, libc development headers and binutils.\n"+error.getMessage());}
            run(work,List.of(System.getenv().getOrDefault("BHASH_READELF","readelf"),"--version"));return report;
        }finally{try(var paths=Files.walk(work)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static ProcessBuilder builder(List<String> args,Path work){var b=new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true);b.environment().put("LC_ALL","C");return b;}
    public static String run(Path work,List<String> args)throws IOException,CompilationException{
        Process process;
        try{process=builder(args,work).start();}
        catch(IOException e){throw new CompilationException("[BH9102] Cannot start native tool "+args.get(0)+". Install clang-18, llvm-18, build-essential and binutils; see docs/native-releases.md.");}
        var captured=new ByteArrayOutputStream();var overflow=new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader=new Thread(()->{
            try(var input=process.getInputStream()){
                byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1) synchronized(captured){int keep=Math.min(n,LOG_LIMIT-captured.size());captured.write(chunk,0,keep);if(keep<n)overflow.set(true);}
            }catch(IOException ignored){/* Failure/timeout is reported by the process boundary. */}
        },"bhash-native-output");
        reader.setDaemon(true);reader.start();
        try{
            if(!process.waitFor(TIMEOUT_SECONDS,TimeUnit.SECONDS))throw new CompilationException("Native tool timed out after "+TIMEOUT_SECONDS+" seconds: "+args.get(0));
            reader.join(2000);
            if(reader.isAlive())throw new CompilationException("Native tool output did not close: "+args.get(0));
            if(overflow.get())throw new CompilationException("Native tool output exceeded "+LOG_LIMIT+" bytes: "+args.get(0));
            String message; synchronized(captured){message=captured.toString(StandardCharsets.UTF_8);}
            if(process.exitValue()!=0)throw new CompilationException("Native tool failed: "+args.get(0)+"\n"+message);
            return message;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Native compilation interrupted.",e);}
        finally{
            var children=process.descendants().toList();children.forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly();
            boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try{var handles=new ArrayList<ProcessHandle>(children);handles.add(process.toHandle());for(var child:handles)while(child.isAlive()){long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Native tool cleanup timed out");try{child.onExit().get(left,TimeUnit.NANOSECONDS);}catch(InterruptedException e){interrupted=true;}catch(ExecutionException | TimeoutException e){throw new IOException("Native tool cleanup failed",e);}}}
            finally{try(var output=process.getOutputStream();var input=process.getInputStream();var error=process.getErrorStream()){}finally{if(interrupted)Thread.currentThread().interrupt();}}
        }
    }
}
