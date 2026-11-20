// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import com.google.gson.*;
import dev.bhash.backend.llvm.*;
import dev.bhash.frontend.*;
import dev.bhash.ffi.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class ForeignNativeTest {
    @TempDir Path directory;
    @Test void compressionResultConstructionSurvivesForcedGcInARealBhashExecutable() throws Exception {
        String archive=System.getProperty("bhash.ffi.archive","");
        assumeTrue(!archive.isEmpty(),"Build the pinned FFI provider and set bhash.ffi.archive.");
        NativeToolchain.require(directory);
        Files.copy(Path.of(archive),directory.resolve("provider.a"));
        Files.writeString(directory.resolve("LICENSE"),resource("zlib-LICENSE.txt"));
        JsonObject json=JsonParser.parseString(resource("compression.ffi.json")).getAsJsonObject();
        var artifact=new JsonObject();artifact.addProperty("target",ForeignArtifacts.host());artifact.addProperty("path","provider.a");
        artifact.addProperty("sha256",NativeBuildInfo.fileHash(directory.resolve("provider.a")));artifact.addProperty("sourceSha256","02dd159783677a749c04adbdd02b55b8a2bcc7e054f51b76b549199a916ad584");artifact.addProperty("license","LICENSE");
        json.getAsJsonArray("artifacts").add(artifact);
        var module=ForeignManifest.parse(json.toString(),true).at(directory);
        String program=Files.readString(Path.of("src/test/ffi/compression.bhash"));
        var sources=List.of(new SourceFile("main.bhash",program,true));
        try(var catalog=ForeignCatalog.withArtifacts(module)){
        Path executable=directory.resolve("compression"+NativePlatform.executableSuffix());
        new NativeCompiler().compile(sources,executable);
        Path output=directory.resolve("stdout.txt"),error=directory.resolve("stderr.txt");
        var builder=new ProcessBuilder(executable.toString()).redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().put("BHASH_GC_STRESS","1");builder.environment().put("BHASH_GC_STATS","1");
        Process process=builder.start();
        if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();fail("Native FFI program timed out");}
        assertEquals(0,process.exitValue(),Files.readString(error));
        assertEquals("भारत😀 नमस्ते\nनिर्गतसीमा\nदूषितडेटा\n",Files.readString(output).replace("\r\n","\n"));
        assertTrue(Files.readString(error).contains("live=0"),Files.readString(error));
        var legacy=List.of(new SourceFile("legacy.bhash",Files.readString(Path.of("src/test/ffi/legacy-null.bhash"))));
        new NativeCompiler().compile(legacy,executable);
        process=builder.start();if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();fail("Legacy FFI program timed out");}
        assertEquals(0,process.exitValue(),Files.readString(error));
        assertEquals("अवैधतर्क\n",Files.readString(output).replace("\r\n","\n"));
        assertTrue(Files.readString(error).contains("live=0"),Files.readString(error));
        }
    }
    private static String resource(String name) throws Exception {
        try(var input=ForeignNativeTest.class.getResourceAsStream("/dev/bhash/ffi/"+name)){
            assertNotNull(input,name);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
