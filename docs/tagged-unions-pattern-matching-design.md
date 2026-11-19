# विकल्पसमूह, मिलान और परिणाम का प्रस्ताव (Tagged unions, pattern matching and Result)

[English version](tagged-unions-pattern-matching-design.en.md)

स्थिति: **स्वीकृत; `feature/tagged-unions` में स्थानीय कार्यान्वयन उपलब्ध है; remote CI सत्यापन शेष है।**
आधार: प्रकाशित संकलक/VS Code 0.26.0 और Jupyter 0.1.12 के भाषा अनुबंध।
यह स्वीकृत भाषा अनुबंध है। चलने योग्य उदाहरण [हिन्दी ट्यूटोरियल](../tutorial/tagged-unions/README.md) में हैं। Jupyter के मौजूदा scalar बन्धन ही cells के बीच संग्रहित होते हैं; object बन्धन cell-local रहते हैं। विकल्पसमूह/फलन की घोषणा अगले cells में पुनः उपयोग करें और वस्तु का निर्माण तथा मिलान उसी cell में रखें।

## १. उद्देश्य (Goals)

रिक्त-सुरक्षा बताती है कि किसी मान की अनुपस्थिति सम्भव है या नहीं। अनुप्रयोगों को
अक्सर इससे अधिक जानकारी चाहिए: छात्र मिला, छात्र नहीं मिला, या खोज असफल हुई।
हर स्थिति में अलग डेटा भी हो सकता है। इस प्रस्ताव में एक मान एक समय पर केवल एक
घोषित विकल्प रखता है। संकलक उस विकल्प से डेटा निकालने और सभी सम्भावनाएँ सँभालने
की जाँच करता है। प्रोग्रामर को स्मृति आवंटन या विमुक्ति नहीं करनी होगी।

पहला लक्ष्य JVM, .NET CLR, browser WebAssembly और native में समान सार्वजनिक
व्यवहार है। Jupyter, VS Code और Playground उसी भाषा अनुबंध का उपयोग करें।
वर्तमान HTTP, database, ORM, task और exception API अपने व्यवहार बनाए रखें।

## २. समीक्षा के लिए मुख्य निर्णय (Recommended decisions)

| विषय | अनुशंसा | प्रभाव |
| --- | --- | --- |
| घोषणा | संदर्भानुसार शब्द `विकल्पसमूह` | numeric `परिगणना` अलग और अपरिवर्तित रहेगी |
| पैटर्न का चयन | संदर्भानुसार शब्द `मिलान` | वर्तमान `चयन` के नियम नहीं बदलेंगे |
| विकल्प बनाना | `प्रकार.विकल्प(तर्क)` factory | शून्य तर्क वाले विकल्प में भी `()` आवश्यक |
| संरचना | बन्द nominal family; प्रत्येक विकल्प में अचल क्षेत्र | बाहर से विकल्प जोड़ना या उससे विरासत लेना अस्वीकार |
| मिलान की पूर्णता | कथन और व्यंजक, दोनों में सभी सम्भव मान सँभालना आवश्यक | छूटा विकल्प संकलन त्रुटि होगा |
| मानक परिणाम | `मानक.परिणाम<त, द>` में `सफल` तथा `असफल` | साधारण त्रुटि डेटा के रूप में लौट सकती है |
| सफल किन्तु बिना डेटा | `परिणाम<मानक.इकाई, द>` | सामान्य generics में `शून्य` की छूट नहीं जोड़नी होगी |
| समानता | एक ही ठोस family और विकल्प के क्षेत्रों की संरचनात्मक समानता | map/set keys और `सर्ववस्तु` में भी वही नियम |
| native भंडारण | हर विकल्प की अलग immutable heap object और fixed GC descriptor | overlapping C union अथवा tag देखकर GC scanning की आवश्यकता नहीं |
| संगतता | additive syntax; पुराने APIs और numeric enum अर्थ यथावत | पुराने स्रोत को बदलना अनिवार्य नहीं |
| प्रथम सीमा | C/JNI exports में सीधे union/Result नहीं | Android/JNI के लिए स्पष्ट wrapper उपयोग करना होगा |

## ३. हिन्दी घोषणा (Hindi declarations)

```text
// प्रस्तावित वाक्यविन्यास; strict profile में उदाहरण
// bhash:null-safety=strict
अभिलेख छात्र(नाम: पाठ, क्रमांक: पूर्णांक);
अभिलेख खोजदोष(कोड: पाठ, संदेश: पाठ);

विकल्पसमूह छात्रखोज {
    मिला(विद्यार्थी: छात्र);
    अनुपस्थित();
    असफल(दोष: खोजदोष);
}
```

`छात्रखोज` एक नामित प्रकार है। `मिला`, `अनुपस्थित` और `असफल` उसी family के
बन्द विकल्प हैं। क्षेत्र नामित और प्रकारयुक्त हैं; उनका क्रम निर्माता तथा
positional patterns, दोनों का क्रम है। पूरी family में एक समान क्षेत्र-सूची
रखना आवश्यक नहीं है। शून्य क्षेत्र वाला विकल्प भी वैध मान है।

सामान्य मापदंड और उनकी सीमाएँ वर्तमान generics की तरह घोषित होंगी:

```text
सार्वजनिक विकल्पसमूह परिणाम<त, द> {
    सफल(मूल्य: त);
    असफल(दोष: द);
}
```

कम से कम एक विकल्प आवश्यक है। एक ही family में दोहरे विकल्प नाम, एक विकल्प
में दोहरे क्षेत्र नाम, गलत प्रकार और गलत generic arguments त्रुटि हैं। नाम NFC
identifier equivalence और मौजूदा Unicode/joiner profile के नियम मानेंगे।

