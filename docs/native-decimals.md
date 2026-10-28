# मूल-मशीन दशमलव (Native binary64)

प्रायोगिक Linux x86-64 / LLVM 18 लक्ष्य अब `दशमलव` को सीधे मशीन के IEEE 754
binary64 मान के रूप में संकलित करता है। अनुप्रयोग को JVM या .NET की आवश्यकता नहीं
है। Java और LLVM केवल compiler/build के लिए आवश्यक हैं।

## संख्या और गणना (Numbers and arithmetic)

`दशमलव` ठीक वही binary floating-point प्रकार है जो अन्य लक्ष्यों पर है। यह exact
base-10 decimal प्रकार नहीं है: उदाहरण के लिए `०.१ + ०.२ == ०.३` असत्य है। पूर्णांक
और दशमलव के बीच अपने-आप परिवर्तन नहीं होता; `परिवर्तित` स्पष्ट रूप से लिखें।

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर लंबाई: दशमलव = ५.७;
    स्थिर चौड़ाई: दशमलव = ४.२;
    लिखो(लंबाई * चौड़ाई);
    लिखो(परिवर्तित<दशमलव>(३) / २.०);
}
```

Unary `+`, unary `-`, `+`, `-`, `*`, `/` और सभी छह comparisons (`==`, `!=`,
`<`, `<=`, `>`, `>=`) उपलब्ध हैं। `%` भाषा में अभी केवल पूर्णांक के लिए है।
गणना binary64 round-to-nearest, ties-to-even का उपयोग करती है; LLVM को fast-math,
reassociation या contraction की अनुमति नहीं दी जाती। ऋणात्मक शून्य (negative zero)
बना रहता है। `१.० / ०.०` से `Infinity`, `-१.० / ०.०` से `-Infinity`, और
`०.० / ०.०` से `NaN` मिलता है; ये गणनाएँ अपवाद नहीं फेंकतीं। Scalar `NaN == NaN`
असत्य और `NaN != NaN` सत्य है; NaN के साथ चारों ordering comparisons असत्य हैं।
दोनों शून्य scalar comparison में समान हैं।

दशमलव locals, parameters, return values, mutable/immutable fields, constructors,
inheritance, virtual/interface calls और `अंततः` से होकर लौटने वाले परिणाम उपलब्ध
हैं। नए fields और array elements का default `०.०` है।

## परिवर्तन (Conversions)

```bhash
कार्य मुख्य() -> शून्य {
    लिखो(परिवर्तित<दशमलव>(४२));
    लिखो(परिवर्तित<पूर्णांक>(-९.९)); // -९: शून्य की ओर काटना (truncate)
    लिखो(परिवर्तित<दशमलव>("-१२३.२५"));
    लिखो(परिवर्तित<दशमलव>("+42.125"));
    लिखो(परिवर्तित<पाठ>(१.२५)); // ASCII पाठ: 1.25
    प्रयास { लिखो(परिवर्तित<पूर्णांक>(१.० / ०.०)); }
    पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
}
```

- `पूर्णांक → दशमलव`: निकटतम binary64; बड़े integers के कुछ अंतिम bits खो सकते हैं।
- `दशमलव → पूर्णांक`: fractional भाग को शून्य की ओर काटता है। NaN, infinities,
  `value < -2^63` या `value >= 2^63` पर catchable `अपवाद` आता है। यह जाँच LLVM
  integer conversion से पहले होती है, इसलिए out-of-range conversion undefined नहीं है।
- `पाठ → दशमलव`: optional ASCII `+`/`-`, फिर कम-से-कम एक digit; optional decimal
  point के बाद भी कम-से-कम एक digit। एक पूरे मान में सभी digits ASCII या सभी
  देवनागरी होने चाहिए। Whitespace, exponent, hex, `NaN`, `Infinity`, embedded NUL,
  mixed digits, `.5` और `1.` अस्वीकार होते हैं। Overflow अपवाद है; बहुत छोटा मान
  subnormal या signed zero हो सकता है। ये मौजूदा managed parsing नियम हैं।
- `दशमलव → पाठ`: ASCII digits के साथ round-trip presentation। `लिखो` संख्याओं
  को देवनागरी digits में प्रस्तुत करता है; पाठ को वैसा ही लिखता है।

## स्वरूपण (Formatting)

```bhash
आयात मानक.पाठ्य;
कार्य मुख्य() -> शून्य {
    लिखो(पाठ्य.संख्यापाठ(२.६७५, २)); // 2.67: वास्तविक binary64 का rounding
    लिखो(पाठ्य.संख्यापाठ(२.५, ०)); // 2: ties-to-even
    लिखो(पाठ्य.संख्यापाठ(३.५, ०)); // 4
    लिखो(पाठ्य.संख्यापाठ(-०.०, ३)); // -0.000
    लिखो(पाठ्य.अंकदेवनागरी(पाठ्य.संख्यापाठ(१२.५, २))); // १२.५०
}
```

`पाठ्य.संख्यापाठ(मानक: दशमलव, स्थान: पूर्णांक) -> पाठ` ASCII fixed-point पाठ
देता है। स्थान ० से १५ तक हैं। Exact binary64 value को nearest, ties-to-even
से round किया जाता है; infinity, NaN और invalid places पर catchable अपवाद है।
ऋणात्मक शून्य का sign बचता है। अधिकतम finite binary64 भी स्वरूपित हो सकता है।

सामान्य `लिखो` और `परिवर्तित<पाठ>` fractional `.0` बचाते हैं और decimal exponent
-3 से 6 के बाहर `E` notation देते हैं, जैसे `1.0E7`। NaN और infinities की spelling
`NaN`, `Infinity`, `-Infinity` है। Native runtime significant-digit candidates को
C `snprintf` और `strtod` से round-trip जाँचकर चुनता है; फिर Bhash presentation
लगाता है। यह कोई `%.6f` truncation नहीं है। पुराने Java formatter या CLR formatter
के साथ कुछ दुर्लभ अंतिम digits अलग हो सकते हैं, जबकि वही binary64 वापस मिलता है।
इस presentation को exact cross-target serialization format न मानें। Fixed formatting
के नियम और उसके finite-value परिणाम target-independent हैं। Runtime process का
प्रारंभिक C locale और rounding mode रखता है; supported native Bhash profile इन्हें
बदलने का कोई API नहीं देता। भविष्य की foreign interoperability को इन contracts का
ध्यान रखना होगा।

## सर्ववस्तु और संग्रह (Boxing and collections)

```bhash
कार्य मुख्य() -> शून्य {
    स्थिर क: सर्ववस्तु = १.२५;
    लिखो(प्रकारहै<दशमलव>(क), परिवर्तित<दशमलव>(क));
    स्थिर स: सूची<दशमलव> = नया सूची<दशमलव>{१.२५, २.५};
    स्थिर म: मानचित्र<दशमलव, पाठ> = नया मानचित्र<दशमलव, पाठ>{};
    म.रखो(-०.०, "पहला"); म.रखो(०.०, "बदला");
    लिखो(स[१], म.आकार(), म.लो(-०.०));
}
```

Unboxing में exact runtime type चाहिए: boxed पूर्णांक को सीधे दशमलव unbox नहीं कर
सकते। पहले पूर्णांक निकालें, फिर numeric conversion करें। Null और wrong boxed kind
पर catchable cast failure आता है। Arrays, lists, maps, snapshots और iteration सभी
दशमलव का समर्थन करते हैं। `सर्ववस्तु` equality और collection equality में NaNs
एक-दूसरे के समान, और दोनों signed zeros समान हैं; hashing भी यही नियम मानता है।
यह मौजूदा collection/value equality है, scalar NaN comparison से अलग।

## स्मृति और निजी अनुबंध (GC and private ABI)

निजी native ABI v8 में decimal scalar का LLVM प्रकार `double` और आकार आठ bytes है।
Function arguments/results और fields में doubles सीधे जाते हैं। Collection kind 6
(`BH_REAL`) के slots raw binary64 bits रखते हैं; वे GC references नहीं हैं। Decimal
box की nominal membership `-6` और `सर्ववस्तु` membership `-4` है। उसका 16-byte
payload आठ-byte metadata pointer और आठ-byte double है; उसके GC descriptor में कोई
managed reference नहीं है। Existing exception ID `-5` और अन्य layouts नहीं बदलते।
Boxing, parsing और formatting allocations precise roots और मौजूदा exception-unwind
contracts का पालन करते हैं। Collections NaN hash canonicalize करती हैं और zero के
sign को hash में अनदेखा करती हैं। Compiler/runtime को साथ rebuild करना आवश्यक है;
यह कोई public foreign ABI नहीं है।

## जाँच और चलाना (Validation and execution)

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/decimals.bhash --target native -o target/decimals-native
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./target/decimals-native
```

Runnable example में formatting, object field/method, boxing, list/map, parsing,
finally और catchable conversion failure हैं। Native tests JVM output और exit status
से तुलना करते हैं। Independent C tests 20,000 bit-pattern round trips, minimum
subnormal/maximum finite values, fixed rounding, invalid input, checked conversions,
NaN/zero hashing और scalar pointer-bit GC precision की जाँच debug/optimized तथा
Linux sanitizer builds में करते हैं। CI executable को JVM/.NET/build tools के बिना
एक साफ Linux container में चलाता है।

अन्य numeric widths, exact decimal arithmetic और अभी unsupported standard-library
JSON/time/file APIs इस milestone में नहीं जुड़ते। आगे की native scope के लिए
[native profile](native-compilation-design.md) देखें।

[Native math and seeded randomness](native-math-random.md) now support all existing functions in those two modules.
