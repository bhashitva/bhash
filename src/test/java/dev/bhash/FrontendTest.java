package dev.bhash;

import dev.bhash.cli.Main;
import dev.bhash.frontend.Frontend;
import dev.bhash.frontend.Identifiers;
import dev.bhash.parser.BhashLexer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontendTest {
    @Test void parsesClassMembersConstructorsInheritanceAndPostfixChains() {
        var result = Frontend.parse("objects.bhash", """
                वर्ग मूल { मान संख्या: पूर्णांक; }
                वर्ग बच्चा विस्तार मूल {
                    निजी मान अगला: मूल = रिक्त;
                    निर्माता(संख्या: पूर्णांक) : आधार() { यह.संख्या = संख्या; }
                    सार्वजनिक अधिलेखन कार्य स्वयं() -> मूल { लौटाओ यह; }
                }
                कार्य मुख्य() -> शून्य { नया बच्चा(१).स्वयं().संख्या = २; }
                """);
        assertTrue(result.valid(), () -> result.diagnostics().toString());
        assertEquals(2, result.tree().classDecl().size());
        assertEquals(1, result.tree().functionDecl().size());
    }

    @Test void objectKeywordsAreReservedButLongerNamesRemainIdentifiers() {
        var lexer = new BhashLexer(CharStreams.fromString("वर्ग नया यह आधार रिक्त वर्गीकरण"));
        for (int expected : new int[]{BhashLexer.CLASS, BhashLexer.NEW, BhashLexer.THIS,
                BhashLexer.BASE, BhashLexer.NULL, BhashLexer.IDENTIFIER})
            assertEquals(expected, lexer.nextToken().getType());
    }
    private Frontend.Result parseBody(String body) {
        return Frontend.parse("test.bhash", "कार्य मुख्य() -> शून्य { " + body + " }");
    }
    @Test void examplesParse() throws Exception {
        for (var name : new String[]{"hello", "factorial"}) {
            var result = Frontend.parse(name, Files.readString(Path.of("examples/" + name + ".bhash")));
            assertTrue(result.valid(), () -> result.diagnostics().toString());
        }
    }
    @Test void acceptsDevanagariConjunctsNuktaAndBothDigitScripts() {
        assertTrue(parseBody("मान क्षेत्र: पूर्णांक = १२३; मान क़दम: पूर्णांक = 123;").valid());
    }
    @Test void acceptsTypesCommentsAndEscapes() {
        assertTrue(parseBody("/* टिप्पणी */ मान मूल्य: दशमलव = १२.५०; "
                + "स्थिर सही: तार्किक = सत्य && !असत्य; "
                + "मान संदेश: पाठ = \"नमस्ते\\n\\u0967\"; // टिप्पणी\n लिखो(संदेश);").valid());
    }
    @Test void acceptsNestedControlFlowAndUnaryOperators() {
        assertTrue(parseBody("जबतक (सत्य) { यदि (!असत्य) { जारी; } "
                + "अन्यथा यदि (१ < २) { विराम; } अन्यथा { लौटाओ; } } "
                + "मान संख्या: पूर्णांक = -१ + +२;").valid());
    }
    @Test void rejectsLatinIdentifiers() { assertFalse(parseBody("मान count: पूर्णांक = 1;").valid()); }
    @Test void rejectsIsolatedMarksAndDanda() {
        assertFalse(parseBody("मान िक: पूर्णांक = १;").valid());
        assertFalse(parseBody("मान क।ख: पूर्णांक = १;").valid());
    }
    @Test void rejectsMixedDigitLiteral() { assertFalse(parseBody("लिखो(1२);").valid()); }
    @Test void rejectsMissingSemicolonAndTrailingGarbage() {
        assertFalse(parseBody("लिखो(१)").valid());
        assertFalse(Frontend.parse("bad", "कार्य मुख्य() -> शून्य {} @").valid());
    }
    @Test void rejectsInvalidStringAndUnterminatedComment() {
        assertFalse(parseBody("लिखो(\"bad\\q\");").valid());
        assertFalse(parseBody("लिखो(\"bad\nline\");").valid());
        assertFalse(parseBody("/* never closed").valid());
    }
    @Test void rejectsInvisibleJoinersAndBidiInCode() {
        assertFalse(parseBody("मान क\u200Dख: पूर्णांक = १;").valid());
        assertFalse(parseBody("\u202E लिखो(१);").valid());
    }
    @Test void keywordPrefixesRemainIdentifiers() {
        var lexer = new BhashLexer(CharStreams.fromString("यदिसंख्या"));
        assertEquals(BhashLexer.IDENTIFIER, lexer.nextToken().getType());
    }
    @Test void multiplicationBindsMoreTightlyThanAddition() {
        var expression = parseBody("लिखो(१ + २ * ३);").tree().functionDecl(0)
                .block().statement(0).arguments().expression(0).logicalOr().logicalAnd(0)
                .equality(0).comparison(0).additive(0);
        assertEquals(2, expression.multiplicative().size());
        assertEquals(2, expression.multiplicative(1).unary().size());
    }
    @Test void normalizationUnifiesEquivalentIdentifierSpellings() {
        assertEquals(Identifiers.key("क़दम"), Identifiers.key("क़दम"));
    }
    @Test void diagnosticsCarryLocations() {
        var result = Frontend.parse("broken.bhash", "कार्य मुख्य() -> शून्य {\n @\n}");
        assertFalse(result.valid());
        assertEquals("broken.bhash", result.diagnostics().get(0).source());
        assertEquals(2, result.diagnostics().get(0).line());
        assertEquals(2, result.diagnostics().get(0).column());
    }
    @Test void cliReturnsNonzeroForSyntaxAndUsageFailures() throws Exception {
        var sink = new PrintStream(new ByteArrayOutputStream());
        assertEquals(2, Main.run(new String[]{"compile"}, sink, sink));
        Path bad = Files.createTempFile("bhash-bad-", ".bhash");
        try {
            Files.writeString(bad, "कार्य");
            assertEquals(1, Main.run(new String[]{"check", bad.toString()}, sink, sink));
            Files.writeString(bad, "\uFEFFकार्य मुख्य() -> शून्य {}");
            assertEquals(0, Main.run(new String[]{"check", bad.toString()}, sink, sink));
        } finally { Files.deleteIfExists(bad); }
    }
}
