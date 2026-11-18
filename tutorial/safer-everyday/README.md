# सुरक्षित दैनिक प्रोग्रामिंग (Safer Everyday Programming)

एक व्यक्ति का नाम बनाते समय भरें, बाद में बदलने न दें, और अनावश्यक प्रकार दोहराए
बिना उस व्यक्ति के साथ काम करें। [पूरा उदाहरण](../../examples/safer-everyday.bhash)
JVM, .NET, native और browser में एक ही अर्थ रखता है।

## निर्माता में अचल क्षेत्र (Constructor-assigned Immutable Fields)

```bhash
वर्ग व्यक्ति {
    निजी स्थिर नामपाठ: पाठ;
    निर्माता(नाम: पाठ) { यह.नामपाठ = नाम; }
    फलन नाम() -> पाठ { लौटाओ नामपाठ; }
}
```

`स्थिर` क्षेत्र को घोषणा में आरंभक दे सकते हैं, या अपने वर्ग के प्रत्येक निर्माता
में भर सकते हैं। Compiler हर सफल रास्ते पर ठीक एक assignment माँगता है। पहले
पढ़ना, दूसरी बार लिखना, दूसरे object का अचल क्षेत्र लिखना और विधि से बाद में
बदलना त्रुटि है। विरासत के क्षेत्र को उसका अपना मूल निर्माता भरेगा।

दोनों `यदि` branches में अलग आरंभक देना मान्य है। पहले `फेंको` से समाप्त रास्ते
को initialization की आवश्यकता नहीं है। `लौटाओ` से सफलतापूर्वक constructor छोड़ने
वाले रास्ते को आवश्यकता है। `अंततः` के बाद यह जाँच होती है। Loops में assignment
तथा try/catch में संभव दूसरी assignment पर जाँच जानबूझकर conservative है।
साझा अचल क्षेत्रों के लिए घोषणा का आरंभक अभी भी आवश्यक है।

## स्थानीय प्रकार-अनुमान (Local Type Inference)

```bhash
स्थिर नाम = "सीमा";       // पाठ
मान गिनती = १;           // पूर्णांक
स्थिर अंक = नया सूची<पूर्णांक>{१,२,३};
```

प्रकार आरंभक से **संकलन के समय** तय होता है। `गिनती = "दो";` फिर भी त्रुटि है।
`स्थिर` और `मान` की mutability नहीं बदलती। Public signatures, मापदंड, परिणाम,
क्षेत्र और foreach bindings में स्पष्ट प्रकार लिखते रहें। `स्थिर व = रिक्त;` से
प्रकार नहीं निकाला जाता; संदर्भ का स्पष्ट प्रकार दें। Lambdas के मापदंड और परिणाम
भी अभी स्पष्ट रहते हैं।

## सामान्य विधियाँ और अनुमान (Generic Methods and Call Inference)

```bhash
वर्ग सहायक {
    फलन पहचान<त>(मूल्य: त) -> त { लौटाओ मूल्य; }
}
```

इसे `सहायकवस्तु.पहचान(१)` या `सहायकवस्तु.पहचान<पूर्णांक>(१)` से बुलाएँ। वर्ग,
साझा विधि और अनुबंध की विधि अपने प्रकार मापदंड रख सकती है। Generic class के
मापदंड और उसकी विधि के मापदंड के नाम अलग हों। Virtual/interface dispatch concrete
specializations पर काम करता है; `अधिलेखन` के पुराने नियम बने रहते हैं।

Global generic functions में भी `पहचान(१)` लिखा जा सकता है। अनुमान arguments
से होता है, अपेक्षित return type से नहीं। `सूची<त>` और `त[]` जैसी संरचनाओं में
भी प्रकार मिलाए जाते हैं। परस्पर विरोधी arguments या केवल `रिक्त` से अनिश्चित
प्रकार पर explicit arguments या typed lambda दें। Generic classes के type
arguments अभी स्पष्ट रहते हैं: `नया पात्र<पाठ>()`।

## प्रकार की सीमाएँ (Generic Constraints)

```bhash
अनुबंध नामित { फलन नाम() -> पाठ; }
फलन नामलो<त: नामित>(वस्तु: त) -> पाठ {
    लौटाओ वस्तु.नाम();
}
```

`नामलो(छात्र)` तभी स्वीकार होगा जब छात्र का प्रकार `नामित` का पालन करता हो।
समान नाम की विधि होना मात्र पर्याप्त नहीं है: यह nominal contract है। कई सीमाएँ
`<त: नामित & बंदनीय>` से जोड़ें। Generic classes पर भी यही सीमा लिख सकते हैं।
Calls और closed types बनाते समय सीमाएँ जाँची जाती हैं। मौजूदा specialization
model में generic body हर उपयोग किए गए concrete प्रकार के लिए जाँची जाती है;
unused template body की पूर्ण definition-site checking इस milestone में नहीं है।
Variance, wildcards तथा default type arguments अभी शामिल नहीं हैं।

## नाम बदलना और उपयोग ढूँढना (Rename and Find References)

VS Code में नाम पर **F2** से rename और **Shift+F12** से references खोलें। Rename
केवल वर्तमान परियोजना में Workspace Edit प्रस्तावित करता है; फ़ाइल स्वयं नहीं
लिखता। खुले unsaved buffers की version edits में रहती है। Comments, string
literals, समान नाम के असंबंधित locals और import के package हिस्से नहीं बदलते।

Import alias का नाम अलग से बदल सकते हैं। किसी type का नाम बदलने पर alias को
ज्यों का त्यों रखते हुए असली import target बदलता है। Function overload group तथा
संबंधित interface/override method-name family साथ बदली जाती है। नया नाम किसी अन्य
binding को capture करे तो परिवर्तन अस्वीकार होता है, भले बदला code compile हो।
Dependency cache और दूसरी परियोजना की फ़ाइलें नहीं बदली जातीं। पहले परियोजना की
त्रुटियाँ ठीक करें; अधूरे/अस्पष्ट code पर सुरक्षित rename उपलब्ध न हो सकता है।

## उदाहरण चलाएँ (Run the Example)

```sh
java -jar bhash-compiler.jar run examples/safer-everyday.bhash --target jvm
java -jar bhash-compiler.jar run examples/safer-everyday.bhash --target clr
java -jar bhash-compiler.jar run examples/safer-everyday.bhash --target native
```

Browser output के लिए `compile ... --target wasm-browser -o target/safer-web`
करें और [browser guide](../../docs/wasm-browser.md) के अनुसार serve करें। Playground
में deployment update के बाद पूरा उदाहरण Full Program mode में चल सकता है।

```text
सीमा २
नमस्ते, भारत!
```

Scalar notebook bindings का inferred प्रकार भी पिछले explicit scalar bindings की
तरह cells के बीच रहता है। Object bindings की मौजूदा notebook lifetime सीमा नहीं
बदलती। स्रोत क्रम, early return और block scope के नियम वही हैं।

अगला अलग प्रस्ताव [रिक्त-सुरक्षा का design](../../docs/null-safety-design.md) है।
उसमें दिखाए `T?`, `?.` और `??` अभी लागू नहीं हैं।
