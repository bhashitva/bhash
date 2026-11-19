# रिक्त-सुरक्षा का प्रस्ताव (Null-safety design)

स्थिति: **स्वीकृत डिज़ाइन; opt-in कार्यान्वयन स्रोत में उपलब्ध है।** Legacy default
बना रहता है। प्रकाशित compiler, extension और kernel में उपलब्धता उनके अगले
package release पर निर्भर है।

## उद्देश्य (Goals)

किसी वस्तु की अनुपस्थिति को प्रकार में स्पष्ट करें और संभावित रिक्त संदर्भ को उपयोग
से पहले जाँचना आवश्यक बनाएँ। नियम JVM, CLR, browser और native पर समान हों। GC तथा
नियत संसाधन-सफ़ाई अपनी मौजूदा भूमिकाएँ बनाए रखें। यह ownership या borrow checker
का प्रस्ताव नहीं है।

## प्रकार और व्याकरण (Types and syntax)

अनुशंसा: strict mode में `छात्र`, `सूची<छात्र>` और `फलन()->छात्र` के मान रिक्त नहीं
हो सकते। `छात्र?` वैकल्पिक छात्र है; `रिक्त` केवल वैकल्पिक प्रकार में रखा जा सके।
`पूर्णांक?` जैसे वैकल्पिक scalar भी शामिल करें। `शून्य?` अमान्य हो।

```bhash
स्थिर नाम: पाठ = "सीमा";
मान छात्रवस्तु: छात्र? = खोजो(नाम);
यदि (छात्रवस्तु != रिक्त) {
    लिखो(छात्रवस्तु.नाम);
}
स्थिर नामपाठ: पाठ = छात्रवस्तु?.नाम ?? "अज्ञात";
```

यह वाक्यविन्यास strict profile में उपलब्ध है। `??` का दायाँ
operand तभी चले जब बायाँ रिक्त हो; receiver केवल एक बार evaluate हो। आरंभिक scope
में unchecked null-forgiving `!` escape न दें। आवश्यक होने पर स्पष्ट जाँच और
अपवाद वाला सहायक फलन लिखें। `T[]?`, `T?[]` और `(फलन()->T)?` के अलग अर्थ हों।

`प्रकारहै<T>(व)` सफल होने पर स्थिर स्थानीय `व` का प्रकार उस branch में संकीर्ण हो
सकता है। `सुरक्षितरूप<T>(व)` का परिणाम `T?` हो; असफल cast को गैर-रिक्त परिणाम के
रूप में घोषित न करें।

## प्रवाह-जाँच (Flow analysis)

Compiler हर binding के लिए not-null, maybe-null और unreachable अवस्थाएँ रखे।
`यदि`, चयन, loops, short circuit, early return, throw तथा try/catch/finally में
अवस्थाओं को मिलाएँ। एक branch में जाँच होने से दूसरी branch सुरक्षित न मानी जाए।
`यदि (व == रिक्त) { लौटाओ; }` के बाद `व` सुरक्षित हो सकता है।

Smart casts पहले uncaptured locals और स्थिर bindings तक सीमित हों। बदलने योग्य
क्षेत्र, properties, closure से बदले जा सकने वाले locals और callbacks/प्रतीक्षा के
आर-पार बदल सकने वाले मान पर पुरानी जाँच लागू न करें। उन्हें एक स्थिर स्थानीय मान
में लेकर जाँचें। किसी instance का गैर-रिक्त होना उसके सभी fields को गैर-रिक्त नहीं
बनाता।

## निर्माण और सरणियाँ (Initialization and arrays)

सभी गैर-रिक्त instance reference fields हर सफल constructor path पर initialized
हों। इसे केवल immutable fields तक सीमित न रखें। Constructor-assigned immutable
fields की definite-assignment machinery इस जाँच का आधार बने। Virtual calls और
आंशिक रूप से बनी वस्तु के escape पर मौजूदा रोक बनी रहे। Static references के लिए
भी initialization और चक्रीय class initialization का स्पष्ट अनुबंध आवश्यक होगा।

`नया छात्र[१०]` को रिक्त entries वाली `छात्र[]` बनाने की अनुमति न दें। विकल्प:
`छात्र?[]`, सभी तत्वों वाला literal, या index-to-element factory वाला array builder।
संख्या/तार्किक के शून्य defaults और पाठ का खाली default बने रह सकते हैं। Collection
insert और update पर element nullability भी जाँची जाए।

## सामान्य प्रकार (Generics)

`सूची<छात्र>` और `सूची<छात्र?>` अलग invariant प्रकार हों। Type inference nullability
को सुरक्षित रखे; केवल `रिक्त` से स्थानीय binding या generic argument का प्रकार
अनुमान न करे। `T: नामित` की सीमा गैर-रिक्त implementer माँगे। वैकल्पिक bound या
अलग not-null constraint जोड़ना तभी हो जब वास्तविक library usage की आवश्यकता हो।
Overload resolution nullable/non-nullable signatures को अलग overload बनाने का
माध्यम न बनाए; इससे JVM erasure और null literal की अस्पष्टता से बचेंगे।

## पुस्तकालय और विदेशी सीमाएँ (Libraries and foreign boundaries)

