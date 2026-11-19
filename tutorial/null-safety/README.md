# रिक्त-सुरक्षा (Null safety)

रिक्त-सुरक्षा (Null safety) में किसी मान की अनुपस्थिति उसके प्रकार का हिस्सा है।
`छात्र` में वास्तविक छात्र होना आवश्यक है; `छात्र?` में छात्र अथवा `रिक्त` हो सकता है।
इससे अनुपस्थित छात्र के सदस्य पढ़ने की गलती क्रमानुदेश चलाने से पहले पकड़ में आती है।
[स्वीकृत डिज़ाइन](../../docs/null-safety-design.md) निर्णयों का आधार बताता है।

## सुविधा चालू करना (Strict profile)

परियोजना की `bhash.project` में लिखें:

```properties
name=सुरक्षितअनुप्रयोग
sources=src
nullSafety=strict
```

एक फ़ाइल, Playground के Full Program अथवा Jupyter के पहले cell के आरम्भ में
`// bhash:null-safety=strict` लिख सकते हैं। Jupyter में यह आगे के cells के लिए भी
लागू रहता है; पहले से bindings हों तो kernel restart करके शुरुआत में इसे चुनें।
परियोजना में सभी source dependencies का profile समान होना चाहिए। अलग profile
के package को पहले migrate करें और dependency lock दोबारा बनाएँ। मौजूदा परियोजनाएँ
बिना opt-in के `legacy` व्यवहार रखती हैं।

CLI से एक build के लिए `--null-safety strict` भी चुन सकते हैं। फ़ाइल में लिखा
strict directive सुरक्षा चालू रखता है; `--null-safety legacy` भी उस directive को बंद नहीं करता। उदाहरण चलाएँ:

```sh
java -jar bhash-compiler.jar run tutorial/null-safety/null-safety.bhash --target jvm
java -jar bhash-compiler.jar run tutorial/null-safety/null-safety.bhash --target clr
java -jar bhash-compiler.jar run tutorial/null-safety/null-safety.bhash --target native
java -jar bhash-compiler.jar compile tutorial/null-safety/null-safety.bhash --target wasm-browser -o build/null-safety-web
```

Browser output को HTTP पर serve करके खोलें। JVM को Java, CLR/browser build को
.NET और native build को अपने लक्ष्य का toolchain चाहिए। डाउनलोड किए गए पुराने
compiler/extension/kernel में यह सुविधा तब मिलेगी जब नया package प्रकाशित हो।

## वैकल्पिक प्रकार (Nullable types)

```bhash
स्थिर नाम: पाठ = "सीमा";
मान उपनाम: पाठ? = रिक्त;
स्थिर शून्यअंक: पूर्णांक? = ०;
स्थिर अनुपस्थितअंक: पूर्णांक? = रिक्त;
```

अंतिम दो मान अलग हैं। `०`, `असत्य` और `०.०` अनुपस्थिति के संकेत नहीं हैं।
`पूर्णांक?`, `दशमलव?`, `तार्किक?`, `बाइट?` और `लघुपूर्णांक?` भी समर्थित हैं।
`शून्य?` मान्य प्रकार नहीं है।
वैकल्पिक पाठ की नई array entries `रिक्त` होती हैं; सामान्य पाठ entries का
मौजूदा डिफ़ॉल्ट `""` है।

`छात्र?[]` वास्तविक array है जिसमें छात्र अथवा रिक्त entries हो सकती हैं।
`छात्र[]?` वैकल्पिक array है जिसके मौजूद होने पर entries वास्तविक छात्र हैं।
`(फलन()->पूर्णांक)?` वैकल्पिक callback है; `फलन()->पूर्णांक?` वास्तविक callback
है जो वैकल्पिक पूर्णांक लौटाता है।

## जाँच और प्रकार का संकुचन (Flow narrowing)

```bhash
फलन अगला(अंक: पूर्णांक?) -> पूर्णांक {
    यदि (अंक == रिक्त) { लौटाओ ०; }
    लौटाओ अंक + १;
}
```

जल्दी लौटने वाला पथ अनुपस्थित मान सँभालता है। शेष पथ में compiler जानता है कि
`अंक` मौजूद है। `!= रिक्त` की शाखा, `&&` के दाएँ भाग और `||` की असत्य शाखा में
भी उचित जाँच लागू होती है। बंधन को फिर लिखने पर पुरानी जानकारी अमान्य हो जाती है।
Nonnull initializer अथवा assignment भी स्थानीय बंधन को मौजूद साबित करता है।
Loops, exception handlers और `प्रतीक्षा` पर mutable proofs के प्रति compiler
सावधान रहता है। Mutable captured variables और बदल सकने वाले fields को बार-बार
पढ़कर proof न बनाएँ; पहले स्थानीय `स्थिर` binding में snapshot लें और उसे जाँचें।

## सुरक्षित सदस्य और विकल्प (Safe access and coalescing)

