package dev.bhash;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.Target;
import dev.bhash.backend.clr.ClrCompiler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("dotnetAvailable")
class ClrCompilerTest extends ManagedCompilerTest {
    ClrCompilerTest() { super(Target.CLR); }

    static boolean dotnetAvailable() {
        // CI must fail rather than silently skip if SDK setup is broken.
        if ("true".equalsIgnoreCase(System.getenv("BHASH_REQUIRE_CLR"))) return true;
        try {
            var process = new ProcessBuilder(ClrCompiler.executable(), "--list-sdks").redirectErrorStream(true).start();
            try {
                return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
                        && new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).matches("(?s).*\\b10\\.\\d.*");
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } catch (Exception absent) { return false; }
    }

    @Test void outputDirectoryRebuildsAndPreservesPriorOutputOnFailure() throws Exception {
        Path output = directory.resolve("rebuild");
        var compiler = new ClrCompiler();
        String valid = "कार्य मुख्य() -> शून्य { लिखो(५); }";
        compiler.compile("test.bhash", valid, output);
        byte[] original = Files.readAllBytes(output.resolve("BhashProgram.dll"));
        assertThrows(CompilationException.class, () -> compiler.compile("bad.bhash", "कार्य", output));
        assertArrayEquals(original, Files.readAllBytes(output.resolve("BhashProgram.dll")));
        compiler.compile("test.bhash", valid.replace("५", "६"), output);
        assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(output.resolve("BhashProgram.dll"))));
    }

    @Test void refusesUnmanagedOrUnrelatedOutputFiles() throws Exception {
        var compiler = new ClrCompiler();
        String source = "कार्य मुख्य() -> शून्य {}";
        Path output = directory.resolve("unrelated");
        Files.createDirectories(output);
        Files.writeString(output.resolve("notes.txt"), "keep me");
        assertThrows(java.io.IOException.class, () -> compiler.compile("test.bhash", source, output));
        assertEquals("keep me", Files.readString(output.resolve("notes.txt")));
        Path managed = directory.resolve("managed");
        compiler.compile("test.bhash", source, managed);
        Files.writeString(managed.resolve("notes.txt"), "also keep me");
        assertThrows(java.io.IOException.class, () -> compiler.compile("test.bhash", source, managed));
        assertEquals("also keep me", Files.readString(managed.resolve("notes.txt")));
    }
}
