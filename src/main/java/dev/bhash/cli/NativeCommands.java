// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;
import dev.bhash.backend.llvm.*;
import dev.bhash.backend.CompilationException;
import dev.bhash.frontend.ProjectLoader;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class NativeCommands {
    private NativeCommands(){}
    static int library(String[] args, boolean android, PrintStream out, PrintStream err) {
        try {
            if (args.length < 3) throw new IllegalArgumentException("Expected a Bhash source or project.");
            var values = new HashMap<String, String>();
            for (int i = 3; i < args.length; i++) {
                String flag = args[i];
                if (!Set.of("-o", "--exports", "--target", "--abi", "--ndk", "--android-api", "--debug-symbols", "--jni-package").contains(flag)
                        || i + 1 >= args.length || values.containsKey(flag))
                    throw new IllegalArgumentException("Invalid native library option: " + flag);
                values.put(flag, args[++i]);
            }
            for (var flag : List.of("-o", "--exports"))
                if (!values.containsKey(flag)) throw new IllegalArgumentException("Native library builds require " + flag);
            if (!android && values.keySet().stream().anyMatch(Set.of("--target", "--abi", "--ndk", "--android-api", "--debug-symbols", "--jni-package")::contains))
                throw new IllegalArgumentException("Android options require compile --target android.");
            if (values.containsKey("--debug-symbols") && !"embedded".equals(values.get("--debug-symbols")))
                throw new IllegalArgumentException("Use --debug-symbols embedded to let Android Gradle Plugin extract native symbols.");
            if (android && (!"android".equals(values.get("--target")) || !values.containsKey("--abi")))
                throw new IllegalArgumentException("Android library builds require --target android and --abi.");
            var project = ProjectLoader.load(Path.of(args[2]));
            Path output = Path.of(values.get("-o"));
            project.protectOutput(output, true);
            Path manifest = Path.of(values.get("--exports")).toRealPath();
            String ndk = values.getOrDefault("--ndk", System.getenv("ANDROID_NDK_HOME"));
            int api = Integer.parseInt(values.getOrDefault("--android-api", "29"));
            Path library = NativeLibrary.build(project.sources(), manifest, output,
                ndk == null || ndk.isBlank() ? null : Path.of(ndk), values.get("--abi"), api,
                values.containsKey("--debug-symbols"), values.get("--jni-package"));
            out.println("Compiled native library: " + library);
            return 0;
        } catch (CompilationException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
    }
    static int run(String[] args,PrintStream out,PrintStream err){
        try{
            if(args.length<2)throw new IllegalArgumentException("Expected native doctor, toolchain, debugger, inspect, package or library; see docs/native-releases.md");
            switch(args[1]){
                case "library" -> { return library(args, false, out, err); }
                case "toolchain", "debugger" -> {
                    boolean debugger=args[1].equals("debugger");
                    if(args.length<3)throw new IllegalArgumentException("Usage: native toolchain|debugger status|verify|install [--archive FILE|--url HTTPS_URL --sha256 HASH] [--json]");
                    var flags=new HashMap<String,String>();boolean json=false;
                    for(int i=3;i<args.length;i++){
                        if(args[i].equals("--json") && !json){json=true;continue;}
                        if(!Set.of("--archive","--url","--sha256").contains(args[i]) || i+1>=args.length || flags.containsKey(args[i]))throw new IllegalArgumentException("Invalid toolchain option: "+args[i]);flags.put(args[i],args[++i]);
                    }
                    if(args[2].equals("install")){
                        if(!flags.containsKey("--sha256") || flags.containsKey("--archive")==flags.containsKey("--url"))throw new IllegalArgumentException("Install requires exactly one --archive or --url and a trusted --sha256");
                        if(debugger)ManagedDebugger.install(flags.containsKey("--archive")?Path.of(flags.get("--archive")):null,flags.containsKey("--url")?ManagedToolchain.validateUrl(flags.get("--url")):null,flags.get("--sha256"));
                        else ManagedToolchain.install(flags.containsKey("--archive")?Path.of(flags.get("--archive")):null,flags.containsKey("--url")?ManagedToolchain.validateUrl(flags.get("--url")):null,flags.get("--sha256"));
                    }else if(Set.of("status","verify").contains(args[2])){
                        if(!flags.isEmpty())throw new IllegalArgumentException("status/verify accept only --json");
                        if(args[2].equals("verify") && debugger){if(!ManagedDebugger.status().installed())throw new IOException("No managed debugger installed");}
                        if(args[2].equals("verify") && !debugger){var installed=ManagedToolchain.active();if(installed==null)throw new IOException("[BH9110] No managed toolchain installed");ManagedToolchain.verifyFiles(installed.root(),false);}
                    }else throw new IllegalArgumentException("Unknown toolchain action: "+args[2]);
                    if(debugger){var status=ManagedDebugger.status();out.println(json?NativeBuildInfo.JSON.toJson(status):status.toString());break;}
                    var status=ManagedToolchain.status();out.println(json?NativeBuildInfo.JSON.toJson(status):status.installed()?"Managed toolchain: "+status.id()+" (LLVM "+status.llvmVersion()+") at "+status.path():"No managed toolchain installed. Cache: "+status.path());
                }
                case "doctor" -> {
                    if(args.length>3 || args.length==3 && !args[2].equals("--json"))throw new IllegalArgumentException("Usage: native doctor [--json]");
                    var report=NativeToolchain.doctor();
                    out.println(args.length==3?NativeBuildInfo.JSON.toJson(report):"Native toolchain OK: "+report.target()+", LLVM "+report.version()+", runtime ABI "+report.runtimeAbi()+", "+report.libcVersion());
                }
                case "inspect" -> {
                    if(args.length<3)throw new IllegalArgumentException("Usage: native inspect <executable> [--json] [--require-runtime-match]");
                    var flags=new HashSet<String>();for(int i=3;i<args.length;i++)if(!Set.of("--json","--require-runtime-match").contains(args[i]) || !flags.add(args[i]))throw new IllegalArgumentException("Invalid inspect option: "+args[i]);
                    var report=NativeCompatibility.inspect(Path.of(args[2]),flags.contains("--require-runtime-match"));
                    out.println(flags.contains("--json")?NativeBuildInfo.JSON.toJson(report):"Compatible Bhash ABI: "+report.target()+(report.minimumMacos()!=null?", macOS >= "+report.minimumMacos():report.minimumGlibc()==null?", Windows 10+ / UCRT":", glibc >= "+report.minimumGlibc())+", runtime matches compiler: "+report.runtimeMatchesCompiler());
                }
                case "package" -> {
                    if(args.length<3)throw new IllegalArgumentException("Expected a Bhash source or project");
                    var values=new HashMap<String,String>();boolean debug=false;
                    for(int i=3;i<args.length;i++){
                        if(args[i].equals("--debug") && !debug){debug=true;continue;}
                        if(!Set.of("-o","--name","--version","--source-root","--max-glibc").contains(args[i]) || i+1>=args.length || values.containsKey(args[i]))throw new IllegalArgumentException("Invalid or incomplete package option: "+args[i]);
                        values.put(args[i],args[++i]);
                    }
                    if((NativePlatform.windows() || NativePlatform.macos()) && values.containsKey("--max-glibc"))throw new IllegalArgumentException("--max-glibc applies only to Linux packages");
                    for(String required:List.of("-o","--name","--version"))if(!values.containsKey(required))throw new IllegalArgumentException("native package requires "+required);
                    var project=ProjectLoader.load(Path.of(args[2]));if(project.library())throw new IOException("[BH8001] Package an application, not a source library");
                    Path output=Path.of(values.get("-o"));project.protectOutput(output,false);
                    var result=NativePackage.build(project.sources(),values.containsKey("--source-root")?Path.of(values.get("--source-root")):project.root(),output,values.get("--name"),values.get("--version"),debug,values.getOrDefault("--max-glibc","2.39"));
                    out.println("Packaged: "+result.archive());out.println("SHA256: "+result.sha256());out.println(NativePlatform.macos()?"Requires macOS 14+ Apple Silicon (ARM64)":NativePlatform.windows()?"Requires Windows 10+ x64 / UCRT":"Requires Linux x86-64, glibc >= "+result.minimumGlibc());
                }
                default -> throw new IllegalArgumentException("Unknown native command: "+args[1]);
            }return 0;
        }catch(CompilationException e){err.println(e.getMessage());return 1;}
        catch(IOException | IllegalArgumentException e){err.println(e.getMessage());return 2;}
    }
}
