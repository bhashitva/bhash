// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EditorAnalysisTest {
    @TempDir Path root;
    @Test void libraryAnalysisDoesNotRelaxExecutableRequirements() throws Exception {
        var source=new SourceFile("library.bhash","सार्वजनिक अभिलेख जोड़ा(अ: पूर्णांक, ब: पूर्णांक);");
        assertDoesNotThrow(()->ManagedEmitter.check(List.of(source)));
        assertThrows(CompilationException.class,()->ManagedEmitter.emit(List.of(source),Target.JVM));
        assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("invalid.bhash","कार्य मुख्य(अ: पूर्णांक) -> शून्य {}"))));
        assertThrows(CompilationException.class,()->ManagedEmitter.check(List.of(new SourceFile("library.bhash","कार्य गलत() -> पूर्णांक { लौटाओ सत्य; }"))));
    }
    @Test void projectOverlaysIncludeNewFilesAndUnsavedManifestWithoutWritingDisk() throws Exception {
        Files.createDirectories(root.resolve("src"));Files.createDirectories(root.resolve("other"));
        Path manifest=root.resolve("bhash.project"),original=root.resolve("src/a.bhash"),newFile=root.resolve("other/new.bhash");
        Files.writeString(manifest,"name=test\nsources=src\n");Files.writeString(original,"वर्ग मूल {}");
        var project=ProjectLoader.load(manifest,Map.of(manifest,"name=test\nsources=other\n",newFile,"वर्ग नया {}"));
        assertEquals(List.of(newFile),project.sourcePaths());assertFalse(Files.exists(newFile));
        assertEquals("name=test\nsources=src\n",Files.readString(manifest));
    }
    @Test void coordinatesHandleCrLfBomAndSupplementaryCharacters() {
        var text=new TextCoordinates("\uFEFFलिखो(\"😀\"); अज्ञात\r\nक");
        assertEquals(13,text.diagnostic(1,12).getStart().getCharacter());
        assertEquals(new org.eclipse.lsp4j.Position(1,1),text.position(text.offset(new org.eclipse.lsp4j.Position(1,1))));
    }
}
