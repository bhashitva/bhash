# विकल्पसमूह का सत्यापन (Tagged-union validation)

यह १९ नवम्बर २०२६ का स्थानीय परीक्षण अभिलेख है। यह GitHub Actions के सफल होने का दावा नहीं है। स्वीकृत अनुबंध [हिन्दी](tagged-unions-pattern-matching-design.md) और [English](tagged-unions-pattern-matching-design.en.md) में तथा चलने योग्य उदाहरण [ट्यूटोरियल](../tutorial/tagged-unions/README.md) में हैं।

## कार्यान्वयन (Implementation)

एक साझा pass बन्द परिवारों और उनके immutable case payload को सामान्य checked classes में बदलता है। Specialization के समय प्रकारयुक्त pattern plan और bounded constructor-matrix coverage जाँच होती है। Expression match के private block values async frame lowering के साथ काम करते हैं; synchronous blocks अन्त में ordinary immediately invoked closures बनते हैं। Statement matching पहले एक शाखा चुनता है, फिर exhaustive selection चलाता है। Selector एक बार और guards source order में चलते हैं। इससे return/throw/loop control, cleanup और precise roots के मौजूदा contracts पुनः उपयोग होते हैं।

Native cases की अलग स्थिर GC layout है। Structural-value membership का नया private marker `-10` है और internal runtime ABI **18** है। Descriptor/hash/provenance, runtime link guard और read-only debugger उसी ABI को पहचानते हैं। C/JNI के सीधे union exports `BH3320` देते हैं; existing supported wrapper boundaries का उपयोग करें। LLVM toolchain का version नहीं बदला गया।

## परीक्षण सामग्री (Test artifacts)

- [TaggedUnionTest.java](../src/test/java/dev/bhash/TaggedUnionTest.java): निर्माण, generic/nominal प्रकार, nested records, finite boolean products, guards, nullable coverage, unreachable arms, typed Result/Unit, expected-type inference, contextual identifiers, async, cancellation, escaping captures, cleanup, deep/shared graphs और तीनों tutorials। JVM और .NET execution आउटपुट जाँचते हैं।
- [NativeBackendTest.java](../src/test/java/dev/bhash/NativeBackendTest.java): `taggedUnionsAndAwaitedMatchingSurviveForcedGc` और `nativeTaggedUnionTutorialsSurviveForcedGc` में `BHASH_GC_STRESS=1`, संग्रहण होने और shutdown पर live heap शून्य होने की जाँच। मूल `nativeRecordFixturesMatchJvmClrAndForcedGc` से shared equality/hash engine की JVM/.NET/native regressions भी जाँची गईं।
- [NativeEmbeddingTest.java](../src/test/java/dev/bhash/NativeEmbeddingTest.java): सीधे union export की रोक। [NativeReleaseTest.java](../src/test/java/dev/bhash/NativeReleaseTest.java): ABI/provenance/link-guard संगति।
- [EditorIntelligenceTest.java](../src/test/java/dev/bhash/lsp/EditorIntelligenceTest.java), [RefactoringsTest.java](../src/test/java/dev/bhash/lsp/RefactoringsTest.java): factories, generic payload binding, references/rename और missing-case placeholders।
- [Browser smoke test](../scripts/browser_smoke_test.cjs): उसी runtime revision पर `results` और `async-result` को isolated browser worker में चलाता है।
- [Real Jupyter smoke test](../editors/jupyter/test_kernel.py): ५७ keywords/contextual keywords की सफल execution coverage, declarations/functions के अगले cell में उपयोग, Result और अपूर्ण match diagnostics।

## स्थानीय परिणाम और सीमाएँ (Local results and limits)

Windows x64 पर JVM, .NET 10 और LLVM 18 native execution, forced-GC और existing record regressions सफल रहे। Focused editor, frontend/Unicode, generic/null-safety और native release checks भी सफल रहे। Result/Unit का generated Hindi reference १२७ प्रकार, ७३५ declared public members और २५४ usage excerpts की सूची में है। Book links और example fixtures जाँचे गए हैं।

