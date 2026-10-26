// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.frontend.SourceFile;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorIntelligenceTest {
    private SourceIndex source(String name,String text) {return new SourceIndex(new SourceFile(Path.of(name).toAbsolutePath().toString(),text));}
    private Position after(SourceIndex index,String needle) {return index.coordinates.position(index.text.lastIndexOf(needle)+needle.length());}
    private List<String> labels(List<CompletionItem> items) {return items.stream().map(CompletionItem::getLabel).toList();}
    private final String library="""
        संकुल पुस्तक;
        सार्वजनिक वर्ग आधारवर्ग<त> {
            निजी मान गुप्त: पूर्णांक;
            संरक्षित मान रक्षित: पूर्णांक;
            मान वस्तु: त;
            कार्य लो() -> त { लौटाओ वस्तु; }
            साझा कार्य बनाओ() -> पूर्णांक { लौटाओ १; }
        }
        सार्वजनिक वर्ग बच्चा विस्तार आधारवर्ग<पाठ> {
            कार्य उत्तर(अ: पूर्णांक, ब: पाठ) -> पाठ { लौटाओ ब; }
            कार्य उत्तर(अ: पाठ) -> पाठ { लौटाओ अ; }
        }
        सार्वजनिक अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
        सार्वजनिक परिगणना स्थिति { तैयार, पूर्ण }
        सार्वजनिक कार्य दुगुना(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }
        """;
    @Test void qualifiedImportCompletionAndMidWordEditsRemainValid() throws Exception {
        var lib=source("lib.bhash",library);
        var importing=source("importing.bhash","संकुल उपयोग;\nआयात पुस्तक.बिं");
        assertTrue(importing.completions(after(importing,"बिं"),List.of(importing,lib)).stream().anyMatch(i->i.getLabel().equals("पुस्तक.बिंदु")));
        var main=source("main.bhash","संकुल उपयोग;\nकार्य मुख्य() -> शून्य { स्थिर क: बिंदु = नया बिंदु(१,२); लिखो(क.अ); }");
        var item=new EditorModel(main,List.of(main,lib)).autoImports(after(main,"क: बिं")).get(0);
        var edits=new ArrayList<TextEdit>(item.getAdditionalTextEdits());edits.add(item.getTextEdit().getLeft());
        edits.sort(Comparator.comparingInt((TextEdit e)->main.coordinates.offset(e.getRange().getStart())).reversed());String fixed=main.text;
        for(var edit:edits){int start=main.coordinates.offset(edit.getRange().getStart()),end=main.coordinates.offset(edit.getRange().getEnd());fixed=fixed.substring(0,start)+edit.getNewText()+fixed.substring(end);}
        final String valid=fixed;
        assertDoesNotThrow(()->dev.bhash.backend.ManagedEmitter.check(List.of(new SourceFile("main.bhash",valid),new SourceFile("lib.bhash",library))));
    }
    @Test void incompleteDeclarationsDoNotBreakEditorRequests() {
        for(String text:List.of("", "आयात ", "वर्ग क { कार्य लो(", "कार्य क(अ: ", "कार्य मुख्य() -> शून्य { नया सूची<पाठ>(", "अभिलेख क(अ: पूर्णांक,")) {
            var main=source("incomplete.bhash",text);
            assertDoesNotThrow(()->main.completions(main.coordinates.position(text.length()),List.of(main)),text);
            assertDoesNotThrow(()->new EditorModel(main,List.of(main)).signatureHelp(main.coordinates.position(text.length())),text);
        }
    }
    @Test void inheritedGenericMembersRespectVisibilityAndStaticReceiversEvenWhileTyping() {
        var lib=source("lib.bhash",library);
        var main=source("main.bhash","""
            संकुल उपयोग;
            आयात पुस्तक.बच्चा उपनाम बालक;
            कार्य मुख्य() -> शून्य {
                स्थिर क: बालक = नया बालक();
                क.
            }
            """);
        var model=new EditorModel(main,List.of(main,lib));
        var items=model.memberCompletions(after(main,"क."));
        assertTrue(labels(items).containsAll(List.of("वस्तु","लो","उत्तर")),items.toString());
        assertFalse(labels(items).contains("गुप्त"));assertFalse(labels(items).contains("रक्षित"));assertFalse(labels(items).contains("बनाओ"));
        assertTrue(items.stream().anyMatch(i->i.getDetail().equals("लो() -> पाठ")));
        var stat=source("main.bhash",main.text.replace("क.\n","बालक.\n"));
        assertEquals(List.of("बनाओ"),labels(new EditorModel(stat,List.of(stat,lib)).memberCompletions(after(stat,"बालक."))));
    }
    @Test void chainedCallsIndexingRecordsEnumsAndCollectionsResolveTheirDeclaredTypes() {
        var lib=source("lib.bhash",library);
        String prefix="संकुल उपयोग; आयात पुस्तक.बिंदु; आयात पुस्तक.स्थिति; कार्य मुख्य() -> शून्य { स्थिर क: मानचित्र<पाठ,सूची<बिंदु>> = नया मानचित्र<पाठ,सूची<बिंदु>>(); ";
        for(String receiver:List.of("क.लो(\"अ\")[०].","क.मूल्य()[०][०].","(नया बिंदु(१,२)).")) {
            var main=source("main.bhash",prefix+receiver+" }");
            assertEquals(List.of("अ","ब"),labels(new EditorModel(main,List.of(main,lib)).memberCompletions(after(main,receiver))),receiver);
        }
        var main=source("main.bhash",prefix+"स्थिति.तैयार. }");
        assertEquals(List.of("नाम","अंक"),labels(new EditorModel(main,List.of(main,lib)).memberCompletions(after(main,"स्थिति.तैयार."))));
        var unknown=source("unknown.bhash","कार्य मुख्य() -> शून्य { अज्ञात. }");
        assertEquals(List.of(),new EditorModel(unknown,List.of(unknown,lib)).memberCompletions(after(unknown,"अज्ञात.")));
    }
    @Test void signatureHelpShowsOverloadsAndTracksNestedArguments() {
        var lib=source("lib.bhash",library);
        var main=source("main.bhash","संकुल उपयोग; आयात पुस्तक.बच्चा; कार्य मुख्य() -> शून्य { स्थिर क: बच्चा = नया बच्चा(); क.उत्तर(पढ़ो(), ");
        var help=new EditorModel(main,List.of(main,lib)).signatureHelp(main.coordinates.position(main.text.length()));
        assertNotNull(help);assertEquals(2,help.getSignatures().size());assertEquals(1,help.getActiveParameter());
        assertTrue(help.getSignatures().stream().anyMatch(s->s.getLabel().contains("अ: पूर्णांक, ब: पाठ")));
        var nested=source("nested-call.bhash","कार्य दो(अ: सर्ववस्तु, ब: पूर्णांक) -> शून्य {} कार्य मुख्य() -> शून्य { दो(नया मानचित्र<पाठ,पूर्णांक>(), ");
        var nh=new EditorModel(nested,List.of(nested)).signatureHelp(nested.coordinates.position(nested.text.length()));
        assertNotNull(nh);assertEquals(1,nh.getActiveParameter());
        var generic=source("generic.bhash","कार्य पहचान<त>(मानक: त) -> त { लौटाओ मानक; } कार्य मुख्य() -> शून्य { पहचान<पूर्णांक>(");
        var gh=new EditorModel(generic,List.of(generic)).signatureHelp(generic.coordinates.position(generic.text.length()));
        assertNotNull(gh);assertEquals("पहचान(मानक: पूर्णांक) -> पूर्णांक",gh.getSignatures().get(0).getLabel());
    }
    @Test void constructorAndQualifiedFunctionSignaturesAndProtectedAccessAreContextual() {
        var lib=source("lib.bhash",library);
        for(String call:List.of("नया पुस्तक.बिंदु(","पुस्तक.दुगुना(")) {
            var main=source("calls.bhash","कार्य मुख्य() -> शून्य { "+call);
            var help=new EditorModel(main,List.of(main,lib)).signatureHelp(main.coordinates.position(main.text.length()));
            assertNotNull(help,call);assertTrue(help.getSignatures().get(0).getLabel().contains("पूर्णांक"));
        }
        var main=source("protected.bhash","संकुल उपयोग; आयात पुस्तक.बच्चा; वर्ग पोता विस्तार बच्चा { कार्य देखो() -> शून्य { यह. ");
        var labels=labels(new EditorModel(main,List.of(main,lib)).memberCompletions(after(main,"यह.")));
        assertTrue(labels.contains("रक्षित"));assertFalse(labels.contains("गुप्त"));
    }
    @Test void autoImportPreservesCrLfBomAndAvoidsConflictsAndExistingAliases() throws Exception {
        var lib=source("lib.bhash",library);
        var main=source("main.bhash","\uFEFFसंकुल उपयोग; // टिप्पणी\r\nकार्य मुख्य() -> शून्य { स्थिर क: बिं }");
        var items=new EditorModel(main,List.of(main,lib)).autoImports(after(main,"बिं"));assertEquals(1,items.size());
        var edits=new ArrayList<TextEdit>(items.get(0).getAdditionalTextEdits());edits.add(items.get(0).getTextEdit().getLeft());
        edits.sort(Comparator.comparingInt((TextEdit e)->main.coordinates.offset(e.getRange().getStart())).reversed());String text=main.text;
        for(var edit:edits){int start=main.coordinates.offset(edit.getRange().getStart()),end=main.coordinates.offset(edit.getRange().getEnd());text=text.substring(0,start)+edit.getNewText()+text.substring(end);}
        assertTrue(text.startsWith("\uFEFFसंकुल उपयोग; // टिप्पणी\r\nआयात पुस्तक.बिंदु;\r\n"),text);
        var alias=source("alias.bhash","संकुल उपयोग; आयात पुस्तक.बिंदु उपनाम जगह; कार्य मुख्य() -> शून्य { बिं }");
        assertTrue(new EditorModel(alias,List.of(alias,lib)).autoImports(after(alias,"बिं")).isEmpty());
        var conflict=source("conflict.bhash","संकुल उपयोग; वर्ग बिंदु {} कार्य मुख्य() -> शून्य { बिं }");
        assertTrue(new EditorModel(conflict,List.of(conflict,lib)).autoImports(after(conflict,"बिं")).isEmpty());
    }
    @Test void memberDefinitionUsesDeclaringFileAndLocalShadowingWins() {
        var lib=source("lib.bhash",library);
        var main=source("main.bhash","संकुल उपयोग; आयात पुस्तक.बच्चा; कार्य मुख्य() -> शून्य { स्थिर क: बच्चा = नया बच्चा(); क.उत्तर(१,\"अ\"); }");
        var definitions=main.resolve(after(main,"क.उत्"),List.of(main,lib));assertEquals(2,definitions.size());assertEquals(lib.uri,definitions.get(0).uri());
        var shadow=source("shadow.bhash","संकुल उपयोग; आयात पुस्तक.बच्चा; कार्य मुख्य() -> शून्य { स्थिर बच्चा: पूर्णांक = १; बच्चा. }");
        assertTrue(new EditorModel(shadow,List.of(shadow,lib)).memberCompletions(after(shadow,"बच्चा.")).isEmpty());
    }
}
