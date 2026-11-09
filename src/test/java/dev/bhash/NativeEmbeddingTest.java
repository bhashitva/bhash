// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.CompilationException;
import dev.bhash.backend.ManagedEmitter;
import dev.bhash.backend.NativeExports;
import dev.bhash.frontend.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeEmbeddingTest {
    @TempDir Path work;

    private List<NativeExports.Request> exports(String text) throws Exception {
        return NativeExports.read(Files.writeString(work.resolve("bhash.exports"), text));
    }

    @Test void exportsUseCheckedSignaturesAndNeedNoMain() throws Exception {
        var program = List.of(new SourceFile("lib.bhash", """
            वर्ग पात्र { निर्माता() {} }
            फलन बनाओ() -> पात्र { लौटाओ नया पात्र(); }
            फलन बदलो(मूल: पात्र, मानक: पूर्णांक) -> पूर्णांक { लौटाओ मानक; }
            फलन बदलो(मूल: पात्र, मानक: तार्किक) -> तार्किक { लौटाओ मानक; }
            """));
        var library = ManagedEmitter.libraryCore(program, exports("""
            bhash_make = बनाओ() -> पात्र
            bhash_get = बदलो(पात्र, पूर्णांक) -> पूर्णांक
            """));
        assertEquals(-1, library.program().entry());
        assertEquals(2, library.exports().size());
        var make = library.exports().get(0);
        var get = library.exports().get(1);
        assertTrue(make.resultHandleType() >= 16);
        assertEquals(List.of(make.resultHandleType(), 0), get.handleTypes());
        assertThrows(CompilationException.class,
            () -> ManagedEmitter.libraryCore(program, exports("bhash_make = बनाओ() -> पाठ")));
    }

    @Test void rejectsReservedSymbolsDuplicateSymbolsAndUnsupportedTypes() throws Exception {
        assertThrows(CompilationException.class, () -> exports("bhash_runtime_start = आरंभ() -> शून्य"));
        assertThrows(CompilationException.class, () -> exports("""
            bhash_example = आरंभ() -> शून्य
            bhash_example = आरंभ() -> शून्य
            """));
        var program = List.of(new SourceFile("lib.bhash", "फलन दो() -> दशमलव { लौटाओ २.०; }"));
        assertThrows(CompilationException.class,
            () -> ManagedEmitter.libraryCore(program, exports("bhash_two = दो() -> दशमलव")));
    }

    @Test void rejectsConsoleOperationsInLibrary() throws Exception {
        var program = List.of(new SourceFile("lib.bhash", "फलन आरंभ() -> शून्य { लिखो(\"नमस्ते\"); }"));
        assertThrows(CompilationException.class,
            () -> ManagedEmitter.libraryCore(program, exports("bhash_start = आरंभ() -> शून्य")));
    }
}