पहले संस्करण में custom constructors, methods, static fields, interface
implementation, inheritance, per-case visibility और partial/open unions नहीं होंगे।
सार्वजनिक family के सभी विकल्प तथा उनके payload types उसके सार्वजनिक अनुबंध हैं;
उससे निजी प्रकार बाहर निकालना अस्वीकार होगा। Family-level visibility वर्तमान
package export नियम मानेगी। कोई निजी generated case class सार्वजनिक Bhash नाम नहीं बनेगी।

## ४. स्रोत-संगत शब्द (Contextual keywords)

`विकल्पसमूह` और `मिलान` को हर जगह reserved lexer keyword बनाने की अनुशंसा नहीं है।
वे साधारण identifier tokens रहें; parser केवल नए रूप में उनका विशेष अर्थ चुने।
उदाहरणतः घोषणा में `विकल्पसमूह नाम ... { ... }`, और मिलान में
`मिलान (व्यंजक) { ... }` पहचाना जाए। `सार्वजनिक विकल्पसमूह ...` भी मान्य हो।

इससे मौजूदा `फलन मिलान(...)`, `मिलान(तर्क);`, `वस्तु.मिलान(...)` और
`मान विकल्पसमूह = ...;` अपने पुराने अर्थ बनाए रखते हैं। मिलान के बाद `{` उसे
साधारण फलन-कॉल से अलग करता है। Lookahead केवल parser shape और canonical शब्द
देखे; symbol lookup अथवा backtracking से अर्थ का अनुमान न करे।

`विकल्प`, `यदि`, `शेष`, `स्थिर` और `रिक्त` पहले से उपलब्ध शब्द हैं। नया `=>`
केवल arm के लिए होगा; फलन का `->` अपरिवर्तित रहेगा। अंग्रेज़ी `union`/`match`
aliases इस प्रस्ताव का हिस्सा नहीं हैं। हिन्दी शब्दों की स्वीकृति भी समीक्षा का विषय है।

## ५. विकल्प बनाना और प्रकार पहचान (Construction and nominal identity)

```text
स्थिर क: छात्रखोज = छात्रखोज.मिला(नया छात्र("सीमा", १));
स्थिर ख: छात्रखोज = छात्रखोज.अनुपस्थित();
स्थिर ग: मानक.परिणाम<छात्र, खोजदोष> =
    मानक.परिणाम<छात्र, खोजदोष>.असफल(नया खोजदोष("अनुपलब्ध", "फिर प्रयास करें"));
```

Family का अपना सार्वजनिक `नया छात्रखोज(...)` निर्माता नहीं होगा। Factories का
नाम, arity और argument types घोषित विकल्प से बनेंगे; तर्क बाएँ से दाएँ एक बार
चलेंगे। प्रारम्भ में factory owner पर closed generic arguments स्पष्ट देना होगा।
केवल payload देखकर दूसरे generic argument का अनुमान नहीं होगा। यह वर्तमान
generic फलन inference को नहीं बदलता।

Case नाम अलग सार्वजनिक subtype नहीं है: `छात्रखोज.मिला` को variable type,
generic argument या `प्रकारहै<...>` के type argument की तरह नहीं लिखेंगे। उसका
डेटा मिलान से खुलेगा। सामान्य member access से `क.विद्यार्थी` पढ़ना अस्वीकार होगा,
क्योंकि `क` किसी दूसरे विकल्प में भी हो सकता है।

`प्रकारहै<छात्रखोज>(मूल)` सभी constructed विकल्पों पर सत्य होगा। वर्तमान checked
cast और safe cast family तथा उसके closed generic arguments पहचानेंगे। Union से
`सर्ववस्तु` में जाने पर वही reference रहे; नया scalar box नहीं बनेगा।

`परिणाम<छात्र, खोजदोष>` और `परिणाम<छात्र?, खोजदोष>` अलग invariant प्रकार हैं।
उनके बीच covariance, implicit conversion या anonymous `A | B` प्रकार नहीं जुड़ेंगे।
Recursive families सम्भव हैं; वर्तमान specialization और constraint limits लागू रहेंगी।

एक closed family एक user-visible type specialization गिने; उसके private case
layouts अलग user generic instantiations न गिने जाएँ। उनके आकार और pattern-analysis
काम पर अलग compiler budgets रहें।

## ६. मिलान व्यंजक (Match expression)

```text
फलन विवरण(उत्तर: छात्रखोज) -> पाठ {
    लौटाओ मिलान (उत्तर) {
        विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी) => विद्यार्थी.नाम,
        विकल्प छात्रखोज.अनुपस्थित() => "छात्र नहीं मिला",
        विकल्प छात्रखोज.असफल(स्थिर दोष) => दोष.संदेश,
    };
}
```

Selector एक बार चलता है और उसका मान पूरे मिलान का स्थिर snapshot है। Arms स्रोत
क्रम में जाँची जाती हैं। पहला सफल pattern और सत्य guard मिलने पर केवल उसी arm
का व्यंजक चलता है। दूसरी arms के guards तथा bodies नहीं चलेंगे।

सभी reachable arm results एक प्रकार में जाँच सकने चाहिए। जहाँ assignment,
return अथवा parameter से अपेक्षित प्रकार मिलता है, उसी का उपयोग करें। अन्यथा
वर्तमान expression/type-inference नियमों में स्पष्ट रूप से एक प्रकार मिलना
आवश्यक है; असंगत results को चुपचाप `सर्ववस्तु` में box न करें। Nullable results
की संगतता वर्तमान null-safety नियम माने। अस्पष्टता में स्पष्ट result type दें।

प्रथम संस्करण में हर `=>` के बाद एक expression होगा; अलग block-result अथवा
`yield` syntax नहीं होगा। हर arm के बाद comma रखें; अन्तिम comma भी स्वीकार हो।
मिलान एक primary expression हो, इसलिए `मिलान (...) { ... } ?? विकल्पमान`
जैसे रूप में उसकी precedence स्पष्ट हो।