Kernel worker को उसके वास्तविक framed protocol से चलाकर भाषा corpus, सभी ५७ keywords और union/Result cross-cell declaration/function cases जाँचे गए। पूर्ण Jupyter client इस session में Windows loopback socket initialization (`asyncio`/`socketpair`) पर रुकता है, Bhash execution से पहले। इसलिए real-client Linux/Windows/macOS CI अभी आवश्यक है। मौजूदा kernel केवल scalar bindings को cells के बीच रखता है; object bindings cell-local रहते हैं। यह milestone उस storage सीमा को नहीं बदलता।

Browser CLR generation/type-checking सफल है, लेकिन इस session में NuGet network और WebAssembly MSBuild task-host launch उपलब्ध नहीं हैं। इसलिए packaged standalone तथा prebuilt-host browser runtime checks CI में पूरे करना आवश्यक है; browser execution को स्थानीय रूप से सफल नहीं बताया गया है।

इस session का Git `github.com` resolve नहीं कर पाता। Linux native और अन्य remote CI results अभी इस स्थानीय अभिलेख में शामिल नहीं हैं। Existing Linux/Windows native workflows और full Jupyter matrix बदलावों पर चलेंगे। Apple Silicon native tests existing weekly/manual workflow में ही हैं; per-PR macOS native trigger नहीं जोड़ा गया है।

## GitHub Actions परिणाम (Remote validation)

[PR #141](https://github.com/bhashitva/bhash/pull/141) के अंतिम revision `3fc143c` पर सभी ५० लागू checks सफल रहे। इसमें JVM/.NET, Linux/Windows native forced-GC और वितरण, standalone/prebuilt browser host तथा Linux/Windows/macOS पर पूर्ण वास्तविक-client Jupyter परीक्षण शामिल हैं। तीन release-only jobs अपेक्षानुसार skip हुए। ऊपर दी गई स्थानीय host सीमाओं की भरपाई इन remote checks से हुई।

## फिर चलाना (Reproduction)

### समीक्षा के सीमांत मामले (Review regressions)

अतिरिक्त regression tests strict base-class selector पर subtype-only शाखा को अपूर्ण मानते हैं, recursive payload के wildcard columns को छोड़कर शेष finite columns जाँचते हैं, और assignment तथा लागू overload से match का अपेक्षित प्रकार लेते हैं। बाइट/लघुपूर्णांक literal सीमा, २५६वीं generic family के private case layout, तथा generic pattern और समान नाम वाले असंबंधित package का मामला भी जाँचा गया है। `reviewedEdgeCasesExecuteOnJvm`, CLR fixture और `reviewedMatchEdgeCasesSurviveForcedGc` वही executable edge cases साझा करते हैं।

Editor tests nested match की ठीक diagnostic position, आंशिक case coverage के साथ अछूते cases के placeholders, generic pattern type-argument navigation, समान owner/case नामों की अलग पहचान, inferred binding hover और rename scope जाँचते हैं। Quick Fix document version से जुड़ा है। Generated reference में पुराने topic anchors सुरक्षित हैं। Family या type parameter के समान case नाम वास्तव में वैध हैं; regression test उन्हें स्वीकार करता है।

```sh
mvn -Dtest=TaggedUnionTest,NativeReleaseTest,NativeEmbeddingTest,RefactoringsTest,EditorIntelligenceTest test
mvn '-Dtest=NativeBackendTest#taggedUnionsAndAwaitedMatchingSurviveForcedGc+nativeTaggedUnionTutorialsSurviveForcedGc+nativeRecordFixturesMatchJvmClrAndForcedGc+reviewedMatchEdgeCasesSurviveForcedGc' test
python scripts/build_library_reference.py --check
python scripts/check_book.py
mvn -DskipTests package
python editors/jupyter/test_kernel.py target/bhash-compiler-0.1.0-SNAPSHOT.jar --notebook editors/jupyter/पहली-नोटबुक.ipynb
node scripts/browser_smoke_test.cjs
```

Jupyter के लिए उसके Python dependencies, browser के लिए .NET wasm-tools और Playwright, तथा native checks के लिए उपयुक्त LLVM 18 toolchain तैयार करें। CI में native tests को चुपचाप skip होने से रोकने के लिए `BHASH_REQUIRE_NATIVE=true` रखें।
