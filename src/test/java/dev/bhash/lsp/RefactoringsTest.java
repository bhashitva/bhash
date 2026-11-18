// SPDX-License-Identifier: Apache-2.0
package dev.bhash.lsp;

import dev.bhash.frontend.SourceFile;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RefactoringsTest {
    @TempDir Path root;
    private SourceIndex source(String name,String text){return new SourceIndex(new SourceFile(root.resolve(name).toString(),text));}
    private Position at(SourceIndex i,String needle){return i.coordinates.position(i.text.indexOf(needle));}
    @Test void localsRespectShadowingClosuresCommentsAndUnicodeOffsets()throws Exception {
        var i=source("local.bhash","\uFEFFफलन मुख्य()->शून्य {\r\n लिखो(\"😀 संख्या\"); मान संख्या=१; // संख्या\r\n { मान संख्या=२; लिखो(संख्या); } स्थिर फल=फलन()->पूर्णांक {लौटाओ संख्या;}; लिखो(संख्या,फल()); }");
        var r=new Refactorings(List.of(i));var p=at(i,"संख्या=१");
        assertNotNull(r.prepare(i,i.coordinates.position(i.text.indexOf("संख्या=१")+"संख्या".length()),root));
        assertEquals(3,r.references(i,p,true).size());assertEquals(2,r.references(i,p,false).size());
        var edits=r.rename(i,p,"गणना",root);var changed=Refactorings.apply(i,edits.get(i.uri));
        assertTrue(changed.contains("\"😀 संख्या\""));assertTrue(changed.contains("// संख्या"));assertTrue(changed.contains("मान संख्या=२; लिखो(संख्या)"));
        assertTrue(changed.contains("लौटाओ गणना"));
    }
    @Test void renameRejectsCaptureEvenWhenRenamedCodeWouldCompile() {
        var i=source("capture.bhash","फलन मुख्य()->शून्य { मान अ=१; { मान ब=२; लिखो(अ,ब); } }");
        var e=assertThrows(IllegalArgumentException.class,()->new Refactorings(List.of(i)).rename(i,at(i,"अ=१"),"ब",root));
        assertTrue(e.getMessage().contains("अर्थ बदलता"),e.getMessage());
    }
    @Test void typeRenamePreservesImportAliasesAndPackageQualifiers()throws Exception {
        var a=source("a.bhash","संकुल विद्यालय; सार्वजनिक वर्ग छात्र {} ");
        var b=source("b.bhash","आयात विद्यालय.छात्र उपनाम विद्यार्थी; फलन मुख्य()->शून्य {स्थिर क: विद्यार्थी=नया विद्यार्थी();}");
        var r=new Refactorings(List.of(a,b));var edits=r.rename(a,at(a,"छात्र"),"शिक्षार्थी",root);
        assertTrue(Refactorings.apply(b,edits.get(b.uri)).contains("आयात विद्यालय.शिक्षार्थी उपनाम विद्यार्थी"));
        assertEquals(1,edits.get(b.uri).size());
        var alias=r.rename(b,at(b,"विद्यार्थी;"),"पाठक",root);
        assertEquals(3,alias.get(b.uri).size());assertFalse(alias.containsKey(a.uri));
    }
    @Test void overrideAndInterfaceNamesChangeTogetherButUnrelatedTypesDoNot()throws Exception {
        var i=source("members.bhash","अनुबंध नामित {फलन नाम()->पाठ;} वर्ग क पालन नामित {फलन नाम()->पाठ {लौटाओ \"क\";}} वर्ग ख विस्तार क {अधिलेखन फलन नाम()->पाठ {लौटाओ \"ख\";}} वर्ग अन्य {फलन नाम()->पाठ {लौटाओ \"अन्य\";}} फलन मुख्य()->शून्य {स्थिर व: नामित=नया ख(); लिखो(व.नाम()); स्थिर अ=नया अन्य(); लिखो(अ.नाम());}");
        var r=new Refactorings(List.of(i));var edits=r.rename(i,at(i,"नाम()->पाठ"),"विवरण",root);
        assertEquals(4,edits.get(i.uri).size());assertTrue(Refactorings.apply(i,edits.get(i.uri)).contains("लिखो(अ.नाम())"));
    }
    @Test void dependenciesKeywordsAndCanonicalCollisionsAreRejected()throws Exception {
        var i=source("bad.bhash","फलन मुख्य()->शून्य {मान अ=१; मान क़=२; लिखो(अ,क़);}");
        var r=new Refactorings(List.of(i));
        assertThrows(IllegalArgumentException.class,()->r.rename(i,at(i,"अ=१"),"यदि",root));
        assertThrows(Exception.class,()->r.rename(i,at(i,"अ=१"),"क़",root));
        assertNull(r.prepare(i,at(i,"अ=१"),root.resolve("another-project")));
    }
    @Test void genericParameterReferencesAreScoped()throws Exception {
        var i=source("parameters.bhash","वर्ग पात्र<त> {मान व: त; निर्माता(व: त){यह.व=व;}} फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {स्थिर अ=नया पात्र<पूर्णांक>(१);}");
        var r=new Refactorings(List.of(i));var edits=r.rename(i,at(i,"त>"),"मूल्यप्रकार",root);
        assertEquals(3,edits.get(i.uri).size());assertTrue(Refactorings.apply(i,edits.get(i.uri)).contains("पहचान<त>"));
    }
    @Test void inferredGenericResultsAndGenericMembersNavigate()throws Exception {
        var i=source("inference.bhash","वर्ग क {मान मूल्य: पूर्णांक; फलन लो<त>(व: त)->त {लौटाओ व;}} फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {स्थिर अ=पहचान(नया क()); स्थिर ब=अ.लो<क>(अ); लिखो(ब.मूल्य,अ.लो(अ).मूल्य);}");
        var r=new Refactorings(List.of(i));var edits=r.rename(i,at(i,"मूल्य:"),"अंक",root);
        assertEquals(3,edits.get(i.uri).size());
        var method=r.rename(i,at(i,"लो<त>"),"लौटादो",root);assertEquals(3,method.get(i.uri).size());
    }
    @Test void languageServerReturnsVersionedEditsForUnsavedBuffers()throws Exception {
        var file=root.resolve("open.bhash");java.nio.file.Files.writeString(file,"फलन मुख्य()->शून्य {}");
        String text="फलन मुख्य()->शून्य {मान संख्या=१; लिखो(संख्या);}";
        try(var server=new BhashLanguageServer()) {
            var init=new InitializeParams();init.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(),"test")));
            var caps=server.initialize(init).get(30,java.util.concurrent.TimeUnit.SECONDS).getCapabilities();assertTrue(caps.getReferencesProvider().getLeft());assertTrue(caps.getRenameProvider().getRight().getPrepareProvider());
            String uri=file.toUri().toString();server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri,"bhash",7,text)));
            var params=new RenameParams(new TextDocumentIdentifier(uri),new TextCoordinates(text).position(text.indexOf("संख्या")),"गिनती");
            var edit=server.rename(params).get(120,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,edit.getDocumentChanges().size());
            var change=edit.getDocumentChanges().get(0).getLeft();assertEquals(7,change.getTextDocument().getVersion());assertEquals(2,change.getEdits().size());
            assertFalse(java.nio.file.Files.readString(file).contains("गिनती"));
        }
    }
}
