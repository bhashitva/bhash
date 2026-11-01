// SPDX-License-Identifier: Apache-2.0
package dev.bhash.backend.wasm;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrebuiltBrowserHostTest {
    @TempDir Path directory;
    Path fixture() throws Exception {
        Path root=directory.resolve("host");Files.createDirectories(root.resolve("web/_framework"));
        Files.writeString(root.resolve("web/_framework/dotnet.js"),"runtime");
        Files.writeString(root.resolve(PrebuiltBrowserHost.HOST_MARKER),"Bhash prebuilt browser host v1\n");
        Path csc=directory.resolve("csc.dll");Files.writeString(csc,"compiler");
        var properties=new Properties();properties.setProperty("runtime",PrebuiltBrowserHost.runtimeFingerprint());
        properties.setProperty("csc",csc.toString());properties.setProperty("cscSha256",sha(Files.readAllBytes(csc)));
        try(var writer=Files.newBufferedWriter(root.resolve("host.properties"))){properties.store(writer,"");}
        seal(root);return root;
    }
    void seal(Path root) throws Exception {Files.writeString(root.resolve(".bhash-wasm-output"),WasmCompiler.manifest(root));}
    String sha(byte[] value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    @Test void changedRuntimeAssetsAndSdkAreRejected() throws Exception {
        Path root=fixture();String id=PrebuiltBrowserHost.open(root).id();assertEquals(64,id.length());
        Files.writeString(root.resolve("web/_framework/dotnet.js"),"changed");
        assertTrue(assertThrows(IOException.class,()->PrebuiltBrowserHost.open(root)).getMessage().contains("modified"));
        seal(root);assertNotEquals(id,PrebuiltBrowserHost.open(root).id());
        Files.writeString(directory.resolve("csc.dll"),"SDK update");
        assertTrue(assertThrows(IOException.class,()->PrebuiltBrowserHost.open(root)).getMessage().contains("SDK compiler"));
    }
    @Test void incompatibleRuntimeAndMissingFilesFailClosed() throws Exception {
        Path root=fixture();
        String properties=Files.readString(root.resolve("host.properties"));
        Files.writeString(root.resolve("host.properties"),properties.replaceFirst("runtime=.*","runtime=old"));seal(root);
        assertTrue(assertThrows(IOException.class,()->PrebuiltBrowserHost.open(root)).getMessage().contains("does not match"));
        Files.delete(root.resolve("host.properties"));assertThrows(IOException.class,()->PrebuiltBrowserHost.open(root));
    }
    @Test void unmanagedPreparationNeverReplacesFilesOrStartsSdk() throws Exception {
        Files.writeString(directory.resolve("keep.txt"),"keep");
        assertThrows(IOException.class,()->PrebuiltBrowserHost.prepare(directory));
        assertEquals("keep",Files.readString(directory.resolve("keep.txt")));
    }
    @Test void programOutputCannotOverwriteOrModifyThePreparedHost() throws Exception {
        Path root=fixture();var host=PrebuiltBrowserHost.open(root);String id=host.id();
        for(Path output:List.of(root,root.resolve("program"),directory))
            assertTrue(assertThrows(IOException.class,()->host.compile("unused",output)).getMessage().contains("overlap"));
        assertThrows(IOException.class,()->new WasmCompiler().compile("hello.bhash","कार्य मुख्य() -> शून्य {}",root));
        assertEquals(id,PrebuiltBrowserHost.open(root).id());
    }
    @org.junit.jupiter.api.RepeatedTest(5) void interruptStopsCompilerAndItsDescendantsBeforeReturning() throws Exception {
        Path processDirectory=directory.resolve("process");Files.createDirectories(processDirectory);
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classes=Path.of(BrowserCompilerProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var preserved=new java.util.concurrent.atomic.AtomicBoolean();
        Thread runner=new Thread(()->{
            try{PrebuiltBrowserHost.run(List.of(javaExecutable,"-cp",classes,BrowserCompilerProcessFixture.class.getName(),processDirectory.toString(),"parent"),processDirectory);}
            catch(Throwable error){failure.set(error);preserved.set(Thread.currentThread().isInterrupted());}
        });
        runner.start();
        try {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(processDirectory.resolve("child.pid"))&&runner.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue(Files.exists(processDirectory.resolve("child.pid")),String.valueOf(failure.get()));
            runner.interrupt();runner.join(10000);assertFalse(runner.isAlive());
            assertInstanceOf(IOException.class,failure.get());assertTrue(preserved.get());
            for(String file:List.of("parent.pid","child.pid")) {
                long pid=Long.parseLong(Files.readString(processDirectory.resolve(file)));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),file+" survived cancellation");
            }
            Files.delete(processDirectory.resolve("build.log"));
        } finally {
            runner.interrupt();
            for(String file:List.of("parent.pid","child.pid"))if(Files.exists(processDirectory.resolve(file)))
                ProcessHandle.of(Long.parseLong(Files.readString(processDirectory.resolve(file)))).ifPresent(ProcessHandle::destroyForcibly);
            runner.join(10000);
        }
    }
}
