// SPDX-License-Identifier: Apache-2.0
package dev.bhash;
import dev.bhash.cli.Main;
import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class Utf8ArgumentsTest {
    @TempDir Path root;
    private String encode(String... args){return Base64.getEncoder().encodeToString(new Gson().toJson(args).getBytes(StandardCharsets.UTF_8));}
    @Test void preservesHindiEmojiPathsAndArgumentBoundaries()throws Exception{
        Path source=root.resolve("नमस्ते 😀 space.bhash");Files.writeString(source,"कार्य मुख्य() -> शून्य { लिखो(१); }");
        var bytes=new ByteArrayOutputStream();var output=new PrintStream(bytes,true,StandardCharsets.UTF_8);
        assertEquals(0,Main.run(new String[]{"--utf8-args",encode("check",source.toString())},output,output));
    }
    @Test void rejectsMalformedOversizedAndRecursiveEnvelopes(){
        var bytes=new ByteArrayOutputStream();var output=new PrintStream(bytes);
        for(String encoded:List.of("!",Base64.getEncoder().encodeToString(new byte[]{(byte)255}),encode(),encode("--utf8-args","anything"),"a".repeat(1048577),Base64.getEncoder().encodeToString("[1]".getBytes(StandardCharsets.UTF_8))))assertEquals(2,Main.run(new String[]{"--utf8-args",encoded},output,output));
    }
}