यह expression form value लौटाए; `शून्य` result वाले calls के लिए statement form
उपयोग करें। `फेंको` को नया expression न बनाएँ; जरूरत पर statement arm अथवा
मौजूदा typed helper दें।

## ७. मिलान कथन और सामान्य नियन्त्रण (Match statement and control flow)

```text
मिलान (उत्तर) {
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी): {
        लिखो("नाम:", विद्यार्थी.नाम);
        लिखो("क्रमांक:", विद्यार्थी.क्रमांक);
    }
    विकल्प छात्रखोज.अनुपस्थित(): {
        लिखो("छात्र नहीं मिला");
    }
    विकल्प छात्रखोज.असफल(स्थिर दोष): {
        लिखो("खोज असफल:", दोष.संदेश);
    }
}
```

कथन में `:` और अनिवार्य block रखें; व्यंजक की तरह `=>` arms उसमें न मिलाएँ।
हर block का अपना scope है। Fallthrough तथा grouped labels प्रारम्भ में नहीं होंगे।
एक arm के सामान्य समापन पर पूरा मिलान समाप्त होगा।

`लौटाओ`, `फेंको` और `संसाधन` अपने सामान्य नियम मानेंगे। `विराम` निकटतम loop
या enclosing selection/match statement से निकलेगा; `जारी` निकटतम loop पर जाएगा।
Match expression अपने आप break/continue target नहीं होगा। उसके भीतर बुलाए गए
फलन की वापसी उसी फलन की वापसी है। Match statement हर path पर लौटता है तभी
definite-return analysis उसे पूरा return माने।

## ८. पैटर्न और bindings (Patterns and bindings)

| रूप | अर्थ |
| --- | --- |
| `छात्रखोज.अनुपस्थित()` | उसी family का बिना payload वाला विकल्प |
| `छात्रखोज.मिला(स्थिर विद्यार्थी)` | विकल्प पहचानकर उसके क्षेत्र का स्थिर स्थानीय नाम |
| `छात्रखोज.मिला(छात्र(स्थिर नाम, _))` | अंदर के अभिलेख के घोषणा-क्रम वाले क्षेत्र खोलना |
| `रिक्त` | केवल absent/null मान |
| `स्थिर क: छात्र` | non-null runtime type test और typed binding |
| `स्थिर क` | पूरा selector/payload बाँधना; null भी शामिल हो सकता है |
| `_` | मान छोड़ना; कोई binding नहीं |
| `१`, `"तैयार"`, `सत्य`, `स्थिति.तैयार` | वर्तमान प्रकार और equality नियम वाला constant |

केवल union payload और `अभिलेख` के असली immutable fields deconstruct होंगे।
सामान्य class fields, computed properties, getter calls, arbitrary expressions,
range/list patterns, OR patterns और user-defined extraction methods प्रथम scope
में नहीं होंगे। इससे pattern जाँच अपने आप user code नहीं बुलाती।

`स्थिर` bindings guard और उसी arm में उपलब्ध हैं; वे बाहर नहीं निकलेंगी। एक
pattern में दोहरा binding नाम अस्वीकार हो। बाहरी local को shadow करना वर्तमान
block-scope नियम माने। Assignment से binding बदलना अस्वीकार होगा; उसके mutable
object/list को बदलने पर कोई नई deep-immutability गारंटी नहीं है। Closure capture
वर्तमान capture नियम माने और आवश्यक field/reference जीवित रखे।

Pattern में अकेला `_` wildcard है; उसके बाहर पुराना `_` identifier मान्य रहेगा।
`स्थिर _` को अस्पष्ट discard/binding बनाने के बजाय अस्वीकार करें। Type pattern
में `T?` न दें; null की अलग arm अथवा catch-all binding दें। Type pattern का सफल
binding non-null है और वर्तमान casts की exact primitive type पहचान रखता है।

Union pattern का owner लिखना आवश्यक हो। Closed selector से उसी generic family
के pattern में arguments छोड़े जा सकते हैं: `मानक.परिणाम.सफल(स्थिर क)`। यदि
selector `सर्ववस्तु` है तो पूरा closed owner लिखना होगा। Record patterns में
generic arguments प्रथम संस्करण में स्पष्ट हों। Field count और nested types
मिलना आवश्यक है; पुनर्व्यवस्था अथवा छिपे numeric conversions नहीं होंगे।

Integral literals अपेक्षित integral type के range नियम मानें। `सर्ववस्तु` पर
असन्दर्भित `१` का प्रकार `पूर्णांक` होगा; boxed `बाइट` से वह नहीं मिलेगा।
Decimal/NaN literal patterns अभी न जोड़ें; decimal type binding के बाद guard दें।
Text matching ordinal value से हो; source identifier normalization को strings
पर लागू न करें।

## ९. शर्त और पूर्णता-जाँच (Guards, exhaustiveness and reachability)

```text
मिलान (उत्तर) {
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी) यदि (विद्यार्थी.क्रमांक > १०): {
        लिखो("वरिष्ठ:", विद्यार्थी.नाम);
    }
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी): { लिखो(विद्यार्थी.नाम); }
    विकल्प छात्रखोज.अनुपस्थित(): { लिखो("अनुपस्थित"); }
    विकल्प छात्रखोज.असफल(स्थिर दोष): { लिखो(दोष.कोड); }
}
```

Guard `तार्किक` होना चाहिए। वह pattern सफल होने पर एक बार चलता है। उसमें
साधारण calls, side effects और, उचित async context में, `प्रतीक्षा` मान्य होंगे।
Guard false हो तो अगली arm जाँची जाएगी। Guard throw करे तो exception बाहर जाएगी;
अगली arm नहीं चलेगी। Pattern से पहले कोई guard नहीं चलाया जाएगा।

