// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.cli.Main;
import dev.bhash.frontend.ProjectLoader;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectLoaderTest {
    @TempDir Path directory;
    private Path write(String name, String text) throws IOException {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent()); Files.writeString(path,text); return path;
    }
    private Path project(String configuration) throws IOException { return write("bhash.project",configuration); }
    @Test void discoversNestedUtf8FilesInStableOrderAcrossSourceRoots() throws Exception {
        Path manifest = project("name=परीक्षण\nsources=src,shared\n");
        write("src/दूसरा/ख.bhash","वर्ग ख {}");
        write("src/पहला/क.bhash","वर्ग क {}");
        write("shared/मुख्य.bhash","कार्य मुख्य() -> शून्य {}");
        write("src/ignored.txt","not a source file");
        var loaded = ProjectLoader.load(directory);
        assertEquals("परीक्षण",loaded.name());
        assertEquals(3,loaded.sources().size());
        assertEquals(loaded.sources(),ProjectLoader.load(manifest).sources());
        assertEquals(loaded.sourcePaths().stream().map(p -> p.toString().replace('\\','/')).sorted().toList(),
                loaded.sourcePaths().stream().map(p -> p.toString().replace('\\','/')).toList());
    }
    @Test void rejectsBadManifestsMissingSourcesAndOverlappingRoots() throws Exception {
        write("src/nested/क.bhash","कार्य मुख्य() -> शून्य {}");
        for (String config : new String[]{"unknown=value", "sources=missing", "sources=src,", "sources=src,src/nested",
                "sources=../", "sources=src\nname=../bad", "name=first\nname=second"}) {
            var error = assertThrows(IOException.class,()->ProjectLoader.load(project(config)),config);
            assertFalse(error.getMessage().isBlank());
        }
        Files.createDirectory(directory.resolve("empty"));
        assertThrows(IOException.class,()->ProjectLoader.load(project("sources=empty")));
    }
    @Test void defaultsToSrcAndPreservesSingleFileLoadingWithBom() throws Exception {
        project("");
        Path source = write("src/मुख्य.bhash","\uFEFFकार्य मुख्य() -> शून्य {}");
        assertTrue(ProjectLoader.load(directory).sources().get(0).parse().valid());
        assertTrue(ProjectLoader.load(source).sources().get(0).parse().valid());
    }
    @Test void projectCliCompilesUsingManifestNameAndRejectsSourceOverwrite() throws Exception {
        project("name=sample");
        Path source = write("src/मुख्य.bhash","कार्य मुख्य() -> शून्य {}");
        var bytes = new ByteArrayOutputStream(); var sink = new PrintStream(bytes,true,StandardCharsets.UTF_8);
        assertEquals(0,Main.run(new String[]{"check",directory.toString()},sink,sink),bytes.toString(StandardCharsets.UTF_8));
        assertEquals(0,Main.run(new String[]{"compile",directory.toString()},sink,sink),bytes.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(directory.resolve("build/sample.jar")));
        assertEquals(2,Main.run(new String[]{"parse",directory.toString()},sink,sink));
        assertEquals(2,Main.run(new String[]{"compile",directory.toString(),"-o",source.toString()},sink,sink));
        assertEquals("कार्य मुख्य() -> शून्य {}",Files.readString(source));
        assertEquals(2,Main.run(new String[]{"compile",directory.toString(),"--target","clr","-o",directory.resolve("src").toString()},sink,sink));
        assertEquals(2,Main.run(new String[]{"compile",directory.toString(),"-o",directory.resolve("bhash.project").toString()},sink,sink));
    }
    @Test void projectSyntaxCheckFindsErrorsInOtherFolders() throws Exception {
        project(""); write("src/a/क.bhash","कार्य मुख्य() -> शून्य {}"); write("src/b/ख.bhash","वर्ग");
        var bytes = new ByteArrayOutputStream(); var sink = new PrintStream(bytes,true,StandardCharsets.UTF_8);
        assertEquals(1,Main.run(new String[]{"check",directory.toString()},sink,sink));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("ख.bhash"),bytes.toString(StandardCharsets.UTF_8));
    }
    @Test void outputProtectionAlsoCoversResolvedParentSymlinks() throws Exception {
        project(""); Path source = write("src/मुख्य.bhash","कार्य मुख्य() -> शून्य {}");
        Path alias = directory.resolve("alias");
        try { Files.createSymbolicLink(alias,directory.resolve("src")); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic link creation unavailable"); return;
        }
        var loaded = ProjectLoader.load(directory);
        assertThrows(IOException.class,()->loaded.protectOutput(alias.resolve(source.getFileName()),false));
        assertThrows(IOException.class,()->loaded.protectOutput(alias,true));
    }
}
