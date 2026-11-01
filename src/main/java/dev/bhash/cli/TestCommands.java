// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;

import dev.bhash.backend.*;
import dev.bhash.backend.jvm.JvmCompiler;
import dev.bhash.backend.clr.ClrCompiler;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.ProjectLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Each test program gets a separate process and fresh runtime/static state. */
final class TestCommands {
    private TestCommands() {}
    static int run(String[] args,PrintStream out,PrintStream err) {
        if(args.length<2){err.println("Usage: test <file.bhash|directory> [--target jvm|clr|native] [--timeout SECONDS]");return 2;}
        Path temporary=null;Process child=null;
        try {
            Target target=Target.JVM;int seconds=30;boolean selected=false,timed=false;
            for(int i=2;i<args.length;i++) {
                if(args[i].equals("--target")&&!selected&&i+1<args.length){selected=true;target=switch(args[++i]){case "jvm"->Target.JVM;case "clr"->Target.CLR;case "native"->Target.NATIVE;default->throw new IllegalArgumentException("Test targets: jvm, clr, native.");};}
                else if(args[i].equals("--timeout")&&!timed&&i+1<args.length){timed=true;seconds=Integer.parseInt(args[++i]);if(seconds<1||seconds>600)throw new IllegalArgumentException("Test timeout must be 1–600 seconds.");}
                else throw new IllegalArgumentException("Invalid test option: "+args[i]);
            }
            Path input=Path.of(args[1]).toAbsolutePath().normalize();List<Path> tests;
            if(Files.isDirectory(input)){
                try(var paths=Files.walk(input)) {tests=paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&(p.getFileName().toString().endsWith(".test.bhash")
                    ||p.getFileName().toString().equals(ProjectLoader.MANIFEST)&&p.getParent().getFileName().toString().endsWith(".test")))
                    .filter(p->!input.relativize(p).toString().replace('\\','/').matches("(?:.*/)?(?:build|target|\\.bhash|\\.git)/.*"))
                    .sorted().toList();}
            }else if(Files.isRegularFile(input)&&(input.toString().endsWith(".bhash")||input.getFileName().toString().equals(ProjectLoader.MANIFEST)))tests=List.of(input);
            else throw new IllegalArgumentException("Expected a Bhash test file or directory.");
            if(tests.isEmpty())throw new IllegalArgumentException("No *.test.bhash programs found.");
            temporary=Files.createTempDirectory("bhash-tests-");int passed=0,failed=0;
            for(int i=0;i<tests.size();i++) {
                Path source=tests.get(i),work=Files.createDirectory(temporary.resolve("case-"+i));
                Path artifact=work.resolve(target==Target.JVM?"test.jar":target==Target.CLR?"clr":"test"+NativePlatform.executableSuffix());
                try {
                    var project=ProjectLoader.load(source);
                    if(target==Target.JVM)new JvmCompiler().compile(project.sources(),artifact);
                    else if(target==Target.CLR)new ClrCompiler().compile(project.sources(),artifact);
                    else new NativeCompiler().compile(project.sources(),artifact);
                    List<String> command=target==Target.JVM?List.of(Path.of(System.getProperty("java.home"),"bin",NativePlatform.windows()?"java.exe":"java").toString(),"-jar",artifact.toString()):
                        target==Target.CLR?List.of(ClrCompiler.executable(),artifact.resolve("BhashProgram.dll").toString()):List.of(artifact.toString());
                    Path stdout=work.resolve("stdout.txt"),stderr=work.resolve("stderr.txt"),stdin=work.resolve("stdin.txt");Files.write(stdin,new byte[0]);
                    child=new ProcessBuilder(command).directory(source.getParent().toFile()).redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
                    boolean finished=child.waitFor(seconds,TimeUnit.SECONDS);
                    if(!finished){terminate(child);throw new IOException("Test exceeded "+seconds+" seconds.");}
                    int status=child.exitValue();child=null;
                    if(status!=0){printLog(stdout,out);printLog(stderr,err);throw new IOException("Program exited with status "+status+".");}
                    passed++;out.println("PASS "+source.getFileName());
                }catch(CompilationException|IOException error){failed++;err.println("FAIL "+source+": "+error.getMessage());}
                finally{if(child!=null){terminate(child);child=null;}}
            }
            out.println("Tests: "+passed+" passed, "+failed+" failed, "+tests.size()+" total.");return failed==0?0:1;
        }catch(IllegalArgumentException|IOException error){err.println(error.getMessage());return 2;}
        catch(InterruptedException error){Thread.currentThread().interrupt();return 130;}
        finally{
            if(child!=null)terminate(child);
            if(temporary!=null)try{ClrCompiler.deleteTree(temporary);}catch(IOException error){err.println("Could not clean test output: "+error.getMessage());}
        }
    }
    private static void terminate(Process process){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    private static void printLog(Path file,PrintStream stream)throws IOException {
        try(var input=Files.newInputStream(file)){byte[] bytes=input.readNBytes(65536);stream.print(new String(bytes,StandardCharsets.UTF_8));if(input.read()!=-1)stream.println("[test output truncated]");}
    }
}