दोनों नए match forms exhaustive हों। Closed unions, booleans, nullable presence,
typed enums और records के nested patterns के लिए साझा coverage checker हो। Guards
coverage में नहीं गिनी जाएँगी, भले guard में literal `सत्य` हो। इसलिए guarded
`मिला` के बाद unguarded `मिला` या catch-all आवश्यक है।

Numbers/text जैसी अनन्त domains और open class hierarchies में samples अथवा अभी
ज्ञात subclasses को पूरी दुनिया न मानें। Catch-all, या पूरे non-null domain को
ढकने वाला type pattern, तथा आवश्यक null case दें। Record pattern केवल अपने
subpatterns जितना domain ढकता है। `_` और untyped binding null सहित पूरा domain ढकते हैं।

`शेष` unguarded catch-all का वैकल्पिक पठनीय रूप हो: statement में `शेष: { ... }`
और expression में `शेष => व्यंजक,`। इसे अन्त में रखें; `_` तथा `शेष` से दोहरा
catch-all बनाना unreachable error होगा। पूर्णतः ढकी हुई बाद की arm संकलन त्रुटि
हो; आंशिक overlap वैध है और source-order नियम मानेगा।

Diagnostic कम से कम एक छूटी सम्भावना दिखाए, जैसे
`छात्रखोज.अनुपस्थित()` या `मिला(छात्र(_, रिक्त))` जहाँ प्रकार ऐसा होने दें।
Recursive/product patterns के लिए memoized usefulness/coverage analysis रखें।
प्रस्तावित आन्तरिक सीमा १०,००० analysis states और ६४ pattern nesting levels है;
सीमा पर स्पष्ट Hindi diagnostic और सरल pattern/catch-all का सुझाव दें। चुपचाप
पूर्णता स्वीकार न करें। इन सीमाओं का सामान्य कार्यक्रमों पर परीक्षण आवश्यक है।

## १०. रिक्त-सुरक्षा और defaults (Null safety and defaults)

Strict profile में `छात्रखोज` non-null और `छात्रखोज?` nullable होगा। Nullable
selector की null सम्भावना किसी explicit `रिक्त` arm या catch-all से ढकें। वर्तमान
flow analysis से वैध non-null proof मिले तो उसी effective domain की जाँच हो।
केवल मिलान करने से बदल सकने वाले बाहरी receiver की स्थायी refinement न मानें;
arm bindings selector snapshot से बनती हैं।

Legacy profile में reference selector की सम्भावित `रिक्त` अवस्था को नए `मिलान`
में भी ढकना होगा। यह नए syntax का नियम है; पुराने `चयन` का no-match/default
व्यवहार नहीं बदलेगा। सभी मुख्य tutorial examples strict directive से आरम्भ हों।

किसी विकल्प को implicit default न बनाएँ। Strict में `नया छात्रखोज[१०]` अधूरे
non-null entries के कारण अस्वीकार होगा; nullable array, पूर्ण literal अथवा
मौजूदा अनुमत builder उपयोग करें। Constructor/static initialization नियम लागू रहें।

`परिणाम<छात्र?, दोष>` का `सफल(रिक्त)` पूरी तरह बना हुआ सफलता परिणाम है। वह
पूरे `परिणाम<छात्र, दोष>?` के `रिक्त` मान से अलग है। इसी तरह `असफल(रिक्त)`
तभी वैध है जब error payload का प्रकार उसकी अनुमति देता हो।

## ११. समानता, hashing और प्रदर्शन (Equality, hashing and display)

Union values समान तभी हों जब closed nominal family, विकल्प और सभी payload
fields समान हों। Declaration में विकल्पों का एक जैसा क्रम या fields का एक जैसा
आकार दो अलग families को समान नहीं बनाता। Generic arguments भी पहचान का हिस्सा हैं।

Fields पर अभिलेखों वाला वर्तमान value-comparison अनुबंध अपनाएँ: scalar के exact
type नियम, text value equality, nested record/union structural equality और सामान्य
object/collection reference identity। Decimal fields में collection equality के
NaN और signed-zero नियम रहें। Typed comparisons की वर्तमान comparability जाँच
न बदलें; असंगत typed operands को `असत्य` लौटाने के लिए स्वतः universal न बनाएँ।

यही नियम direct equality, `सर्ववस्तु`, map keys और sets में लागू हो। Hash में
nominal family, closed arguments, विकल्प और fields शामिल हों। समान values का
hash एक process में समान हो; raw hash और case ordinal कोई portable wire format नहीं हैं।

गहरी/shared immutable संरचनाओं की comparison/hash iterative और memoized हों;
C stack पर असुरक्षित recursion या exponential repeated traversal न हो। Mutable
references के contents traverse नहीं होंगे। Shallow immutability records जैसी है।

पहले संस्करण में implicit JSON, SQL, ORM discriminator, text formatting या
संघ-से-पाठ conversion न जोड़ें। स्पष्ट मिलान/mapper उपयोग करें। Debugger/Jupyter
inspector पढ़ने योग्य family, active case और fields दिखाएँ; formatting के लिए
application methods न बुलाएँ और sensitive payload अपने आप logs में न लिखें।

Native comparison/hash helpers सामान्य मार्ग में managed allocation या GC न करें।
अगर कोई error path अपवाद बनाने के लिए allocate करता है तो उससे पहले उसके जीवित
operands precise roots में हों; allocation failure की वर्तमान fatal/runtime policy बनी रहे।

## १२. मानक परिणाम और इकाई (Standard Result and Unit)

`मानक.परिणाम<त, द>` ऊपर घोषित दो-विकल्प वाला सामान्य standard-library type हो।
`त` और `द` पर स्वतः `अपवाद` या किसी class की सीमा न हो। Error text, enum, record
या अलग union हो सकती है। Qualified name और explicit imports वर्तमान shadowing
नियम मानें; `परिणाम` नाम वैश्विक reserved identifier न बने।

