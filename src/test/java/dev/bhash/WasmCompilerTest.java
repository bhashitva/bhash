package dev.bhash;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.wasm.WasmCompiler;
import dev.bhash.cli.Main;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WasmCompilerTest {
    @TempDir Path directory;
    @Test void validatesLanguageBeforeStartingDotnet() {
        var compiler = new WasmCompiler();
        assertThrows(CompilationException.class, () -> compiler.compile("bad.bhash", "कार्य", directory.resolve("web")));
        var error = assertThrows(CompilationException.class, () -> compiler.compile("bad.bhash",
                "कार्य मुख्य() -> शून्य { लिखो(अज्ञात); }", directory.resolve("web")));
        assertFalse(error.getMessage().isBlank());
        assertFalse(Files.exists(directory.resolve("web")));
    }
    @Test void refusesUnmanagedOutputAndSourceDirectories() throws Exception {
        var compiler = new WasmCompiler();
        Files.writeString(directory.resolve("notes.txt"), "keep");
        assertThrows(IOException.class, () -> compiler.compile("test.bhash", "कार्य मुख्य() -> शून्य {}", directory));
        assertEquals("keep", Files.readString(directory.resolve("notes.txt")));
        Path source = directory.resolve("input.bhash");
        Files.writeString(source, "कार्य मुख्य() -> शून्य {}");
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"compile", source.toString(), "--target", "wasm-browser", "-o", directory.toString()},
                new PrintStream(OutputStream.nullOutputStream()), new PrintStream(errors)));
        assertTrue(errors.toString().contains("Output must not overwrite"));
    }
    @Test void browserRunCommandExplainsHowToLaunch() {
        var errors = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[]{"run", "test.bhash", "--target", "wasm-browser"}, System.out, new PrintStream(errors)));
        assertTrue(errors.toString().contains("serve the output over HTTP"));
    }
}
