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
    static int run(String[] args,PrintStream out,PrintStream err){
        try{
            if(args.length<2)throw new IllegalArgumentException("Expected native doctor, inspect or package; see docs/native-releases.md");
            switch(args[1]){
                case "doctor" -> {
                    if(args.length>3 || args.length==3 && !args[2].equals("--json"))throw new IllegalArgumentException("Usage: native doctor [--json]");
                    var report=NativeToolchain.doctor();
                    out.println(args.length==3?NativeBuildInfo.JSON.toJson(report):"Native toolchain OK: "+report.target()+", LLVM "+report.version()+", runtime ABI "+report.runtimeAbi()+", "+report.libcVersion());
                }
                case "inspect" -> {
                    if(args.length<3)throw new IllegalArgumentException("Usage: native inspect <executable> [--json] [--require-runtime-match]");
                    var flags=new HashSet<String>();for(int i=3;i<args.length;i++)if(!Set.of("--json","--require-runtime-match").contains(args[i]) || !flags.add(args[i]))throw new IllegalArgumentException("Invalid inspect option: "+args[i]);
                    var report=NativeCompatibility.inspect(Path.of(args[2]),flags.contains("--require-runtime-match"));
                    out.println(flags.contains("--json")?NativeBuildInfo.JSON.toJson(report):"Compatible Bhash ABI: "+report.target()+", glibc >= "+report.minimumGlibc()+", runtime matches compiler: "+report.runtimeMatchesCompiler());
                }
                case "package" -> {
                    if(args.length<3)throw new IllegalArgumentException("Expected a Bhash source or project");
                    var values=new HashMap<String,String>();boolean debug=false;
                    for(int i=3;i<args.length;i++){
                        if(args[i].equals("--debug") && !debug){debug=true;continue;}
                        if(!Set.of("-o","--name","--version","--source-root","--max-glibc").contains(args[i]) || i+1>=args.length || values.containsKey(args[i]))throw new IllegalArgumentException("Invalid or incomplete package option: "+args[i]);
                        values.put(args[i],args[++i]);
                    }
                    for(String required:List.of("-o","--name","--version"))if(!values.containsKey(required))throw new IllegalArgumentException("native package requires "+required);
                    var project=ProjectLoader.load(Path.of(args[2]));if(project.library())throw new IOException("[BH8001] Package an application, not a source library");
                    Path output=Path.of(values.get("-o"));project.protectOutput(output,false);
                    var result=NativePackage.build(project.sources(),values.containsKey("--source-root")?Path.of(values.get("--source-root")):project.root(),output,values.get("--name"),values.get("--version"),debug,values.getOrDefault("--max-glibc","2.39"));
                    out.println("Packaged: "+result.archive());out.println("SHA256: "+result.sha256());out.println("Requires Linux x86-64, glibc >= "+result.minimumGlibc());
                }
                default -> throw new IllegalArgumentException("Unknown native command: "+args[1]);
            }return 0;
        }catch(CompilationException e){err.println(e.getMessage());return 1;}
        catch(IOException | IllegalArgumentException e){err.println(e.getMessage());return 2;}
    }
}
