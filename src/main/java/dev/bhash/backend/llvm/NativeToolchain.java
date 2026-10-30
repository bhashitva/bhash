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
            throw new CompilationException("[BH9101] Native compilation requires Linux/Windows x86-64 or macOS 14+ Apple Silicon (ARM64), with LLVM 18. Intel Macs, other ARM hosts and cross-compilation are not enabled.");
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
        if(installed!=null && NativePlatform.macos()){Path bin=installed.root().resolve("bin");return new Selection(bin.resolve("clang-18").toString(),bin.resolve("opt-18").toString(),bin.resolve("llvm-readobj").toString(),null,installed);}
        if(installed!=null && NativePlatform.windows()){Path bin=installed.root().resolve("bin");return new Selection(bin.resolve("clang.exe").toString(),bin.resolve("clang.exe").toString(),bin.resolve("llvm-readobj.exe").toString(),bin.resolve("ld.lld.exe").toString(),installed);}
        if(installed!=null){Path bin=installed.root().resolve("bin");return new Selection(bin.resolve("clang-18").toString(),bin.resolve("opt-18").toString(),bin.resolve("llvm-readelf").toString(),bin.resolve("ld.lld").toString(),installed);}
        if(NativePlatform.windows())return new Selection(System.getenv().getOrDefault("BHASH_CLANG","clang.exe"),System.getenv().getOrDefault("BHASH_CLANG","clang.exe"),System.getenv().getOrDefault("BHASH_READELF","llvm-readobj.exe"),null,null);
        return new Selection(System.getenv().getOrDefault("BHASH_CLANG","clang-18"),System.getenv().getOrDefault("BHASH_LLVM_OPT","opt-18"),System.getenv().getOrDefault("BHASH_READELF",NativePlatform.macos()?"llvm-readobj":"readelf"),null,null);
    }
    public static String readelf()throws IOException,CompilationException{return select(null).readelf();}
    public static Report require(Path work)throws IOException,CompilationException{return require(work,null);}
    private static Report require(Path work,ManagedToolchain.Installation forced)throws IOException,CompilationException{
        requirePlatform();requireWindowsVersion();var identity=NativeBuildInfo.identity();var selected=select(forced);
        var apple=NativePlatform.macos()?MacosToolchain.require(work):null;
        String libc=apple!=null?"macOS "+NativePlatform.MINIMUM_MACOS+"+ / libSystem / Apple SDK "+apple.sdkVersion():NativePlatform.windows()?"Windows UCRT (Windows 10+)":run(work,List.of("getconf","GNU_LIBC_VERSION")).strip();
        var installed=selected.installation();
        if(installed!=null && !NativePlatform.windows() && !NativePlatform.macos()){
            String current=libc.startsWith("glibc ")?libc.substring(6):"0.0";
            if(NativeCompatibility.compareVersions(current,installed.minimumGlibc())<0)throw new CompilationException("[BH9111] Managed LLVM needs host glibc >= "+installed.minimumGlibc()+"; found "+libc+". Use a compatible Linux/WSL distribution or select a compatible system toolchain.");
        }
        String cv=version(run(work,List.of(selected.clang(),"--version"))),ov=version(run(work,List.of(selected.opt(),"--version")));
        if(!cv.equals(ov) || installed!=null && !cv.equals(installed.llvmVersion()))throw new CompilationException("[BH9103] clang and opt versions differ from each other or the selected manifest: "+cv+" / "+ov+". Select a matching LLVM 18 installation.");
        String target=run(work,List.of(selected.clang(),"-dumpmachine")).strip();if(!(NativePlatform.macos()?target.matches("(?:arm64|aarch64)-apple-(?:darwin|macosx)[0-9.]*"):NativePlatform.windows()?target.matches("x86_64-(?:w64|pc)-(?:windows-gnu|mingw32)"):target.matches("x86_64-(?:[^-]+-)?linux-gnu")))throw new CompilationException("[BH9104] clang targets "+target+"; select an LLVM 18 toolchain for "+NativeBuildInfo.TARGET+".");
        String linker=apple!=null?apple.linker():selected.linker()!=null?selected.linker():run(work,List.of(selected.clang(),"--print-prog-name="+(NativePlatform.windows()?"ld.lld":"ld"))).strip();
        String lv=apple!=null?"Apple ld; SDK "+apple.sdkVersion()+"; SHA256 "+NativeBuildInfo.fileHash(Path.of(linker)):run(work,List.of(linker,"--version")).lines().findFirst().orElse("");
        return new Report(NativeBuildInfo.TARGET,cv,selected.clang(),NativePlatform.windows()?null:selected.opt(),NativeBuildInfo.ABI,(String)identity.get("runtime_sha256"),lv,libc,installed!=null,installed==null?"system":installed.id(),installed==null?null:installed.archiveSha256(),selected.readelf());
    }
    public static Report doctor()throws IOException,CompilationException{return doctor(null);}
    public static Report doctor(ManagedToolchain.Installation selected)throws IOException,CompilationException{
        requirePlatform();
        Path work=Files.createTempDirectory("bhash-doctor-");try{
            Report report=require(work,selected);Files.writeString(work.resolve("probe.c"),"#include <math.h>\n#include <time.h>\n#include <stdio.h>\nint main(void){struct timespec t;volatile double x=2;return !timespec_get(&t,TIME_UTC) || !isfinite(log(x));}\n");
            try{
                if(NativePlatform.windows()){
                    run(work,List.of(report.clang(),"--target="+report.target(),"-std=c17","-c","probe.c","-o","probe.o"));
                    run(work,List.of(report.clang(),"--target="+report.target(),"probe.o","-lm","-o","probe.exe"));
                }else run(work,List.of(report.clang(),"--target="+report.target(),"-std=c17","probe.c","-lm","-o","probe"+NativePlatform.executableSuffix()));
            }
            catch(CompilationException error){throw new CompilationException("[BH9105] C17/system-linker probe failed. For system tools install target C17 headers/libraries; for managed tools verify or reinstall the trusted archive.\n"+error.getMessage());}
            run(work,NativePlatform.windows() || NativePlatform.macos()?List.of(report.readelf(),"--file-headers","probe"+NativePlatform.executableSuffix()):List.of(report.readelf(),"-W","-h","probe"));
            run(work,List.of(work.resolve("probe"+NativePlatform.executableSuffix()).toString()));return report;
        }finally{ManagedToolchain.delete(work);}
    }
    private static ProcessBuilder builder(List<String> args,Path work){var b=new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true);b.environment().put("LC_ALL","C");return b;}
    // The MinGW clang wrapper and LLVM 18 driver start descendants without
    // CREATE_NO_WINDOW. Invoke the real driver, integrated cc1, and linker ourselves.
    static List<String> windowsClangInvocation(List<String> args){
        var command=new ArrayList<>(args);
        Path driver=Path.of(command.get(0));
        if(driver.getFileName().toString().equalsIgnoreCase("clang.exe") && driver.getParent()!=null){
            Path real=driver.resolveSibling("clang-18.exe");
            if(Files.isRegularFile(real))command.set(0,real.toString());
        }
        if(command.stream().noneMatch(a->a.startsWith("--target=") || a.equals("-target")))command.add("--target="+NativeBuildInfo.TARGET);
        if(command.contains("-c"))command.add("-fintegrated-cc1");
        return List.copyOf(command);
    }
    static List<String> windowsLinkCommand(String plan)throws CompilationException{
        List<String> result=null;
        try{
            for(String line:plan.lines().toList()){
                String text=line.strip();if(!text.startsWith("\""))continue;
                if(result!=null)throw new IllegalArgumentException("expected one linker job");
                var words=new ArrayList<String>();int pos=0;
                while(pos<text.length()){
                    while(pos<text.length() && Character.isWhitespace(text.charAt(pos)))pos++;
                    if(pos==text.length())break;
                    if(text.charAt(pos)!='"')throw new IllegalArgumentException("unquoted linker argument");
                    int start=pos++;boolean ended=false;
                    while(pos<text.length()){
                        char c=text.charAt(pos++);
                        if(c=='\\'){if(pos<text.length())pos++;else throw new IllegalArgumentException("truncated escape");}
                        else if(c=='"'){ended=true;break;}
                    }
                    if(!ended)throw new IllegalArgumentException("unterminated argument");
                    words.add(com.google.gson.JsonParser.parseString(text.substring(start,pos)).getAsString());
                }
                if(words.isEmpty())throw new IllegalArgumentException("missing linker");
                String executable=words.get(0).replace('\\','/');
                String name=executable.substring(executable.lastIndexOf('/')+1);
                if(!name.equalsIgnoreCase("ld.lld") && !name.equalsIgnoreCase("ld.lld.exe"))throw new IllegalArgumentException("expected GNU ld.lld");
                result=List.copyOf(words);
            }
            if(result==null)throw new IllegalArgumentException("missing linker job");
            return result;
        }catch(RuntimeException error){throw new CompilationException("[BH9103] Unsupported Windows LLVM linker plan: "+error.getMessage());}
    }
    public static String run(Path work,List<String> args)throws IOException,CompilationException{
        if(NativePlatform.macos()){
            String name=Path.of(args.get(0)).getFileName().toString();
            if((name.equals("clang") || name.equals("clang-18") || args.get(0).equals(System.getenv("BHASH_CLANG"))) && (args.contains("-c") || args.contains("-o")))
                return runProcess(work,MacosToolchain.compilerCommand(args,MacosToolchain.require(work)));
        }
        if(NativePlatform.windows()){
            String name=Path.of(args.get(0)).getFileName().toString().toLowerCase(Locale.ROOT);
            if(name.equals("clang.exe") || name.equals("clang-18.exe")){
                var command=windowsClangInvocation(args);
                if(command.contains("-o") && !command.contains("-c")){
                    var plan=new ArrayList<>(command);plan.add("-###");
                    return runProcess(work,windowsLinkCommand(runProcess(work,plan)));
                }
                return runProcess(work,command);
            }
        }
        return runProcess(work,args);
    }
    private static String runProcess(Path work,List<String> args)throws IOException,CompilationException{
        Process process;
        try{process=builder(args,work).start();}
        catch(IOException e){throw new CompilationException("[BH9102] Cannot start native tool "+args.get(0)+". Use native toolchain install for the managed SDK, or install a matching LLVM 18 target toolchain; see docs/managed-native-toolchain.md. Cause: "+e.getMessage());}
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
            if(process.exitValue()!=0)throw new CompilationException("Native tool failed (exit code "+process.exitValue()+", "+String.format(Locale.ROOT,"0x%08X",process.exitValue())+"): "+args.get(0)+"\n"+message);
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
