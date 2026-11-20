// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.cli.ForeignCommands;
import dev.bhash.backend.llvm.NativeBuildInfo;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ForeignCommandsTest {
    @TempDir Path root;
    @Test void prepareIsOfflineReproducibleAndDoesNotOverwrite()throws Exception {
        var output=new ByteArrayOutputStream();var errors=new ByteArrayOutputStream();
        for(String name:new String[]{"one","two"})assertEquals(0,ForeignCommands.run(new String[]{"ffi","prepare","--builtin","compression","-o",name},root,new PrintStream(output),new PrintStream(errors)),errors.toString());
        assertEquals(Files.readString(root.resolve("one/provider.json")),Files.readString(root.resolve("two/provider.json")));
        var metadata=JsonParser.parseString(Files.readString(root.resolve("one/provider.json"))).getAsJsonObject();
        for(var entry:metadata.getAsJsonObject("files").entrySet())assertEquals(entry.getValue().getAsString(),NativeBuildInfo.fileHash(root.resolve("one").resolve(entry.getKey())));
        assertTrue(Files.readString(root.resolve("one/gateway.c")).contains("_invoke"));
        assertEquals(2,ForeignCommands.run(new String[]{"ffi","prepare","--builtin","compression","-o","one"},root,new PrintStream(output),new PrintStream(errors)));
        assertFalse(Files.readString(root.resolve("one/adapter.c")).contains("System.loadLibrary"));
    }
}
