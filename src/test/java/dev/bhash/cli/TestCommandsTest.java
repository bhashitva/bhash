// SPDX-License-Identifier: Apache-2.0
package dev.bhash.cli;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class TestCommandsTest {
    @TempDir Path directory;
    private record Result(int status,String out,String err) {}
    private Result run(String...options)throws Exception {
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        int status=Main.run(options,new PrintStream(out,true,StandardCharsets.UTF_8),new PrintStream(err,true,StandardCharsets.UTF_8));
        return new Result(status,out.toString(StandardCharsets.UTF_8),err.toString(StandardCharsets.UTF_8));
    }
    @Test void reportsAssertionAndCompilationFailuresAndContinues()throws Exception {
        String pass="आयात मानक.परीक्षण; कार्य मुख्य() -> शून्य { परीक्षण.बराबर(१,१); }";
        Files.writeString(directory.resolve("a.test.bhash"),pass);
        Files.writeString(directory.resolve("b.test.bhash"),"कार्य मुख्य() -> शून्य { अज्ञात(); }");
        Files.writeString(directory.resolve("c.test.bhash"),"आयात मानक.परीक्षण; कार्य मुख्य() -> शून्य { परीक्षण.बराबर(१,२); }");
        Files.writeString(directory.resolve("d.test.bhash"),pass);
        Files.writeString(directory.resolve("ignored.bhash"),"not a test");
        var result=run("test",directory.toString());assertEquals(1,result.status);
        assertTrue(result.out.contains("Tests: 2 passed, 2 failed, 4 total."),result.out);
        assertTrue(result.err.contains("b.test.bhash"));assertTrue(result.err.contains("c.test.bhash"));
    }
    @Test void timesOutAndRunsNextCaseWithFreshProcessState()throws Exception {
        Files.writeString(directory.resolve("a.test.bhash"),"कार्य मुख्य() -> शून्य { जबतक (सत्य) {} }");
        String source="आयात मानक.परीक्षण; वर्ग क { साझा मान ग: पूर्णांक = ०; सार्वजनिक साझा कार्य अगला() -> पूर्णांक { ग = ग + १; लौटाओ ग; } } कार्य मुख्य() -> शून्य { परीक्षण.बराबर(१,क.अगला()); }";
        Files.writeString(directory.resolve("b.test.bhash"),source);Files.writeString(directory.resolve("c.test.bhash"),source);
        var result=run("test",directory.toString(),"--timeout","5");assertEquals(1,result.status);
        assertTrue(result.err.contains("exceeded 5 seconds"),result.err);
        assertTrue(result.out.contains("Tests: 2 passed, 1 failed, 3 total."),result.out);
    }
    @Test void rejectsEmptySuitesAndUnsupportedTargets()throws Exception {
        assertEquals(2,run("test",directory.toString()).status);
        assertEquals(2,run("test",directory.toString(),"--target","wasm").status);
        assertEquals(2,run("test",directory.toString(),"--timeout","0").status);
    }
    @Test void discoversACompleteTestProjectWithSharedSources()throws Exception {
        Path project=Files.createDirectory(directory.resolve("library.test"));Path src=Files.createDirectory(project.resolve("src"));
        Files.writeString(project.resolve("bhash.project"),"name=foundation-test\nsources=src\n");
        Files.writeString(src.resolve("helper.bhash"),"संकुल पुस्तक; सार्वजनिक वर्ग हिसाब { सार्वजनिक साझा कार्य दुगुना(क: पूर्णांक) -> पूर्णांक { लौटाओ क * २; } }");
        Files.writeString(src.resolve("main.bhash"),"आयात पुस्तक.हिसाब; आयात मानक.परीक्षण; कार्य मुख्य() -> शून्य { परीक्षण.बराबर(६,हिसाब.दुगुना(३)); }");
        var result=run("test",directory.toString());assertEquals(0,result.status,result.err);assertTrue(result.out.contains("1 passed, 0 failed, 1 total."));
    }
}
