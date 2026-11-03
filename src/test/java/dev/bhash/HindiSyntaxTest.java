// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.frontend.Frontend;
import dev.bhash.parser.BhashLexer;
import java.nio.file.*;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HindiSyntaxTest {
    @Test void functionKeywordIsReservedAndOldSpellingIsAnIdentifier() {
        var lexer = new BhashLexer(CharStreams.fromString("फलन फलनमान कार्य"));
        assertEquals(BhashLexer.FUNCTION, lexer.nextToken().getType());
        assertEquals(BhashLexer.IDENTIFIER, lexer.nextToken().getType());
        assertEquals(BhashLexer.IDENTIFIER, lexer.nextToken().getType());
        assertTrue(Frontend.parse("valid.bhash", "फलन मुख्य() -> शून्य { स्थिर कार्य: पूर्णांक = १; लिखो(कार्य); }").valid());
        assertFalse(Frontend.parse("old.bhash", "कार्य मुख्य() -> शून्य {}").valid());
        assertFalse(Frontend.parse("reserved.bhash", "फलन मुख्य() -> शून्य { मान फलन: पूर्णांक = १; }").valid());
    }

    @Test void onlyCollectionFirstIterationIsAccepted() {
        for (String header : new String[]{
                "अंक में अ: पूर्णांक", "तालिका में कुंजी: पाठ, मूल्य: सूची<पूर्णांक>",
                "लो<पूर्णांक>(नया सूची<पूर्णांक>{१,२}) में अ: पूर्णांक",
                "(नया पूर्णांक[][]{नया पूर्णांक[]{१}})[०] में अ: पूर्णांक",
                "अंक /* collection */ में /* binding */ अ: पूर्णांक"}) {
            var result = Frontend.parse("loop.bhash", "फलन मुख्य() -> शून्य { प्रत्येक (" + header + ") {} }");
            assertTrue(result.valid(), () -> header + ": " + result.diagnostics());
        }
        for (String header : new String[]{"अ: पूर्णांक में अंक", "क: पाठ, व: पूर्णांक में तालिका",
                "अंक में", "में अ: पूर्णांक", "अंक में अ: पूर्णांक, ब: पूर्णांक, क: पूर्णांक"})
            assertFalse(Frontend.parse("invalid.bhash", "फलन मुख्य() -> शून्य { प्रत्येक (" + header + ") {} }").valid(), header);
    }

    @Test void allPublishedBhashSourcesParseWithTheCurrentSyntax() throws Exception {
        for (String folder : new String[]{"examples", "lessons", "tutorial", "benchmarks/workloads", "src/test/resources"})
            try (var paths = Files.walk(Path.of(folder))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".bhash")).toList()) {
                    var result = Frontend.parse(path.toString(), Files.readString(path));
                    assertTrue(result.valid(), () -> path + ": " + result.diagnostics());
                }
            }
    }
}