```bhash
स्थिर नाम: पाठ = छात्रवस्तु?.नाम ?? "अज्ञात";
```

`?.` प्राप्तकर्ता एक बार पढ़ता है। वह अनुपस्थित हो तो सदस्य अथवा method arguments
का मूल्यांकन नहीं होता। परिणाम वैकल्पिक होता है। `??` केवल बायाँ मान अनुपस्थित
होने पर दायाँ expression चलाता है। यह दाएँ से जुड़ता है: `अ ?? ब ?? स` का अर्थ
`अ ?? (ब ?? स)` है। वैकल्पिक प्राप्तकर्ता पर अगली स्वतंत्र पहुँच के लिए फिर
`?.` लिखें।

यदि दाएँ expression अथवा method argument में `प्रतीक्षा` है तो वह भी केवल
चुनी हुई शाखा में चलती है। उदाहरण के लिए `अंक ?? प्रतीक्षा अगलामान()` में मौजूद
अंक मिलने पर न अगलामान बुलता है, न उस छोड़ी गई शाखा की वजह से suspension होता है।

## संग्रह और निर्माता (Collections and initialization)

`सूची<छात्र>` और `सूची<छात्र?>` अलग invariant प्रकार हैं। Insert, assignment और
generic calls nullability बनाए रखते हैं। केवल `रिक्त` से type inference नहीं होता;
स्पष्ट प्रकार लिखें। Non-null class/interface constraints में nullable argument
नहीं दे सकते। केवल nullability बदलकर overload नहीं बनाएँ।

संदर्भ fields को हर सफल constructor path में भरें। `फेंको` से विफल constructor
को सफल initialization नहीं माना जाता। Non-null static reference को initializer
दें। Numeric, boolean और पाठ के मौजूदा zero/empty defaults बने रहते हैं।

```bhash
स्थिर खालीस्थान: छात्र?[] = नया छात्र?[१०];
स्थिर तैयार: छात्र[] = नया छात्र[]{नया छात्र("सीमा")};
```

`नया छात्र[१०]` से दस non-null छात्रों का वादा पूरा नहीं होता, इसलिए strict mode
इसे अस्वीकार करता है। Nullable entries चुनें अथवा पूर्ण initializer दें।

## परिवर्तन, JSON और डेटाबेस (Casts and missing values)

`सुरक्षितरूप<पूर्णांक>(मूल)` का strict परिणाम `पूर्णांक?` है। गलत runtime type पर
रिक्त मिलता है। `परिवर्तित<पूर्णांक>(मूल)` checked conversion है; गलत type अथवा
रिक्त को non-null लक्ष्य में बदलने पर अपवाद मिलता है। Null-forgiving `!` नहीं है;
`!` का तार्किक अर्थ पूर्ववत है।

JSON `null` वैकल्पिक `सर्ववस्तु?` है। SQL में `रिक्तहै()` से NULL जाँचें और typed
getter केवल उपयुक्त स्थिति में बुलाएँ; getters, transactions और ORM की मौजूदा
NULL/error policy नहीं बदली जाती। Map में अनुपस्थित key तथा invalid JSON अपने
मौजूदा अनुबंध रखते हैं; उन्हें हर जगह चुपचाप रिक्त नहीं बनाया गया है।
मानचित्र की कुंजी और समुच्चय का सदस्य `रिक्त` नहीं हो सकता; यह मौजूदा runtime
validation वैकल्पिक generic argument लिखने पर भी लागू रहती है।

सामान्य निदान: `BH3201` पर रिक्त-जाँच चाहिए, `BH3202` पर non-null प्रकार में रिक्त
रखा जा रहा है, और `BH3203` पर non-null field/array initialization अधूरा है।

## GC और विदेशी सीमा (Runtime representation and boundary)

Nullable scalar मौजूद होने पर मौजूदा typed `सर्ववस्तु` box में रहता है और अनुपस्थित
होने पर null pointer होता है। इसलिए underlying scalar की पहचान तथा GC roots दोनों
बचते हैं। Nullable references मौजूदा reference representation रखते हैं। Async frames,
closures और collection slots में यही नियम लागू है। अलग sentinel संख्या नहीं है।

वर्तमान C/JNI export ABI nullable signatures प्रकाशित नहीं करता। Compiler उन्हें
अस्वीकार करता है; वैकल्पिक मान को foreign API में भेजने से पहले explicit status और
non-null payload वाला wrapper बनाएँ। भविष्य का nullable export ABI presence और
ownership के स्पष्ट versioned अनुबंध के साथ जोड़ा जाएगा।

पूरा [चलने योग्य उदाहरण](null-safety.bhash) और [अपेक्षित output](null-safety.expected.txt)
देखें। परीक्षणों में JVM/CLR/native parity, native forced GC, async lowering तथा
browser और notebook smoke coverage शामिल हैं।