`मानक.इकाई` शून्य fields वाला immutable record हो। बिना success payload वाले
operation का प्रकार `परिणाम<मानक.इकाई, दोष>` हो और success payload
`नया मानक.इकाई()` हो। `परिणाम<शून्य, दोष>` अस्वीकार रहे। मौजूदा
`कार्यफल<शून्य>` की विशेष छूट केवल task API की रहेगी।

आरम्भिक API case factories और मिलान तक सीमित रखें। Implicit unwrap, error
propagation `?`, implicit exception conversion, custom union methods और callback
helpers जैसे map/flatMap बाद के अलग निर्णय हों। गलत विकल्प से payload पढ़ने वाला
throwing `मूल्य` getter शुरू में देने की आवश्यकता नहीं है।

`असफल(दोष)` एक सामान्य value है। Compiler उसके अपने आप log होने, फेंके जाने,
retry होने या caller से लौट जाने का अनुमान नहीं लगाएगा। प्रथम milestone में
Result-specific must-use warning नहीं जोड़ेंगे; वर्तमान task-valued expression
diagnostic बना रहेगा। अवश्य-उपयोग policy अलग समीक्षा का विषय होगी।

## १३. काम करने के ढंग का उदाहरण (Proposed application example)

नीचे का छोटा future program बिना database/network के तीनों अवस्थाएँ दिखाता है।
यह नया syntax स्वीकृत होने के बाद runnable fixture और Hindi tutorial बनेगा।

```text
// bhash:null-safety=strict
अभिलेख छात्र(नाम: पाठ, क्रमांक: पूर्णांक);
अभिलेख खोजदोष(कोड: पाठ, संदेश: पाठ);
विकल्पसमूह छात्रखोज {
    मिला(विद्यार्थी: छात्र);
    अनुपस्थित();
    असफल(दोष: खोजदोष);
}

फलन खोजो(क्रमांक: पूर्णांक) -> छात्रखोज {
    यदि (क्रमांक == १) {
        लौटाओ छात्रखोज.मिला(नया छात्र("सीमा", १));
    }
    यदि (क्रमांक == ०) { लौटाओ छात्रखोज.अनुपस्थित(); }
    लौटाओ छात्रखोज.असफल(नया खोजदोष("अमान्य", "क्रमांक जाँचें"));
}

फलन बताओ(उत्तर: छात्रखोज) -> पाठ {
    लौटाओ मिलान (उत्तर) {
        विकल्प छात्रखोज.मिला(छात्र(स्थिर नाम, _)) => "मिला: " + नाम,
        विकल्प छात्रखोज.अनुपस्थित() => "छात्र नहीं मिला",
        विकल्प छात्रखोज.असफल(स्थिर दोष) => दोष.संदेश,
    };
}

फलन मुख्य() -> शून्य {
    लिखो(बताओ(खोजो(१)));
    लिखो(बताओ(खोजो(०)));
    लिखो(बताओ(खोजो(-१)));
}
```

अपेक्षित output: `मिला: सीमा`, फिर `छात्र नहीं मिला`, फिर `क्रमांक जाँचें`।

Standard Result का अलग उदाहरण:

```text
// इसी strict उदाहरण के छात्र और खोजदोष प्रकार
स्थिर उत्तर: मानक.परिणाम<छात्र, खोजदोष> =
    मानक.परिणाम<छात्र, खोजदोष>.सफल(नया छात्र("सीमा", १));
स्थिर विवरण: पाठ = मिलान (उत्तर) {
    विकल्प मानक.परिणाम.सफल(स्थिर विद्यार्थी) => विद्यार्थी.नाम,
    विकल्प मानक.परिणाम.असफल(स्थिर दोष) => दोष.संदेश,
};
लिखो(विवरण);
```

बाद का HTTP/database demo इसी मॉडल को वास्तविक lookup से जोड़े। केवल स्पष्ट
expected conditions को domain values में बदलें। अनजान exception को blanket catch
करके सामान्य `असफल` न बनाएँ। SQL/network विवरण client को ज्यों का त्यों न भेजें।
Transport failure, cancellation और transaction outcome unknown जैसे मौजूदा
अपवाद अपने अनुबंध बनाए रखें। Database transaction resources हर विकल्प, return,
exception और cancellation पर वर्तमान cleanup नियम से बन्द हों।

## १४. Async और संसाधन-सफ़ाई (Tasks, cancellation and cleanup)

`कार्यफल<परिणाम<त, द>>` में दो अलग स्तर होंगे। Task पूर्ण होकर `असफल(दोष)`
लौटाए तो await सामान्य Result value देगा। Task faulted/cancelled हो तो `प्रतीक्षा`
वर्तमान exception/cancellation व्यवहार रखेगी। नये cancellation case को Result
में अपने आप न जोड़ें और task fault को Result failure में स्वतः न बदलें।

Async guard या चुनी हुई async arm में suspension पर selector, आवश्यक extracted
bindings और pending results typed heap-frame fields में spill हों। Guard से पहले
निकाला payload उसी snapshot का रहे; resume पर selector दोबारा न चलाएँ। False
guard के बाद उसकी bindings छोड़कर अगली arm पर जाएँ। Await का वर्तमान yielding
और cancellation-check नियम वास्तविक execution path पर लागू हो।

Match कोई implicit resource scope/transaction commit नहीं है। चुनी arm में
`संसाधन` और `असमकालिक संसाधन` सामान्य रूप से काम करें। Normal completion,
`विराम`, `जारी`, return, throw और cancellation पर सफ़ाई वर्तमान क्रम में हो।
Cleanup exception pending return को बदल दे तो पुराने returned union/payload की
temporary roots भी छोड़ें। Ownership अथवा destructor का नया अनुबंध नहीं जुड़ेगा।

