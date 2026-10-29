# मूल-मशीन लघुपूर्णांक (Native Signed 32-bit Integer)

Linux x86-64 / LLVM 18 पर `लघुपूर्णांक` उपलब्ध है। उसकी inclusive range
**−2,147,483,648 से 2,147,483,647** है। यह `पूर्णांक` (signed 64-bit) और `बाइट`
(unsigned 8-bit) से अलग logical type है। Generated executables को JVM/.NET नहीं चाहिए।
[पूरा GC उदाहरण](../examples/native/int32.bhash) देखें।

## परिवर्तन (Conversions)

छोटे integer types के लिए explicit conversion आवश्यक है। Integer literals अभी भी
`पूर्णांक` हैं; `स्थिर क: लघुपूर्णांक = १;` पर existing shared type error मिलता है।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर छोटा: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>("-२१४७४८३६४८");
    स्थिर बड़ा: पूर्णांक = परिवर्तित<पूर्णांक>(छोटा);
    लिखो(छोटा,बड़ा,परिवर्तित<पाठ>(छोटा));
    लिखो(परिवर्तित<लघुपूर्णांक>(३.९),परिवर्तित<लघुपूर्णांक>(-०.९));
}
```

```text
-२१४७४८३६४८ -२१४७४८३६४८ -2147483648
३ ०
```

`पूर्णांक`/`बाइट` से narrowing range-check करती है। `लघुपूर्णांक` से `पूर्णांक` widening
सभी values के लिए सुरक्षित है; `बाइट` में conversion तभी मान्य है जब value 0–255 हो।
`लघुपूर्णांक` से `दशमलव` conversion exact है, क्योंकि binary64 में पूरे signed 32-bit range
के integers represent हो सकते हैं। Text conversion ASCII digits देता है; सीधे `लिखो`
करने पर सामान्य Devanagari display मिलता है।

Decimal पहले signed 64-bit integer में **zero की ओर truncate** होता है, फिर 32-bit range
check होती है। इसलिए `2147483647.9` तथा `-2147483648.9` स्वीकार हैं। NaN, infinity और
signed 64-bit सीमा से बाहर decimal पर पहले existing numeric error मिलता है।

Text पहले existing integer grammar और 64-bit parser से गुजरता है, फिर narrow होता है।
Optional ASCII sign और एक ही digit script (ASCII या Devanagari) स्वीकार हैं। Whitespace,
decimal points और mixed scripts स्वीकार नहीं हैं। बहुत बड़े digits के बाद invalid suffix
होने पर syntax error को overflow से पहले report किया जाता है, जैसा JVM/CLR में है।

| परिस्थिति | Catchable error |
|---|---|
| 64-bit integer मान 32-bit range से बाहर | `लघुपूर्णांक परिवर्तन सीमा से बाहर है।` |
| पहले decimal-to-64-bit conversion असफल | `संख्यात्मक परिवर्तन सीमा से बाहर है।` |
| Text integer grammar गलत | `अमान्य पूर्णांक पाठ।` |
| Valid integer text signed 64-bit range से बाहर | `पूर्णांक 64-बिट सीमा से बाहर है।` |
| छोटे integer को 0–255 के बाहर byte बनाना | `बाइट परिवर्तन सीमा से बाहर है।` |

## गणना और चयन (Promotion and Selection)

Arithmetic, bitwise और unary numeric operations छोटे integers को `पूर्णांक` में promote
करते हैं। `2147483647 + 1` का परिणाम `2147483648` है; यह 32-bit wrap नहीं है। परिणाम को
वापस `लघुपूर्णांक` में रखने के लिए explicit checked conversion चाहिए। Shift distances का
64-bit `count & 63` नियम लागू है। Function parameters और returns अपने declared types
रखते हैं; overload resolution भी `लघुपूर्णांक` और `पूर्णांक` में अंतर करता है।

`चयन` में signed 32-bit selector तथा उसी range के constant labels स्वीकार हैं। Range से
बाहर label compile-time error है। Existing grouped-label, default और scope rules लागू हैं।

## सर्ववस्तु और संग्रह (Boxing, Unboxing and Collections)

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर छोटा: सर्ववस्तु = परिवर्तित<लघुपूर्णांक>(२);
    स्थिर बड़ा: सर्ववस्तु = २;
    लिखो(प्रकारहै<लघुपूर्णांक>(छोटा),प्रकारहै<पूर्णांक>(छोटा),छोटा==बड़ा);
    लिखो(परिवर्तित<लघुपूर्णांक>(छोटा));
}
```

```text
सत्य असत्य असत्य
२
```

Boxed `लघुपूर्णांक`, `पूर्णांक` और `बाइट` अलग runtime types हैं। समान value वाले दो
boxed छोटे integers value-equal हैं, पर छोटे integer और 64-bit integer के boxes बराबर
नहीं हैं। यह distinction universal map keys में भी सुरक्षित है। Unboxing exact type माँगता
है; `परिवर्तित<पूर्णांक>(छोटा)` पर `रनटाइम प्रकार परिवर्तन असफल हुआ।` मिलता है। पहले
unbox करके फिर numeric conversion कर सकते हैं। Null का primitive unboxing भी fail होता है।

Fields, constructors, virtual/interface methods, parameters, returns, arrays, jagged arrays,
lists, map keys/values और snapshots यह type सुरक्षित रखते हैं। Default fields/array elements
zero हैं। `सूची<लघुपूर्णांक>` और `सूची<पूर्णांक>` अलग collection types हैं। JSON writing
छोटे integers और उनके boxes को integer numbers के रूप में लिखता है। JSON parsing का
existing numeric-type contract नहीं बदलता; parsed numbers अपने पुराने types रखते हैं।

## स्मृति और जाँच (Memory Layout and Validation)

Private ABI v11 में scalar kind 8 और boxed membership ID −8 reserved हैं। वर्तमान native
ABI registers, fields और scalar collection elements में eight-byte carriers रखता है; छोटा
integer sign-extended रहता है और उसकी logical value 32-bit range में होती है। यह packed
चार-byte array/field storage का वादा नहीं है। Layout optimization आगे अलग milestone हो सकती है।

छोटे integer payloads scalar हैं; collector उन्हें references की तरह trace नहीं करता। Boxes,
reference-valued maps और universal containers existing precise GC metadata का उपयोग करते हैं।
Returned boxes allocating `अंततः` के दौरान सुरक्षित रहते हैं। C runtime checks range failures,
boxed-key identity, JSON output, map growth और snapshots पर forced collection करते हैं।

Shared JVM/CLR/native fixtures signed boundaries, fractional truncation, malformed inputs,
promotion, type identity, fields, calls, selection और typed collections जाँचते हैं। Example
300 retained nodes और 1,000 cyclic-container/wrong-unboxing iterations चलाता है। Linux CI
LLVM verification, ASan/UBSan और JVM/.NET के बिना clean-container execution जाँचता है।
