// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.ffi.*;
import dev.bhash.frontend.SourceFile;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ForeignEditorTest {
    @TempDir Path root;
    @Test void externalDefinitionHasRealReadonlyBhashCoordinatesAndCannotBeRenamed()throws Exception {
        var module=ForeignManifest.read(Path.of("src/test/ffi/counter/bhash.ffi.json"),false);
        var cache=Files.createDirectory(root.resolve("cache"));
        var declarations=ForeignDeclarations.editorSource(cache,module,false);
        var library=new SourceIndex(declarations);
        String text="आयात परीक्षण.जोड़; फलन मुख्य()->शून्य { लिखो(जोड़.जोड़ो(२,३)); }";
        var caller=new SourceIndex(new SourceFile(root.resolve("main.bhash").toString(),text));
        var at=caller.coordinates.position(text.indexOf(".जोड़ो")+3);
        var project=List.of(caller,library);
        var symbols=caller.resolve(at,project);
        assertEquals(1,symbols.size());assertEquals("जोड़ो",symbols.get(0).name());
        assertEquals(library.uri,symbols.get(0).uri());
        assertTrue(symbols.get(0).selection().getStart().getLine()>0);
        assertNull(new Refactorings(project).prepare(caller,at,root));
        assertEquals(2,new Refactorings(project).references(caller,at,true).size());
        assertEquals(declarations,ForeignDeclarations.editorSource(cache,module,false));
        Path file=Path.of(java.net.URI.create(library.uri));file.toFile().setWritable(true);
    }
}
