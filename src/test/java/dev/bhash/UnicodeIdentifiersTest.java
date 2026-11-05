package dev.bhash;

import dev.bhash.frontend.*;
import dev.bhash.backend.*;
import dev.bhash.parser.BhashLexer;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeIdentifiersTest {
    @Test void hostNormalizationMatchesPinnedProfilePairs() throws Exception {
        var metadata = com.google.gson.JsonParser.parseString(Files.readString(Path.of("docs/identifier-profile.json"))).getAsJsonObject();
        var points = new ArrayList<Integer>();
        for (var range : metadata.getAsJsonArray("continue")) {
            var ends = range.getAsJsonArray();
            for (int point = ends.get(0).getAsInt(); point <= ends.get(1).getAsInt(); point++) points.add(point);
        }
        var hash = MessageDigest.getInstance("SHA-256");
        for (int first : points) for (int second : points) {
            hash.update(Identifiers.key(Character.toString(first) + Character.toString(second)).getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
        }
        assertEquals(metadata.get("nfcPairsSha256").getAsString(), HexFormat.of().formatHex(hash.digest()));
    }
    @Test void profileCountsAndEveryAcceptedCharacterAreRecognized() {
        int starts = 0, parts = 0;
        for (int point = 0; point <= 0x10FFFF; point++) {
            if (IdentifierProfile.start(point)) {
                starts++;
                assertIdentifier(Character.toString(point) + "क");
            }
            if (IdentifierProfile.part(point)) {
                parts++;
                assertIdentifier("क" + Character.toString(point));
            }
        }
        assertEquals(102, starts);
        assertEquals(205, parts);
        assertTrue(IdentifierProfile.part(0x953));
        assertTrue(IdentifierProfile.part(0x954));
    }

    private void assertIdentifier(String spelling) {
        var errors = new ArrayList<String>();
        var lexer = new BhashLexer(CharStreams.fromString(spelling));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object token, int line, int col, String message, RecognitionException error) {
                errors.add(message);
            }
        });
        var token = lexer.nextToken();
        assertEquals(BhashLexer.IDENTIFIER, token.getType(), spelling);
        assertEquals(spelling, token.getText());
        assertEquals(Token.EOF, lexer.nextToken().getType());
        assertEquals(List.of(), errors, spelling);
    }

    @Test void keywordVariantsAreReservedAndOffsetsKeepOriginalSource() {
        for (int type = 1; type <= BhashLexer.VOCABULARY.getMaxTokenType(); type++) {
            String literal = BhashLexer.VOCABULARY.getLiteralName(type);
            if (literal == null || !literal.contains("्")) continue;
            String word = literal.substring(1, literal.length() - 1);
            String shaped = word.replace("्", "्\u200D");
            var lexer = new BhashLexer(CharStreams.fromString(shaped));
            Token token = lexer.nextToken();
            assertEquals(type, token.getType(), word);
            assertEquals(shaped, token.getText());
            assertEquals(shaped.codePointCount(0, shaped.length()) - 1, token.getStopIndex());
        }
        assertEquals(Identifiers.key("क्षेत्र"), Identifiers.key("क्\u200Cषेत्र"));
        assertEquals(Identifiers.key("क़दम"), Identifiers.key("क़दम"));
        assertIdentifier("यदिसंख्या");
    }

    @Test void invalidControlsAndStartsHaveLocatedHindiDiagnostics() {
        for (String spelling : List.of("\u200Dक", "क\u200Dख", "क्\u200D", "क्\u200D\u200Cष", "़क", "aक", "क\u202Eख")) {
            var parsed = Frontend.parse("unicode.bhash", "फलन मुख्य()->शून्य { मान " + spelling + ":पूर्णांक=१; }");
            assertFalse(parsed.valid(), spelling);
            assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.message().matches("(?s).*BH100[234].*U\\+[0-9A-F]+.*")), parsed.diagnostics().toString());
        }
    }

    @Test void equivalentDeclarationsConflictWhileBlockShadowingRemainsValid() throws Exception {
        var duplicate = List.of(new SourceFile("same.bhash", "फलन मुख्य()->शून्य { मान क्षेत्र:पूर्णांक=१; मान क्\u200Dषेत्र:पूर्णांक=२; }"));
        assertTrue(assertThrows(CompilationException.class, () -> ManagedEmitter.check(duplicate)).getMessage().contains("Duplicate"));
        ManagedEmitter.check(List.of(new SourceFile("scope.bhash", "फलन मुख्य()->शून्य { मान क्षेत्र:पूर्णांक=१; { मान क्\u200Cषेत्र:पूर्णांक=२; लिखो(क्षेत्र); } लिखो(क्षेत्र); }")));
    }
}
