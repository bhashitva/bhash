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
    public record Report(String target,String version,String clang,String opt,int runtimeAbi,String runtimeSha256,String linkerVersion,String libcVersion,boolean managed,String toolchainId,String toolchainSha256,String readelf){}
    private NativeToolchain(){}
    public static void requirePlatform()throws CompilationException{
        if(!NativePlatform.supported())
            throw new CompilationException("[BH9101] Native compilation requires Linux or Windows x86-64 with LLVM 18. macOS, ARM and cross-compilation are not enabled.");
    }
    private static void requireWindowsVersion()throws CompilationException{
        if(NativePlatform.windows() && NativeCompatibility.compareVersions(System.getProperty("os.version"),"10.0")<0)throw new CompilationException("[BH9111] Native Windows support requires Windows 10 or later (x64 / UCRT).");
    }
    public static String version(String output)throws CompilationException{
        var match=Pattern.compile("\\bversion\\s+(18\\.\\d+\\.\\d+)\\b").matcher(output);
        if(!match.find())throw new CompilationException("[BH9103] Native compilation requires LLVM 18 tools; incompatible tool version: "+output.lines().findFirst().orElse("<empty>"));return match.group(1);
    }
    private record Selection(String clang,String opt,String readelf,String linker,ManagedToolchain.Installation installation){}
    private static Selection select(ManagedToolchain.Installation forced)throws IOException,CompilationException{
        String mode=System.getenv().getOrDefault("BHASH_NATIVE_TOOLCHAIN","auto");
        if(!Set.of("auto","system","managed").contains(mode))throw new CompilationException("[BH9110] BHASH_NATIVE_TOOLCHAIN must be auto, system or managed");
        boolean override=List.of("BHASH_CLANG","BHASH_LLVM_OPT","BHASH_READELF").stream().anyMatch(k->System.getenv(k)!=null);
        if(forced==null && mode.equals("managed") && override)throw new CompilationException("[BH9110] Unset tool overrides when selecting the managed toolchain, or choose system mode");
        var installed=forced;
        if(installed==null && !mode.equals("system") && !override)installed=ManagedToolchain.active();
        if(installed==null && mode.equals("managed"))throw new CompilationException("[BH9110] No managed native toolchain installed. Use native toolchain install --archive <zip> --sha256 <trusted-hash>.");
        if(installed!=null && NativePlatform.windows()){Path bin=installed.root().resolve("bin");return new Selection(bin.resolve("clang.exe").toString(),bin.resolve("clang.exe").toString(),bin.resolve("llvm-readobj.exe").toString(),bin.resolve("ld.lld.exe").toString(),installed);}
        if(installed!=null){Path bin=installed.root().resolve("bin");return new Selection(bin.resolve("clang-18").toString(),bin.resolve("opt-18").toString(),bin.resolve("llvm-readelf").toString(),bin.resolve("ld.lld").toString(),installed);}
        if(NativePlatform.windows())return new Selection(System.getenv().getOrDefault("BHASH_CLANG","clang.exe"),System.getenv().getOrDefault("BHASH_CLANG","clang.exe"),System.getenv().getOrDefault("BHASH_READELF","llvm-readobj.exe"),null,null);
        return new Selection(System.getenv().getOrDefault("BHASH_CLANG","clang-18"),System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"),System.getenv().getOrDefault("BHASH_READELF","readelf"),null,null);
    }
    public static String readelf()throws IOException,CompilationException{return select(null).readelf();}
    public static Report require(Path work)throws IOException,CompilationException{return require(work,null);}
    private static Report require(Path work,ManagedToolchain.Installation forced)throws IOException,CompilationException{
        requirePlatform();requireWindowsVersion();var identity=NativeBuildInfo.identity();var selected=select(forced);
        String libc=NativePlatform.windows()?"Windows UCRT (Windows 10+)":run(work,List.of("getconf","GNU_LIBC_VERSION")).strip();
        var installed=selected.installation();
        if(installed!=null && !NativePlatform.windows()){
            String current=libc.startsWith("glibc ")?libc.substring(6):"0.0";
            if(NativeCompatibility.compareVersions(current,installed.minimumGlibc())<0)throw new CompilationException("[BH9111] Managed LLVM needs host glibc >= "+installed.minimumGlibc()+"; found "+libc+". Use a compatible Linux/WSL distribution or select a compatible system toolchain.");
        }
        String cv=version(run(work,List.of(selected.clang(),"--version"))),ov=version(run(work,List.of(selected.opt(),"--version")));
        if(!cv.equals(ov) || installed!=null && !cv.equals(installed.llvmVersion()))throw new CompilationException("[BH9103] clang and opt versions differ from each other or the selected manifest: "+cv+" / "+ov+". Select a matching LLVM 18 installation.");
        String target=run(work,List.of(selected.clang(),"-dumpmachine")).strip();if(!(NativePlatform.windows()?target.matches("x86_64-(?:w64|pc)-(?:windows-gnu|mingw32)"):target.matches("x86_64-(?:[^-]+-)?linux-gnu")))throw new CompilationException("[BH9104] clang targets "+target+"; select an LLVM 18 toolchain for "+NativeBuildInfo.TARGET+".");
        String linker=selected.linker()!=null?selected.linker():run(work,List.of(selected.clang(),"--print-prog-name="+(NativePlatform.windows()?"ld.lld":"ld"))).strip();
        String lv=run(work,List.of(linker,"--version")).lines().findFirst().orElse("");
        return new Report(NativeBuildInfo.TARGET,cv,selected.clang(),selected.opt(),NativeBuildInfo.ABI,(String)identity.get("runtime_sha256"),lv,libc,installed!=null,installed==null?"system":installed.id(),installed==null?null:installed.archiveSha256(),selected.readelf());
    }
    public static Report doctor()throws IOException,CompilationException{return doctor(null);}
    public static Report doctor(ManagedToolchain.Installation selected)throws IOException,CompilationException{
        requirePlatform();
        Path work=Files.createTempDirectory("bhash-doctor-");try{
            Report report=require(work,selected);Files.writeString(work.resolve("probe.c"),"#include <math.h>\n#include <time.h>\n#include <stdio.h>\nint main(void){struct timespec t;volatile double x=2;return !timespec_get(&t,TIME_UTC) || !isfinite(log(x));}\n");
            try{run(work,List.of(report.clang(),"--target="+report.target(),"-std=c17","probe.c","-lm","-o","probe"+NativePlatform.executableSuffix()));}
            catch(CompilationException error){throw new CompilationException("[BH9105] C17/system-linker probe failed. For system tools install target C17 headers/libraries; for managed tools verify or reinstall the trusted archive.\n"+error.getMessage());}
            run(work,NativePlatform.windows()?List.of(report.readelf(),"--file-headers","probe.exe"):List.of(report.readelf(),"-W","-h","probe"));
            run(work,List.of(work.resolve("probe"+NativePlatform.executableSuffix()).toString()));return report;
        }finally{ManagedToolchain.delete(work);}
    }
    private static ProcessBuilder builder(List<String> args,Path work){var b=new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true);b.environment().put("LC_ALL","C");return b;}
    public static String run(Path work,List<String> args)throws IOException,CompilationException{
        Process process;
        try{process=builder(args,work).start();}
        catch(IOException e){throw new CompilationException("[BH9102] Cannot start native tool "+args.get(0)+". Install a managed bundle with native toolchain install, or install clang-18, llvm-18, build-essential and binutils; see docs/managed-native-toolchain.md.");}
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
