// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.llvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedDebuggerTest {
    @TempDir Path directory;
    private Path payload()throws Exception{
        Path root=directory.resolve("debugger");Files.createDirectories(root);
        Map<String,String> files=new TreeMap<>();
        for(String name:List.of("bin/gdb.exe","share/gdb/python/gdb/__init__.py","NOTICE.txt")){
            Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"fixture "+name);files.put(name,NativeBuildInfo.fileHash(file));
        }
        Files.writeString(root.resolve("debugger.json"),NativeBuildInfo.JSON.toJson(Map.of("format",1,"id",ManagedDebugger.ID,"files",files)));
        return root;
    }
    @Test void rejectsModifiedMissingAndUnlistedFiles()throws Exception{
        Path root=payload();ManagedDebugger.verify(root);
        Path gdb=root.resolve("bin/gdb.exe");String original=Files.readString(gdb);
        Files.writeString(gdb,"modified");assertThrows(IOException.class,()->ManagedDebugger.verify(root));
        Files.delete(gdb);assertThrows(IOException.class,()->ManagedDebugger.verify(root));
        Files.writeString(gdb,original);Files.writeString(root.resolve("injected.dll"),"injected");assertThrows(IOException.class,()->ManagedDebugger.verify(root));
    }
    @Test void rejectsWrongIdentityAndPathTraversalInventory()throws Exception{
        Path root=payload(),manifest=root.resolve("debugger.json");String valid=Files.readString(manifest);
        Files.writeString(manifest,valid.replace(ManagedDebugger.ID,"other-debugger"));assertThrows(IOException.class,()->ManagedDebugger.verify(root));
        Files.writeString(manifest,valid.replace("bin/gdb.exe","../gdb.exe"));assertThrows(IOException.class,()->ManagedDebugger.verify(root));
    }
    @Test void safeExtractionNeverWritesOutsideStaging()throws Exception{
        for(String name:List.of("../escape","C:/escape","bin/../../escape","bin/CON.txt","bin\\escape")){
            Path zip=directory.resolve("bad.zip");
            try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry(name));out.write(1);out.closeEntry();}
            Path stage=directory.resolve(UUID.randomUUID().toString());assertThrows(IOException.class,()->ManagedToolchain.extractArchive(zip,stage));
            assertFalse(Files.exists(directory.resolve("escape")));
        }
    }

    public static class SlowProbe {
        public static void main(String[] args)throws Exception{Thread.sleep(Long.parseLong(args[0]));System.out.println("BHASH_GDB_READY");}
    }
    private ManagedDebugger.ProbeLauncher delayed(long milliseconds,java.util.concurrent.atomic.AtomicReference<Process> child){
        return builder->{
            Path java=Path.of(System.getProperty("java.home"),"bin",NativePlatform.windows()?"java.exe":"java");
            String classes=System.getProperty("java.class.path");
            Process result=new ProcessBuilder(java.toString(),"-cp",classes,SlowProbe.class.getName(),Long.toString(milliseconds))
                .directory(builder.directory()).redirectErrorStream(true).redirectOutput(builder.redirectOutput()).start();
            child.set(result);return result;
        };
    }
    @Test void slowStartupSurvivesThePreviousTwentySecondDeadline()throws Exception{
        Path root=payload();var child=new java.util.concurrent.atomic.AtomicReference<Process>();
        ManagedDebugger.probe(root,ManagedDebugger.startupTimeoutSeconds(null),delayed(21000,child));
        assertFalse(child.get().isAlive());ManagedDebugger.verify(root);
    }
    @Test void hungStartupTimesOutAndCleansUpItsProcessAndLog()throws Exception{
        Path root=payload();var child=new java.util.concurrent.atomic.AtomicReference<Process>();
        IOException error=assertThrows(IOException.class,()->ManagedDebugger.probe(root,1,delayed(30000,child)));
        assertTrue(error.getMessage().contains("Security software"));assertFalse(child.get().isAlive());ManagedDebugger.verify(root);
    }
    @Test void startupTimeoutIsBoundedAndValidated(){
        assertEquals(300,ManagedDebugger.startupTimeoutSeconds(null));assertEquals(600,ManagedDebugger.startupTimeoutSeconds("600"));
        for(String value:List.of("0","14","601","NaN","-1"))assertThrows(IllegalArgumentException.class,()->ManagedDebugger.startupTimeoutSeconds(value));
    }
}
