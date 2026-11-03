# फलन मान, लैम्ब्डा और संवृतियाँ (Function values, lambdas and closures)

अब किसी फलन को चर में रख सकते हैं, दूसरे फलन को तर्क के रूप में दे सकते हैं और परिणाम के रूप में लौटा सकते हैं। इससे हर छोटे callback के लिए अलग अनुबंध और वर्ग लिखना आवश्यक नहीं रहता। यह सुविधा JVM, .NET और native targets के साझा प्रकार-जाँच और lowering से लागू होती है। यह अपने आप asynchronous execution या नया thread शुरू नहीं करती।

## फलन का प्रकार (Function type)

`फलन(पूर्णांक, पाठ) -> तार्किक` दो तर्क लेने और तार्किक परिणाम लौटाने वाला प्रकार है। प्रकार में तर्कों के नाम नहीं लिखते। `फलन() -> शून्य` कोई तर्क नहीं लेता और कोई परिणाम नहीं लौटाता। तर्कों का क्रम, उनकी संख्या और परिणाम का प्रकार हस्ताक्षर का हिस्सा हैं। समान हस्ताक्षर अलग स्थानों पर लिखने पर भी समान प्रकार होते हैं; प्रकारों के बीच स्वतः variance conversion नहीं होता।

```bhash
फलन दुगुना(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }
फलन मुख्य() -> शून्य {
    स्थिर क: फलन(पूर्णांक) -> पूर्णांक = दुगुना;
    लिखो(क(५));
}
```

`दुगुना` उसका मान है; `दुगुना(५)` उसे तत्काल बुलाता है। यहाँ `क(५)` का परिणाम `१०` है। गलत तर्क संख्या, गलत प्रकार या गलत परिणाम को compiler अस्वीकार करता है।

## अनाम फलन (Lambda)

लैम्ब्डा नाम दिए बिना फलन बनाता है। इस संस्करण में हर parameter का प्रकार और परिणाम का प्रकार स्पष्ट लिखें। शरीर सामान्य block है; परिणाम के लिए `लौटाओ` लिखें।

```bhash
स्थिर बढ़ाओ: फलन(पूर्णांक) -> पूर्णांक =
    फलन(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक + ३; };
लिखो(बढ़ाओ(५));
```

यह snippet `मुख्य` के शरीर में रखें। परिणाम `८` है। parameter सामान्य फलन की तरह अपरिवर्तनीय होता है। किसी parameter को बदलना हो तो उससे अलग `मान` चर बनाएँ।

## प्रतिपुष्टि और उच्चतर फलन (Callback and higher-order function)

```bhash
फलन लगाओ(क: फलन(पूर्णांक) -> पूर्णांक, अंक: पूर्णांक) -> पूर्णांक {
    लौटाओ क(अंक);
}
```

`लगाओ` को `दुगुना` या उपयुक्त लैम्ब्डा दिया जा सकता है। callback सामान्य call की तरह उसी execution flow में चलता है। अपवाद caller तक सामान्य `प्रयास` / `पकड़ो` / `अंततः` नियमों से पहुँचते हैं। पुराने callback अनुबंधों में अपने आप conversion नहीं होता; वहाँ छोटा adapter वर्ग लिखें।

## परिवेश को जीवित रखना (Closure and capture)

```bhash
फलन गिनकबनाओ(आरंभ: पूर्णांक) -> फलन() -> पूर्णांक {
    मान गिनती: पूर्णांक = आरंभ;
    लौटाओ फलन() -> पूर्णांक {
        गिनती = गिनती + १;
        लौटाओ गिनती;
    };
}
```

संवृति अपने शरीर में प्रयुक्त बाहरी स्थानीय bindings को याद रखती है। `गिनकबनाओ` लौटने के बाद भी `गिनती` जीवित है। एक ही binding को पकड़ने वाले दो callbacks उसी परिवर्तनशील storage को साझा करते हैं; बाहर किया गया परिवर्तन अंदर दिखता है और अंदर किया गया परिवर्तन बाहर। अलग-अलग `गिनकबनाओ` calls की गिनती स्वतंत्र होती है।

`स्थिर` और parameters का मान capture होता है। यदि वह object reference है तो object की प्रतिलिपि नहीं बनती: उसी object का reference मिलता है। `स्थिर` binding बदल नहीं सकती, लेकिन object के परिवर्तनशील fields बदल सकते हैं। Nested closures बाहरी captures को सुरक्षित रखती हैं। `यह` और enclosing class के members का प्रयोग भी संभव है; private/protected access enclosing class के नियमों के अनुसार रहता है।

## संग्रह और लूप (Collections and loop bindings)

Callbacks को सूची, map, array या object field में रख सकते हैं। array प्रकार में पूरे फलन प्रकार को कोष्ठकों में रखें: `(फलन() -> पूर्णांक)[]`। `फलन() -> पूर्णांक[]` का अर्थ पूर्णांक-array लौटाने वाला फलन है।

`प्रत्येक` के हर iteration में अलग, अपरिवर्तनीय binding होती है। उदाहरण में `अंक` को पकड़ने वाले callbacks क्रमशः `१`, `४`, `९` देंगे। लूप के बाहर घोषित एक परिवर्तनशील चर को सभी callbacks पकड़ें तो वे उसी एक binding का बाद का मान देखते हैं।

## सफाई और त्रुटियाँ (Lifetime and errors)

Native collector captures, साझा परिवर्तनशील cells, nested environments और cycles को trace करता है। उपयोगकर्ता को allocation/free नहीं करना होता। कोई callback reachable हो तो उसके captures भी जीवित रहते हैं; सारे references हटने पर cyclic closures भी एकत्र की जा सकती हैं। Captured file/database resource की object lifetime बढ़ सकती है, लेकिन `संसाधन` block से बाहर निकलने पर उसका `बंदकरो()` फिर भी चलता है। बंद resource को बाद में callback से उपयोग न करें।

`रिक्त` function value को बुलाना सामान्य null-reference error है। `स्थिर` capture को पुनः assign करना compile-time error है। अलग हस्ताक्षर का फलन assign करना भी error है। Overloaded named functions के लिए स्पष्ट typed lambda से सही overload बुलाएँ। Generic global function का specialization, जैसे `पहचान<पाठ>`, value बनाया जा सकता है। Bound method references का अलग syntax नहीं है: `फलन()->पूर्णांक { लौटाओ वस्तु.गिनो(); }` लिखें। लैम्ब्डा के अंदर सीधे `आधार` dispatch के लिए enclosing class में helper method रखें और उसे बुलाएँ।

## पूरा उदाहरण चलाना (Running the example)

[callbacks.bhash](callbacks.bhash) पूरा क्रमानुदेश है; किसी आयात की आवश्यकता नहीं है। repository root से चलाएँ:

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/functions/callbacks.bhash --target jvm
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/functions/callbacks.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run tutorial/functions/callbacks.bhash --target native
```

Native के लिए समर्थित SDK/toolchain उपलब्ध होना चाहिए। अपेक्षित परिणाम [callbacks.expected.txt](callbacks.expected.txt) में है। इन उदाहरणों में asynchronous scheduling, parallelism और thread safety का दावा नहीं है; वे अलग milestone हैं।
