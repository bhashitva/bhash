// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectResolverTest {
    private List<SourceFile> files(String... sources) {
        var result = new ArrayList<SourceFile>();
        for (int i=0;i<sources.length;i++) result.add(new SourceFile("file"+i+".bhash",sources[i]));
        return result;
    }
    private String emit(List<SourceFile> sources) throws Exception { return ManagedEmitter.emit(sources,Target.JVM); }
    private String reject(String expected, String... sources) {
        var error = assertThrows(CompilationException.class,()->emit(files(sources)));
        assertTrue(error.getMessage().contains(expected),error.getMessage());
        return error.getMessage();
    }
    @Test void samePackageTypesAndMutualReferencesDoNotDependOnFileOrder() throws Exception {
        var sources = files("संकुल ऐप; वर्ग पहला { मान अगला: दूसरा; } कार्य मुख्य() -> शून्य { नया पहला(); }",
                "संकुल ऐप; वर्ग दूसरा { मान अगला: पहला; }");
        for (int i=0;i<2;i++) {
            assertNotNull(emit(sources));
            sources = new ArrayList<>(sources); Collections.reverse(sources);
        }
    }
    @Test void importsAreFileScopedAndDoNotLeakFromNeighboringFiles() {
        reject("Unknown type: वस्तु",
                "संकुल पुस्तक; सार्वजनिक वर्ग वस्तु {}",
                "संकुल ऐप; आयात पुस्तक.वस्तु; वर्ग पहला { मान वस्तुका: वस्तु; }",
                "संकुल ऐप; वर्ग दूसरा { मान वस्तुका: वस्तु; } कार्य मुख्य() -> शून्य {}");
    }
    @Test void qualifiedTypesConstructorsStaticCallsAndGenericFunctionsResolve() throws Exception {
        assertNotNull(emit(files("""
                संकुल पुस्तक;
                सार्वजनिक वर्ग पात्र<त> { साझा कार्य संख्या() -> पूर्णांक { लौटाओ ७; } }
                सार्वजनिक कार्य पहचान<त>(मानक: त) -> त { लौटाओ मानक; }
                """, """
                संकुल ऐप;
                कार्य मुख्य() -> शून्य {
                    स्थिर वस्तु: पुस्तक.पात्र<पूर्णांक> = नया पुस्तक.पात्र<पूर्णांक>();
                    लिखो(पुस्तक.पात्र<पूर्णांक>.संख्या(), पुस्तक.पहचान<पूर्णांक>(१));
                }
                """)));
    }
    @Test void duplicateShortNamesCanBeDistinguishedWithAliasesAndQualification() throws Exception {
        assertNotNull(emit(files("संकुल पहला; सार्वजनिक वर्ग वस्तु {}",
                "संकुल दूसरा; सार्वजनिक वर्ग वस्तु {}", """
                संकुल ऐप;
                आयात पहला.वस्तु उपनाम पहली;
                आयात दूसरा.वस्तु उपनाम दूसरी;
                कार्य मुख्य() -> शून्य {
                    स्थिर एक: पहली = नया पहली();
                    स्थिर दो: दूसरी = नया दूसरा.वस्तु();
                }
                """)));
        reject("ambiguous", "संकुल पहला; सार्वजनिक वर्ग वस्तु {}", "संकुल दूसरा; सार्वजनिक वर्ग वस्तु {}",
                "संकुल ऐप; आयात पहला.वस्तु; आयात दूसरा.वस्तु; कार्य मुख्य() -> शून्य {}");
    }
    @Test void duplicateImportsUnknownImportsAndConflictingAliasesFailEvenWhenUnused() {
        reject("Duplicate or ambiguous import", "संकुल पहला; सार्वजनिक वर्ग वस्तु {}",
                "संकुल ऐप; आयात पहला.वस्तु; आयात पहला.वस्तु; कार्य मुख्य() -> शून्य {}");
        reject("Unknown import", "आयात अनजान.वस्तु; कार्य मुख्य() -> शून्य {}");
        reject("conflicts", "संकुल पहला; सार्वजनिक वर्ग वस्तु {}",
                "संकुल ऐप; आयात पहला.वस्तु; वर्ग वस्तु {} कार्य मुख्य() -> शून्य {}");
        reject("Reserved import", "संकुल पहला; सार्वजनिक वर्ग वस्तु {}",
                "आयात पहला.वस्तु उपनाम पढ़ो; कार्य मुख्य() -> शून्य {}");
    }
    @Test void packageVisibilityAppliesToImportsQualifiedTypesAndQualifiedCalls() throws Exception {
        reject("not public", "संकुल पहला; वर्ग वस्तु {}", "संकुल ऐप; आयात पहला.वस्तु; कार्य मुख्य() -> शून्य {}");
        reject("not public", "संकुल पहला; वर्ग वस्तु {}", "कार्य मुख्य() -> शून्य { नया पहला.वस्तु(); }");
        reject("not public", "संकुल पहला; कार्य रहस्य() -> शून्य {}", "कार्य मुख्य() -> शून्य { पहला.रहस्य(); }");
        assertNotNull(emit(files("संकुल ऐप; वर्ग वस्तु {} कार्य रहस्य() -> शून्य {}",
                "संकुल ऐप; कार्य मुख्य() -> शून्य { नया वस्तु(); रहस्य(); }")));
    }
    @Test void duplicateFullyQualifiedTypesAndNormalizedSpellingsFail() {
        reject("Duplicate class", "संकुल ऐप; वर्ग वस्तु {}", "संकुल ऐप; वर्ग वस्तु {} कार्य मुख्य() -> शून्य {}");
        reject("Duplicate class", "संकुल क़दम; वर्ग वस्तु {}", "संकुल क़दम; वर्ग वस्तु {} कार्य मुख्य() -> शून्य {}");
    }
    @Test void overloadGroupsSpanFilesButMustHaveUniformVisibility() throws Exception {
        assertNotNull(emit(files("संकुल पुस्तक; सार्वजनिक कार्य लो(क: पूर्णांक) -> पूर्णांक { लौटाओ क; }",
                "संकुल पुस्तक; सार्वजनिक कार्य लो(क: पाठ) -> पाठ { लौटाओ क; }",
                "आयात पुस्तक.लो; कार्य मुख्य() -> शून्य { लिखो(लो(१), लो(\"अ\")); }")));
        reject("identical package visibility", "संकुल पुस्तक; सार्वजनिक कार्य लो(क: पूर्णांक) -> पूर्णांक { लौटाओ क; }",
                "संकुल पुस्तक; कार्य लो(क: पाठ) -> पाठ { लौटाओ क; } कार्य मुख्य() -> शून्य {}");
        reject("Duplicate function signature", "संकुल ऐप; कार्य लो(क: पूर्णांक) -> पूर्णांक { लौटाओ क; }",
                "संकुल ऐप; कार्य लो(ख: पूर्णांक) -> पूर्णांक { लौटाओ ख; } कार्य मुख्य() -> शून्य {}");
    }
    @Test void localsParametersAndInheritedMembersTakePrecedenceOverImportedNames() throws Exception {
        assertNotNull(emit(files("संकुल पुस्तक; सार्वजनिक वर्ग वस्तु {} सार्वजनिक कार्य लो() -> पूर्णांक { लौटाओ १; }", """
                संकुल ऐप;
                आयात पुस्तक.वस्तु;
                आयात पुस्तक.लो;
                वर्ग मूल { मान वस्तु: पूर्णांक; कार्य लो() -> पूर्णांक { लौटाओ वस्तु; } }
                वर्ग बच्चा विस्तार मूल { कार्य जांच() -> पूर्णांक { लौटाओ लो(); } }
                कार्य जांच(वस्तु: पूर्णांक) -> पूर्णांक { लौटाओ वस्तु; }
                कार्य मुख्य() -> शून्य {
                    लिखो(लो());
                    { स्थिर वस्तु: पूर्णांक = ९; लिखो(वस्तु); }
                    स्थिर वस्तु: वस्तु = नया वस्तु();
                    नया बच्चा().जांच();
                }
                """)));
    }
    @Test void methodNamesDoNotShadowTypeReceiversAndTypeParametersDoNotShadowFunctionCalls() throws Exception {
        assertNotNull(emit(files("""
                संकुल पुस्तक;
                सार्वजनिक वर्ग वस्तु { साझा कार्य लो() -> पूर्णांक { लौटाओ १; } }
                """, """
                संकुल ऐप;
                आयात पुस्तक.वस्तु;
                वर्ग उपभोक्ता {
                    कार्य वस्तु() -> पूर्णांक { लौटाओ २; }
                    कार्य जांच() -> पूर्णांक { लौटाओ वस्तु.लो(); }
                }
                कार्य त() -> पूर्णांक { लौटाओ ४; }
                कार्य पहचान<त>(मूल्य: त) -> पूर्णांक { लौटाओ त(); }
                कार्य मुख्य() -> शून्य { लिखो(नया उपभोक्ता().जांच(), पहचान<पूर्णांक>(०)); }
                """)));
    }
    @Test void packageTypesRemainDistinctInAssignmentsAndOverloads() {
        String message = reject("Expected पहला.वस्तु, found दूसरा.वस्तु",
                "संकुल पहला; सार्वजनिक वर्ग वस्तु {}", "संकुल दूसरा; सार्वजनिक वर्ग वस्तु {}",
                "कार्य मुख्य() -> शून्य { स्थिर क: पहला.वस्तु = नया दूसरा.वस्तु(); }");
        assertTrue(message.startsWith("file2.bhash:1:"),message);
    }
    @Test void semanticAndSpecializedBodyErrorsPreserveDefiningFileLineAndColumn() {
        String message = reject("Expected", """
                संकुल पुस्तक;
                सार्वजनिक वर्ग पात्र<त> {
                    कार्य गलत() -> पूर्णांक {
                        लौटाओ "गलत";
                    }
                }
                """, "आयात पुस्तक.पात्र; कार्य मुख्य() -> शून्य { नया पात्र<पूर्णांक>(); }");
        assertTrue(message.startsWith("file0.bhash:4:9:"),message);
        message = reject("Unknown type", "कार्य मुख्य() -> शून्य {}", "संकुल पुस्तक;\nवर्ग क { मान ख: अज्ञात; }");
        assertTrue(message.startsWith("file1.bhash:2:"),message);
    }
    @Test void inheritanceCyclesAndCrossFileAccessViolationsRemainErrors() {
        reject("cycle", "संकुल ऐप; वर्ग क विस्तार ख {} कार्य मुख्य() -> शून्य {}", "संकुल ऐप; वर्ग ख विस्तार क {}");
        reject("not accessible", "संकुल पुस्तक; सार्वजनिक वर्ग वस्तु { निजी मान रहस्य: पूर्णांक; }",
                "आयात पुस्तक.वस्तु; कार्य मुख्य() -> शून्य { लिखो(नया वस्तु().रहस्य); }");
    }
    @Test void syntaxErrorsAreCollectedFromAllFilesAndEntrypointMustBeUnique() {
        String message = reject("file0.bhash:", "कार्य", "वर्ग");
        assertTrue(message.contains("file1.bhash:"),message);
        reject("मुख्य", "संकुल पहला; कार्य मुख्य() -> शून्य {}", "संकुल दूसरा; कार्य मुख्य() -> शून्य {}");
    }
    @Test void unnamedPackageDeclarationsDoNotLeakIntoNamedPackages() {
        reject("Undefined function", "कार्य रहस्य() -> पूर्णांक { लौटाओ १; }",
                "संकुल ऐप; कार्य मुख्य() -> शून्य { लिखो(रहस्य()); }");
        reject("Undefined variable", "वर्ग वस्तु { साझा मान संख्या: पूर्णांक; }",
                "संकुल ऐप; कार्य मुख्य() -> शून्य { लिखो(वस्तु.संख्या); }");
    }
    @Test void headersAreOrderedAndPublicDoesNotApplyToTopLevelVariables() {
        for (String source : List.of("आयात क.ख; संकुल क;", "वर्ग क {} आयात क.ख;", "सार्वजनिक मान क: पूर्णांक = १;"))
            assertFalse(Frontend.parse("bad.bhash",source).valid());
    }
    @Test void internalNamesCannotCollideWithUserIdentifiers() throws Exception {
        assertNotNull(emit(files("संकुल पुस्तक; सार्वजनिक वर्ग वस्तु {}", """
                आयात पुस्तक.वस्तु;
                वर्ग संकुलित_0 {}
                कार्य मुख्य() -> शून्य {}
                """)));
    }
}