## १५. साझा compiler योजना (Compiler architecture)

वर्तमान pipeline में ProjectResolver, GenericExpander, ManagedEmitter और native
CoreProgram/LLVM lowering की अलग जिम्मेदारियाँ हैं। यह सुविधा पूरा compiler
पुनर्लेखन किए बिना जुड़े; लेकिन हर backend अपने अलग pattern semantics न बनाए।

साझा checked model में `UnionFamily`, `UnionCase`, typed `Pattern`, coverage
result और source-located `MatchPlan` रखें। Family visibility और aliases पहले
resolve हों; specialization के बाद concrete payload types, constraints और
representation तय हों। Unused templates की मौजूदा checking policy न बदलें।

MatchPlan में evaluate-once selector, pure case/type/field tests, success-only
bindings, guard edge, next-arm edge, selected body और merge/result edge स्पष्ट हों।
Ordinary expressions/await वाले guards और bodies वर्तमान control-flow machinery
में उतरें। Side effects वाले arms को sharing/reordering optimizations न बदलें।

Return/definite-assignment/null-flow analysis उसी plan का उपयोग करे। Source spans
मूल pattern, payload field, guard और arm body तक रहें। Private generated names
error text, stack traces और F2/Find References में सार्वजनिक names का स्थान न लें।

यह scoped checked plan होगा; public IR, separately compiled union libraries या
पूरी typed-AST migration इस milestone की prerequisites नहीं हैं।

## १६. लक्ष्य व्यवहार (Cross-target behavior)

| लक्ष्य/होस्ट | प्रस्तावित निरूपण और आवश्यक जाँच |
| --- | --- |
| JVM | internal family base और final generated case classes; स्वयं Bhash coverage checker, host Java pattern syntax पर निर्भरता नहीं |
| .NET CLR | वही logical family और sealed case objects; primitive payload unneeded universal boxing के बिना |
| Browser WebAssembly | CLR वाला अनुबंध और वर्तमान isolated worker/prebuilt host; नयी HTTP/file/database capabilities नहीं |
| Native Linux/Windows x64, macOS ARM64 | concrete case heap layouts, precise roots और मौजूदा shared checked plan |
| Jupyter JVM | declarations/case metadata session में बने रहें; बाद के cells में values, patterns और Result उपयोग हों |
| Android native library | internal union use सम्भव; पुराने C/JNI export signatures के माध्यम से सीधा union pass नहीं |

सभी लक्ष्यों पर same source का विकल्प चयन, side-effect क्रम, null checks,
typed equality, visible output और diagnostic location समान हो। Object bytes,
allocation addresses, native case ordinals और raw hashes target-portable नहीं हैं।

Jupyter में case set बदलने को hot-reload करके पुराने live values न बदलें। मौजूदा
declaration replacement नियम लागू हों; जहाँ live nominal identity बदलनी पड़े,
स्पष्ट restart-required diagnostic दें। पूरी family declaration parse/resolve
होने से पहले session symbol table में अधूरा case set commit न हो।

Playground scratch mode में local bindings/matches चलें; top-level family
declarations के लिए Full Program उपयोग हो। Compiler/kernel/extension/browser-host
एक ही grammar और runtime revision से package हों; standalone और prebuilt browser
paths अलग-अलग validate हों।

## १७. Native layout और GC (Precise GC representation)

Collector **precise, stop-the-world, nonmoving mark-and-sweep** ही रहे। हर case
अलग object हो, जिसमें nominal metadata pointer और उसके अपने field carriers हों।
Class-info prefix वर्तमान runtime helpers के अनुकूल हो; union extension में
closed family identity, private case discriminator और field-kind metadata हों।
Logical tag immutable case metadata में हो सकता है; overlapping payload और
mutable active-tag storage प्रारम्भ में न दें।

उदाहरणतः `मिला(छात्र)` का descriptor उसके छात्र reference को trace करेगा।
`अनुपस्थित()` में payload reference slot नहीं होगा। `गिनती(पूर्णांक)` के integer
bits pointer नहीं माने जाएँगे। अलग case sizes सामान्य allocator से आएँगे। मौजूदा
record/class headers को ऐसे reinterpret न करें कि collector गलत offsets पढ़े।

हर closed specialization का अपना exact descriptor हो। Nullable scalar payload
वर्तमान implemented boxed-value-or-null representation उपयोग करे; नया sentinel
या inline optional scalar ABI अनुमान से न बनाएँ। Code generation type-aware
reference classification उपयोग करे, सिर्फ source spelling नहीं।

Construction का अनिवार्य क्रम:

1. Arguments बाएँ से दाएँ एक बार evaluate हों। अगला argument allocate/suspend
   करे तो पहले बने reference arguments writable roots अथवा async frame में रहें।
2. सभी arguments तैयार होने के बाद exact case descriptor से zero-initialized
   object allocate हो। उसे भरने से पहले उसका reference root करें।
3. Reference fields वर्तमान `bh_reference_store` contract से भरें। अधूरे object
   में scalar/reference classification पहले से सही हो; user code उसका access न पाए।
4. पूरी तरह भरा object ही प्रकाशित हो। Failure/unwind पर temporary object और
   argument roots वर्तमान protected-region cleanup से छोड़ें।

मिलान में rooted selector से metadata तथा payload पढ़ें। Guard/call/await/error
helper allocation कर सके तो जीवित extracted references अलग precise slots/frame
fields में सुरक्षित रहें। Field का interior address safepoint के पार न रखें।
Failed guards तथा arm exit पर dead temporaries छोड़ें; escaped captures और pending
return values को उनकी वास्तविक lifetime तक बनाए रखें।

