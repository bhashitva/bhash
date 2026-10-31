// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;

import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate, user-local Windows GDB/Python payload; never a compiler dependency. */
public final class ManagedDebugger {
    public static final String ID="bhash-windows-x86_64-gdb-v1";
    public record Status(boolean installed,String archiveSha256,String path,String gdb){}
    private ManagedDebugger(){}
    public static Path home(){
        String configured=System.getenv("BHASH_DEBUGGER_HOME");
        Path result=configured==null?ManagedToolchain.home().resolveSibling("debuggers"):Path.of(configured);
        if(!result.isAbsolute())throw new IllegalArgumentException("BHASH_DEBUGGER_HOME must be absolute");
        return result.normalize();
    }
    public static void verify(Path root)throws IOException{
        var data=ManagedToolchain.json(root.resolve("debugger.json"));
        if(!data.has("format") || !"1".equals(data.get("format").toString()) || !ID.equals(ManagedToolchain.string(data,"id")))throw new IOException("Unsupported debugger manifest");
        if(!data.has("files") || !data.get("files").isJsonObject() || data.getAsJsonObject("files").size()>50000)throw new IOException("Missing/oversized debugger inventory");
        var files=data.getAsJsonObject("files");
        for(String name:List.of("bin/gdb.exe","share/gdb/python/gdb/__init__.py","NOTICE.txt"))if(!files.has(name))throw new IOException("Incomplete debugger: "+name);
        var expected=new HashSet<String>();expected.add("debugger.json");expected.add(".installed.json");
        for(var entry:files.entrySet()){
            Path file=ManagedToolchain.safe(root,entry.getKey());
            if(!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())throw new IOException("Invalid debugger inventory hash");
            String hash=entry.getValue().getAsString();
            if(!expected.add(entry.getKey()) || !hash.matches("[0-9a-f]{64}") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !NativeBuildInfo.fileHash(file).equals(hash))throw new IOException("Debugger file checksum mismatch: "+entry.getKey());
        }
        try(var paths=Files.walk(root)){for(Path file:paths.toList())if(Files.isSymbolicLink(file) || !Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS) && !expected.contains(root.relativize(file).toString().replace('\\','/')))throw new IOException("Unexpected debugger file: "+file);}
    }
    public static Status status()throws IOException{
        Path cache=home(),pointer=cache.resolve("active.json");
        if(!Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))return new Status(false,null,cache.toString(),null);
        var receipt=ManagedToolchain.json(pointer);String hash=ManagedToolchain.string(receipt,"archive_sha256"),directory=ManagedToolchain.string(receipt,"directory");
        ManagedToolchain.validateDigest(hash);
        if(!directory.matches(hash.substring(0,16)+"-[0-9a-f-]{36}"))throw new IOException("Invalid debugger receipt directory");
        Path root=cache.resolve("installations").resolve(directory);
        if(Files.isSymbolicLink(root))throw new IOException("Debugger root must not be a symbolic link");
        var installed=ManagedToolchain.json(root.resolve(".installed.json"));
        if(!hash.equals(ManagedToolchain.string(installed,"archive_sha256")) || !NativeBuildInfo.fileHash(root.resolve("debugger.json")).equals(ManagedToolchain.string(installed,"manifest_sha256")))throw new IOException("Debugger receipt mismatch");
        verify(root); // Detect damaged or modified caches before any execution, including offline reuse.
        return new Status(true,hash,root.toString(),root.resolve("bin/gdb.exe").toString());
    }
    static int startupTimeoutSeconds(String configured){
        try {
            int seconds=configured==null?300:Integer.parseInt(configured);
            if(seconds<15 || seconds>600)throw new NumberFormatException();
            return seconds;
        }catch(NumberFormatException error){throw new IllegalArgumentException("BHASH_DEBUGGER_STARTUP_TIMEOUT_SECONDS must be an integer from 15 to 600");}
    }
    @FunctionalInterface interface ProbeLauncher {Process start(ProcessBuilder builder)throws IOException;}
    private static void probe(Path root)throws IOException{
        probe(root,startupTimeoutSeconds(System.getenv("BHASH_DEBUGGER_STARTUP_TIMEOUT_SECONDS")),ProcessBuilder::start);
    }
    static void probe(Path root,int timeoutSeconds,ProbeLauncher launcher)throws IOException{
        String prefix=NativeBuildInfo.JSON.toJson(root.toString());
        String python="import gdb, json, struct, sys, ctypes, os; assert os.path.normcase(os.path.realpath(sys.prefix)) == os.path.normcase(os.path.realpath("+prefix+")), 'Bundled Python prefix required'; assert ctypes.windll.kernel32.GetACP() == 65001, 'Windows 10 1903+ UTF-8 process support required'; from gdb.FrameDecorator import FrameDecorator; assert sys.version_info.major == 3; print('BHASH_GDB_READY')";
        // Avoid nested double quotes in Windows Java 17/MinGW argv parsing.
        String encoded=Base64.getEncoder().encodeToString(python.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var builder=new ProcessBuilder(root.resolve("bin/gdb.exe").toString(),"--data-directory="+root.resolve("share/gdb"),"--nx","--nh","--batch","-eiex","set python ignore-environment on","-eiex","set python dont-write-bytecode on","-iex","set auto-load off","-ex","python import base64; exec(base64.b64decode('"+encoded+"'))");
        builder.directory(root.toFile());builder.redirectErrorStream(true);
        builder.environment().keySet().removeIf(key->Set.of("PYTHONHOME","PYTHONPATH","PYTHONSTARTUP").contains(key.toUpperCase(Locale.ROOT)));
        Path output=Files.createTempFile(root,".probe-",".log");
        try{
            builder.redirectOutput(output.toFile());var process=launcher.start(builder);
            try{
                if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)){stopProbe(process);throw new IOException("GDB startup timed out after "+timeoutSeconds+" seconds. Security software may still be reviewing it; let that review finish and retry, or increase BHASH_DEBUGGER_STARTUP_TIMEOUT_SECONDS.");}
                if(Files.size(output)>65536 || process.exitValue()!=0 || !Files.readString(output).contains("BHASH_GDB_READY"))throw new IOException("GDB/Python cannot run from the installed debugger: "+Files.readString(output));
            }catch(InterruptedException e){stopProbe(process);Thread.currentThread().interrupt();throw new IOException("Debugger setup cancelled",e);}
        }finally{Files.deleteIfExists(output);}
    }
    private static void stopProbe(Process process){
        process.destroyForcibly();
        try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}
    }
    public static Status install(Path archive,URI url,String hash)throws IOException{
        if(!NativePlatform.windows() || !System.getProperty("os.arch").matches("amd64|x86_64"))throw new IOException("Managed debugger requires Windows x64");
        ManagedToolchain.validateDigest(hash);hash=hash.toLowerCase(Locale.ROOT);
        if((archive==null)==(url==null))throw new IllegalArgumentException("Choose exactly one --archive or --url");
        Path cache=home();Files.createDirectories(cache);
        try(var channel=FileChannel.open(cache.resolve(".install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another debugger installation is in progress");
            Path work=Files.createTempDirectory(cache,".install-"),publishedRoot=null;boolean active=false;
            try{
                Path input=archive;if(url!=null){input=work.resolve("download.zip");ManagedToolchain.download(ManagedToolchain.validateUrl(url.toString()),input);}
                if(!Files.isRegularFile(input) || Files.size(input)>ManagedToolchain.MAX_ARCHIVE || !NativeBuildInfo.fileHash(input).equals(hash))throw new IOException("Debugger archive SHA-256 mismatch; previous installation preserved");
                Path stage=work.resolve("payload");ManagedToolchain.extractArchive(input,stage);verify(stage);probe(stage);
                String directory=hash.substring(0,16)+"-"+UUID.randomUUID();
                Files.writeString(stage.resolve(".installed.json"),NativeBuildInfo.JSON.toJson(Map.of("archive_sha256",hash,"manifest_sha256",NativeBuildInfo.fileHash(stage.resolve("debugger.json"))))+"\n");
                Files.createDirectories(cache.resolve("installations"));publishedRoot=cache.resolve("installations").resolve(directory);
                Files.move(stage,publishedRoot,StandardCopyOption.ATOMIC_MOVE);
                Path pointer=work.resolve("active.json");Files.writeString(pointer,NativeBuildInfo.JSON.toJson(Map.of("archive_sha256",hash,"directory",directory))+"\n");
                Files.move(pointer,cache.resolve("active.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);active=true;
                return status();
            }finally{if(!active && publishedRoot!=null)ManagedToolchain.delete(publishedRoot);ManagedToolchain.delete(work);}
        }catch(OverlappingFileLockException e){throw new IOException("Another debugger installation is in progress",e);}
    }
}
