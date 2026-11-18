// SPDX-License-Identifier: Apache-2.0
package dev.bhash;

import dev.bhash.backend.*;
import dev.bhash.frontend.Frontend;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaferEverydayTest {
    private void check(String source)throws Exception {
        var parsed=Frontend.parse("everyday.bhash",source);assertTrue(parsed.valid(),parsed.diagnostics().toString());
        ManagedEmitter.emit("everyday.bhash",parsed.tree(),Target.JVM);
        ManagedEmitter.emit("everyday.bhash",parsed.tree(),Target.CLR);
    }
    private void reject(String body,String message) {
        var e=assertThrows(CompilationException.class,()->check(body));assertTrue(e.getMessage().contains(message),e.getMessage());
    }
    private void constructor(String body)throws Exception {check("वर्ग क { स्थिर अ: पूर्णांक; निर्माता(च: तार्किक) {"+body+"} } फलन मुख्य()->शून्य {}");}
    private void rejectConstructor(String body,String message) {reject("वर्ग क { स्थिर अ: पूर्णांक; निर्माता(च: तार्किक) {"+body+"} } फलन मुख्य()->शून्य {}",message);}
    @Test void constructorAssignmentsFollowAllSuccessfulPaths()throws Exception {
        constructor("यदि (च) { यह.अ=१; } अन्यथा { अ=२; }");
        constructor("यदि (च) { फेंको नया अपवाद(); } अ=१;");
        constructor("प्रयास { अ=१; } अंततः { लिखो(अ); }");
        constructor("प्रयास { यदि (च) { लौटाओ; } } अंततः { अ=१; }");
        constructor("चयन (च) { विकल्प सत्य: अ=१; विराम; शेष: अ=२; }");
    }
    @Test void constructorAssignmentsRejectMissingDuplicateAndEarlyReads() {
        rejectConstructor("यदि (च) { अ=१; }","every successful");
        rejectConstructor("अ=१; अ=२;","already be initialized");
        rejectConstructor("यदि (च) { अ=१; } अ=२;","already be initialized");
        rejectConstructor("लिखो(अ); अ=१;","before initialization");
        rejectConstructor("अ=अ+१;","before initialization");
        rejectConstructor("यदि (च) { लौटाओ; } अ=१;","every successful");
        rejectConstructor("जबतक (च) { अ=१; }","already be initialized");
        rejectConstructor("प्रयास { अ=१; } पकड़ो (ग: अपवाद) { अ=२; }","already be initialized");
        rejectConstructor("प्रयास { अ=१; } अंततः { अ=२; }","already be initialized");
    }
    @Test void immutablePermissionDoesNotEscapeConstructor() {
        reject("वर्ग क { स्थिर अ: पूर्णांक; निर्माता(){अ=१;} फलन बदलो()->शून्य {अ=२;} } फलन मुख्य()->शून्य {}","immutable");
        reject("वर्ग क { स्थिर अ: पूर्णांक=०; निर्माता(){अ=१;} } फलन मुख्य()->शून्य {}","immutable");
        reject("वर्ग क { स्थिर अ: पूर्णांक; निर्माता(द: क){द.अ=१;} } फलन मुख्य()->शून्य {}","immutable");
        reject("वर्ग क { स्थिर अ: पूर्णांक; } फलन मुख्य()->शून्य {}","every successful");
    }
    @Test void inferredBindingsStayStaticallyTyped()throws Exception {
        check("फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {मान क=पहचान(१); क=२; स्थिर स=नया सूची<पाठ>{\"क\"}; स्थिर व=स[०]; लिखो(व,क);}");
        reject("फलन मुख्य()->शून्य {मान क=१; क=\"क\";}","Expected");
        reject("फलन मुख्य()->शून्य {स्थिर क=रिक्त;}","Cannot infer");
        reject("फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य { पहचान(रिक्त); }","Cannot infer");
        reject("फलन पहचान<त>(अ: त,ब: त)->त {लौटाओ अ;} फलन मुख्य()->शून्य { पहचान(१,\"क\"); }","Cannot infer");
    }
    @Test void constraintsAreCheckedAtTypeAndCallSites()throws Exception {
        check("अनुबंध नामित {फलन नाम()->पाठ;} वर्ग क पालन नामित {फलन नाम()->पाठ {लौटाओ \"क\";}} फलन लो<त: नामित>(व: त)->पाठ {लौटाओ व.नाम();} फलन मुख्य()->शून्य {लिखो(लो(नया क()));}");
        reject("अनुबंध नामित {} फलन लो<त: नामित>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {लो(१);}","constraint not satisfied");
        reject("अनुबंध नामित {} वर्ग पात्र<त: नामित> {} फलन मुख्य()->शून्य {स्थिर क=नया पात्र<पूर्णांक>();}","constraint not satisfied");
    }
    @Test void genericMembersAndInterfacesShareVirtualSlots()throws Exception {
        check("अनुबंध प्रदाता {फलन लो<त>(व: त)->त;} वर्ग क पालन प्रदाता {फलन लो<त>(व: त)->त {लौटाओ व;}} फलन मुख्य()->शून्य {स्थिर क: प्रदाता=नया क(); लिखो(क.लो(१),क.लो<पाठ>(\"क\"));}");
        check("वर्ग क<अ> {फलन लो<ब>(व: ब)->ब {लौटाओ व;}} फलन मुख्य()->शून्य {स्थिर क=नया क<पाठ>(); लिखो(क.लो(१));}");
        check("वर्ग मूल {फलन लो<अ>(व: अ)->अ {लौटाओ व;}} वर्ग बच्चा विस्तार मूल {अधिलेखन फलन लो<ब>(व: ब)->ब {लौटाओ व;}} फलन मुख्य()->शून्य {स्थिर क: मूल=नया बच्चा(); लिखो(क.लो(१));}");
    }
    @Test void notebookScalarInferenceUsesPriorBindingsAndSourceOrder()throws Exception {
        var types=ManagedEmitter.notebookBindingTypes("वर्ग भंडार {साझा मान पिछला: पूर्णांक;} फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {लिखो(पिछला); स्थिर अगला=पहचान(पिछला+१); {स्थिर अंदर=\"क\";} स्थिर पाठ्य=\"ख\";}","भंडार");
        assertEquals(java.util.Map.of("अगला","पूर्णांक","पाठ्य","पाठ"),types);
    }
    @Test void genericContractsAreValidatedEvenWithoutCalls() {
        reject("अनुबंध क {फलन लो<त>(व: त)->त;} वर्ग ख पालन क {} फलन मुख्य()->शून्य {}","generic interface implementation");
        reject("वर्ग क {फलन लो<त>(व: त)->त {लौटाओ व;} फलन लो<ख>(व: ख)->ख {लौटाओ व;}} फलन मुख्य()->शून्य {}","Duplicate generic");
        reject("वर्ग क {फलन लो<त>(व: त)->त {लौटाओ व;}} वर्ग ख विस्तार क {फलन लो<त>(व: त)->त {लौटाओ व;}} फलन मुख्य()->शून्य {}","अधिलेखन");
        reject("अनुबंध क {} वर्ग पात्र<त: त> {} फलन मुख्य()->शून्य {}","Cyclic");
        reject("वर्ग पात्र<त: पूर्णांक> {} फलन मुख्य()->शून्य {}","class or interface");
        reject("परिगणना रंग {लाल} वर्ग पात्र<त: रंग> {} फलन मुख्य()->शून्य {}","class or interface");
        reject("वर्ग पात्र<त: अनुपस्थित> {} फलन मुख्य()->शून्य {}","Unknown type");
    }
    @Test void inferenceHandlesLoopSourcesEnumsBytesAndStaticMethods()throws Exception {
        check("परिगणना रंग {लाल} वर्ग सहायक {साझा फलन पहचान<त>(व: त)->त {लौटाओ व;}} फलन पहचान<त>(व: त)->त {लौटाओ व;} फलन मुख्य()->शून्य {स्थिर स=नया सूची<पूर्णांक>{१}; प्रत्येक (पहचान(स) में स: पूर्णांक) {लिखो(स);} स्थिर ब=नया बाइटबफ़र(१); स्थिर अ=सहायक.पहचान(ब[०]); स्थिर र=रंग.लाल; लिखो(अ,र.नाम);}");
        check("फलन मुख्य()->शून्य {स्थिर स=नया सूची<पूर्णांक>{१}; स्थिर हटा=स.हटाओ(०); स्थिर पढ़ा=पूर्णांकपाठ(\"१२\"); स्थिर जोड़: पूर्णांक=हटा+पढ़ा; स्थिर म=नया मानचित्र<पाठ,पूर्णांक>{\"क\":१}; स्थिर खाली=म.खाली(); स्थिर मानक=म.मूल्य(); स्थिर ब=नया बाइटबफ़र(८); स्थिर सरणी=ब.सरणी(); स्थिर असल: बाइट[]=सरणी; लिखो(जोड़,खाली,मानक[०],असल[०]);}");
        check("वर्ग सहायक {साझा फलन लो<त>(व: सर्ववस्तु)->पाठ {लौटाओ \"साझा\";} फलन लो<त>(व: त)->त {लौटाओ व;}} फलन मुख्य()->शून्य {स्थिर अ=सहायक.लो<पूर्णांक>(१); स्थिर ब=नया सहायक().लो(१); लिखो(अ+\"!\",ब+१);}");
    }
    @Test void programDiagnosticsKeepTheUserSourceForEmptyInputs() {
        reject("","everyday.bhash:");
    }
}
