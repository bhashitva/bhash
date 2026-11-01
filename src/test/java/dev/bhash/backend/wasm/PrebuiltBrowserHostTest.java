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
        Path csc=directory.resolve("csc.dll");Files.writeString(csc,"compiler");
        var bytes=new ByteArrayOutputStream();
        for(String resource:List.of("/dev/bhash/backend/clr/BhashRuntime.cs.txt","/dev/bhash/backend/clr/BhashStandard.cs.txt","/dev/bhash/backend/wasm/PrebuiltBrowserHost.cs.txt"))
            try(var stream=PrebuiltBrowserHost.class.getResourceAsStream(resource)){stream.transferTo(bytes);}
        var properties=new Properties();properties.setProperty("runtime",sha(bytes.toByteArray()));
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
}