खोज में परिणाम न मिलना, मानचित्र में अनुपस्थित कुंजी, JSON null, SQL NULL, वैकल्पिक
HTTP fields और ORM nullable columns के अनुबंध की अलग audit करें। जहाँ अनुपस्थिति
सामान्य परिणाम है वहाँ `T?` दें; malformed data और transport failure अपवाद ही रहें।
`मानचित्र.लो` जैसे APIs के वर्तमान missing-key और fallback नियम चुपचाप न बदलें।

Java/.NET/C/JNI से आए references पर declared boundary check हो; अनजान foreign
signature को स्वयं non-null न मानें। C ABI की versioning, presence flags और buffer
ownership स्पष्ट रखें। Browser/WASM capability restrictions बनी रहें।

## निरूपण और GC (Representation and GC)

Reference `T?` वही managed pointer या null हो सकता है। Nullable scalar को स्पष्ट
presence tag और scalar payload के रूप में निर्दिष्ट करें; scalar zero, false या
NaN को अनुपस्थिति का sentinel न बनाएँ। हर backend का storage अलग हो सकता है, पर
boxing, equality, type tests, collections तथा serialization के सार्वजनिक नियम
समान हों। Nullable scalar boxing में underlying scalar type की पहचान सुरक्षित रहे।

Native descriptors केवल वास्तविक reference slots trace करें। उपस्थित nullable
references, boxed scalars, closure captures, suspended async frames और exception
paths पर forced-GC जाँचें। Integer bits को pointers न माना जाए। किसी absent payload
में पुराना reference बचाकर अनावश्यक heap retention न हो।

## संक्रमण और निदान (Migration and diagnostics)

अनुशंसा: पहले project-level `nullSafety=strict` opt-in दें। पुराने projects का
व्यवहार वही रहे। Bundled standard library के strict signatures compiler के साथ
version हों; nullable library signatures की migration guide दें। Default बदलने का
release निर्णय अलग review हो। असुरक्षित dereference, invalid null assignment और
uninitialized non-null field के अलग diagnostic codes तथा हिन्दी सुधार सुझाव हों।

Editor diagnostics, hover, completion और rename उसी nullable type graph का उपयोग
करें। Jupyter session में binding की nullability भी signature का भाग हो; उसे बदलने
पर restart या स्पष्ट redeclaration नीति हो। Playground के generated wrappers
source locations सुरक्षित रखें।

## स्वीकृति की जाँच (Acceptance tests)

- हर target पर accepted/rejected null assignments, branch narrowing और early exits।
- Constructors, inherited fields, exceptions, finally तथा array factories।
- Generic constraints, nested collections, overload ambiguity और nullable scalars।
- SQL NULL, JSON null, safe casts और foreign boundary rejection।
- Callback mutation और await के बाद असुरक्षित smart-cast rejection।
- Native forced GC, boxing/unboxing, async cleanup तथा browser isolation।
- Existing legacy projects और strict-mode Hindi tutorial, JVM/CLR/native/browser/Jupyter।

## कार्यान्वयन के व्यावहारिक निर्णय (Implementation decisions)

Project profile के साथ एक build के लिए `--null-safety strict` और single-file/
notebook के लिए `// bhash:null-safety=strict` उपलब्ध हैं। Nullable scalar मौजूद
होने पर मौजूदा typed boxing और अनुपस्थित होने पर null pointer उपयोग करता है।
`?.`, lazy `??` और lifted conversions source-located helpers/closures में उतरते
हैं; सभी targets वही checked control flow और native GC root machinery उपयोग
करते हैं। Awaiting fallback और member arguments सीधे caller के async states में
शाखा बनाते हैं; skipped branch कोई अतिरिक्त await या task नहीं बनाती। मौजूदा
always-yielding await तथा cancellation के नियम अपने वास्तविक execution path पर
ही लागू होते हैं।

इस आरम्भिक opt-in में एक compilation के source packages का profile समान होना
आवश्यक है। Bundled library के JSON/अनुपस्थित-result signatures अलग audit से
nullable होते हैं। SQL के typed getters और explicit `रिक्तहै()` policy यथावत हैं।
मौजूदा C/JNI export ABI nullable signatures अस्वीकार करता है; कोई नया presence
या ownership representation अनुमान से नहीं बनाया गया है। यह प्रतिबंध हिन्दी
ट्यूटोरियल में migration के साथ स्पष्ट है।

Mutable fields/captures पर unsafe smart casts नहीं हैं। Stable immutable field
chains को जाँच के बाद narrow किया जा सकता है। Loops और exception paths पर
conservative proofs के कारण कभी-कभी explicit स्थानीय snapshot फिर जाँचना पड़ता
है। [उपयोगकर्ता ट्यूटोरियल](../tutorial/null-safety/README.md) चलने योग्य नियम देता है।

## स्वीकृत निर्णय (Approved decisions)

१. Non-null defaults, `T?` और flow analysis को स्वीकार करें।
२. Nullable scalars तथा `?.`/`??` को प्रथम milestone में शामिल करें।
३. प्रारंभ में null-forgiving escape न दें।
४. Opt-in migration अपनाएँ; default बदलने का निर्णय अलग रखें।
५. गैर-रिक्त reference arrays के लिए पूर्ण initialization अनिवार्य करें।