Union objects array/list/map/set fields, `सर्ववस्तु`, static roots, closure captures,
exception objects और suspended tasks में सामान्य managed references की तरह रहें।
Zero-payload case के लिए पहले संस्करण में हर factory call पर ordinary allocation
करें; singleton cache अथवा process-lifetime managed roots न जोड़ें। Structural
equality के कारण भविष्य में interning का अलग optimization सम्भव है।

Writable roots, owner-plus-offset reference stores और reload-after-safepoint
नियम future moving/generational collector के लिए सुरक्षित रखें। Immutable fields
भी initial store barrier को bypass नहीं करेंगी। इस milestone में moving GC,
conservative scanning, user-visible allocation/deallocation या नया ownership model नहीं आएगा।

Future relocation में referenced mutable objects का identity hash भी स्थिर रहना
चाहिए, ताकि union/record map keys स्थान बदलने पर न खो जाएँ। यह पूरे runtime का
भावी collector अनुबंध है; केवल writable roots होने से आज moving-GC support का
दावा नहीं होगा। अभी hash numbers को addresses से बाहर serialize न करें।

इस चुनाव की लागत स्पष्ट है: हर factory call सामान्यतः एक managed object बनाती
है; नया payload record अथवा `इकाई` अलग बनाया जाए तो वह अलग allocation है। Union
array में references और उनके पीछे case objects होंगे। Inline/value-type union की
तुलना में headers, indirection और GC काम बढ़ सकते हैं। बदले में मौजूदा reference
ABI, collection storage और precise roots सरल रहते हैं। इसे zero-allocation अथवा
Rust जैसी inline layout की गारंटी न कहें। Representative workloads मापने के बाद
interning, allocation elision या inline representation की अलग समीक्षा हो सकती है।

## १८. Runtime ABI और विदेशी सीमाएँ (ABI and interoperability)

वर्तमान source header में native internal ABI **17** है। Union metadata, runtime
value equality/hash और debugger schema को समझाने के लिए नया ABI revision आवश्यक
मानेँ। प्रस्ताव **18, यदि बीच में कोई और ABI change न हुआ हो**; implementation
के समय अगला उपलब्ध revision चुनें और उसकी provenance दर्ज करें।

Compiler, bundled C runtime और generated program link-time ABI symbol तथा
runtime/cache fingerprint पर सहमत हों। पुराने object/runtime/cache का असंगत मिश्रण
स्पष्ट error दे; layout पढ़कर अनुमान न लगाए। वर्तमान standalone source packages
फिर build होंगे; stable public binary union ABI घोषित नहीं होगा। Generated native
apps को JVM/.NET की आवश्यकता नहीं होगी। LLVM SDK को केवल metadata change के कारण
बदलना आवश्यक नहीं मानें; नए compiled runtime के साथ वास्तविक compatibility जाँचें।

Existing C/JNI export validator union, Result अथवा उन्हें समेटे unsupported export
types को स्पष्ट रूप से अस्वीकार करे। Internal functions ऐसे values उपयोग कर सकें।
पहले से supported scalars/text/handles के explicit wrappers ही सीमा पार जाएँ।
Pointer ownership, presence flag, serialized tag या Kotlin sealed hierarchy अपने
आप न बनाएँ। Generated Kotlin/JNI union bindings अलग versioned design होंगी।

## १९. पुराने स्रोत और संकुल (Compatibility and evolution)

- Numeric `परिगणना` के codes, `नाम`/`अंक`, casts, equality और `चयन` labels यथावत।
- `चयन` का non-exhaustive statement, grouped labels और default व्यवहार यथावत।
- Record declaration/construction syntax और existing class/interface subtyping यथावत।
- पुराने HTTP/database/ORM APIs के nullable returns, typed getters और exceptions यथावत।
- `कार्यफल` की सफलता/fault/cancellation और resource cleanup यथावत।
- Null-safety default legacy तथा compilation-wide dependency profile नियम यथावत।
- Source lockfile format न बदलें; exact Git/local dependencies सामान्य restore/verify से आएँ।

बन्द family में नया विकल्प जोड़ना source consumers के exhaustive matches को
recompile करते समय त्रुटि दे सकता है। यह जानबूझकर उपयोगी compatibility signal है।
Payload order/type/name बदलना भी API change है। Library लेखक version/release notes
और pinned dependency update में इसे स्पष्ट करें। `शेष` रखने से future alternatives
स्वतः catch हो सकती हैं; ऐसा fallback जानबूझकर चुनें, ताकि नई अवस्था अनजाने न छिपे।

JSON/wire/database representation अलग explicit mapping से version हो। Numeric
case ordinal serialize न करें। SQL NULL, not-found और domain failure को एक-दूसरे
में implicit रूप से न बदलें।

## २०. संपादक और निदान (Editor and diagnostics)

Completion case factories और payload parameters दिखाए; missing-cases Quick Fix
सही owner names के साथ placeholders जोड़े, चुपचाप runtime fallback न बनाए। Hover
family/active pattern binding का प्रकार बताए। F2/references declaration, case
construction, patterns और payload bindings को scope सहित समझें; `_` binding न हो।

प्रस्तावित diagnostic श्रेणियाँ नीचे हैं। अंतिम BH codes implementation से पहले
catalog में उपलब्ध स्थान देखकर आरक्षित हों; इस प्रस्ताव में कोई existing code
पुनः उपयोग नहीं किया गया है।

| श्रेणी | उदाहरण |
| --- | --- |
| घोषणा/पहचान | दोहरा case, inaccessible payload type, family के बाहर variant |
| निर्माण | गलत arity/type, unresolved generic arguments |
| pattern | गलत family, field count, दोहरा binder, nullable type pattern |
| पूर्णता | छूटा variant/null; bounded-analysis limit |
| पहुँच | पूरी तरह unreachable arm, दोहरा catch-all |
| result type | असंगत arm results या `शून्य` generic argument |
| host/boundary | unsupported C/JNI export, notebook identity change |

Messages में हिन्दी कारण, मूल file/cell और pattern/arm का span हो। Supplementary
Unicode और NFC-equivalent नामों के बाद CLI तथा LSP UTF-16 positions जाँचे जाएँ।
Debugger active case और payload को read-only memory inspection से दिखाए। नया
Watch grammar बाद में आ सकता है; वर्तमान Watch को नया unsupported layout गलत
पढ़ने देने के बजाय स्पष्ट unsupported diagnostic दें।

## २१. परीक्षण और स्वीकृति (Validation and release gates)

| क्षेत्र | आवश्यक प्रमाण |
| --- | --- |
| Frontend | contextual-name compatibility, aliases, NFC/joiners, parser recovery और source spans |
| Types | payload arity, visibility, generics/constraints/invariance, recursive types और casts |
| Coverage | missing/guarded/unreachable arms, null, bool, enums, nested products और complexity limits |
| Evaluation | selector/arguments/guards एक बार; source order; skipped arms में कोई call/await नहीं |
| Values | exact-type universal equality, nested unions/records, NaN fields, shared deep graphs, map/set keys |
| Async/cleanup | awaited guard/arm, captured bindings, cancellation, throwing cleanup और return replacement |
| Native GC | हर allocation पर collection, partial construction, guard failure, static/collection/task roots |
| Packaging | एक ही feature वाला CLI JAR, VSIX, kernel और regenerated browser host |

एक साझा golden corpus JVM, CLR और native में output, exit code तथा diagnostic code/span
compare करे। Compile-fail tests में executable न बने। दोनों browser publishing paths
में actual execution, isolation, Stop/cleanup और restart जाँचे जाएँ।

Native tests में scalar bits को reference न trace करने का स्वतंत्र descriptor test,
reference fields के actual offsets, nullable boxes, cyclic mutable references और
गहरी shared immutable structures हों। Full-GC के बाद unreachable objects वापस आएँ;
स्थायी roots वाले fixtures में bounded retention और shutdown पर अनुमत baseline जाँचे।
Linux में उपलब्ध ASan/UBSan तथा debug/optimized LLVM execution चलें।

Windows x64 और Linux native validation संबंधित change पर चलें। Apple Silicon
tests मौजूदा **weekly workflow** में रहें; feature release से पहले उसे manually
चलाकर proof लें, नया per-PR macOS native trigger न जोड़ें। Full real-client Jupyter
smoke Linux, Windows और macOS में हो, cross-cell declaration/value/match तथा kernel
restart सहित। Android में existing bindings/regression tests और unsupported union
export tests हों; कोई नया Kotlin union ABI घोषित न हो।

Compatibility corpus पुराने `परिगणना`, `चयन`, records, nullable legacy code और
`मिलान`/`विकल्पसमूह` नाम वाले मौजूदा identifiers को बिना बदलाव compile करे।
प्रकाशन से पहले Hindi tutorial, API reference, book और package documentation update
हों। Documentation-only proposal पर compiler/native suites चलाना आवश्यक नहीं है।

## २२. कार्यान्वयन क्रम और समीक्षा सीमा (Delivery plan and review boundary)

1. **स्वीकृत अनुबंध:** शब्द, factory/pattern syntax, equality, null domain, Result/Unit
   तथा native layout/ABI निर्णय की समीक्षा। यही वर्तमान कार्य है।
2. **Family core:** resolver/specialization, construction, nominal casts,
   equality/hash और सभी targets पर exact GC layouts; अभी public release नहीं।
3. **Matching:** साझा checked plan, nested patterns, coverage, async/cleanup और
   source diagnostics; statement/expression parity तथा cross-target corpus।
4. **Library और tools:** Result/Unit catalog, editor/Jupyter/debug inspection,
   runnable Hindi examples और explicit HTTP/database adapter demo।
5. **Release gates:** forced-GC/clean-host/browser/package tests, weekly Mac proof,
   user documentation और अलग से अधिकृत package/deployment release।

पहले पूर्ण milestone में ऊपर की core syntax और Result उपलब्ध हों; list/property
patterns, custom extractors, union methods, implicit error propagation, ABI exports,
automatic serialization/ORM mapping और allocation-elision optimizations बाद की scope हैं।

**समीक्षा के लिए विशेष प्रश्न:** क्या `विकल्पसमूह`/`मिलान` हिन्दी शब्द उपयुक्त हैं?
क्या अलग `मिलान` और पुराने `चयन` को यथावत रखना स्वीकार्य है? क्या exhaustive
statements, structural equality, explicit Result handling और प्रति-case heap allocation
के लाभ/लागत स्वीकार्य हैं? क्या प्रथम release में सीधे C/JNI union exports न होना
स्वीकार्य है? स्वीकृति के बाद ही compiler/runtime/editor implementation आरम्भ होगा।

## २३. आधार अनुबंध (Repository references)

- [भाषा विनिर्देश](language-spec.md) और [प्रकार/रूपांतरण](types-conversions.md)
- [रिक्त-सुरक्षा का डिज़ाइन और implemented decisions](null-safety-design.md)
- [सुरक्षित दैनिक प्रोग्रामिंग का हिन्दी ट्यूटोरियल](../tutorial/safer-everyday/README.md)
- [सामान्य प्रकार/अनुबंध](contracts-generics.md) और [native specialization](native-generics.md)
- [Native records और equality/GC अनुबंध](native-records.md)
- [Native selection/control flow](native-control-flow.md) और [exception root cleanup](native-exceptions.md)
- [Task core और suspended frames](task-core-implementation.md)
- [Native debugger inspection](native-vscode-debug.md) और [Jupyter design](jupyter-notebook-design.md)
- [वर्तमान native runtime header](../src/main/resources/dev/bhash/backend/native/bhash_runtime.h)
- [वर्तमान ANTLR grammar](../src/main/antlr4/Bhash.g4)
