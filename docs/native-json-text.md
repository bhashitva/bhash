# मूल-मशीन split/join और JSON (Native Structured Text)

प्रायोगिक Linux x86-64 / LLVM 18 target पर `पाठ्य.बाँटो`, `पाठ्य.जोड़ो`,
`जेसन.पढ़ो` और `जेसन.पाठबनाओ` उपलब्ध हैं। Applications को JVM या .NET की
आवश्यकता नहीं है। मौजूदा [standard-library contracts](standard-library.md#json-data-model)
और [native profile](native-compilation-design.md) लागू हैं।

## बाँटना (Split) और जोड़ना (Join)

```bhash
आयात मानक.पाठ्य उपनाम प;
कार्य मुख्य() -> शून्य {
    स्थिर भाग: सूची<पाठ> = प.बाँटो("अ,,भारत😀,", ",");
    लिखो(भाग.आकार(), प.जोड़ो(भाग,"|")); // ४ अ||भारत😀|
    भाग.जोड़ो("अंत");
    लिखो(प.जोड़ो(भाग,""));
}
```

`बाँटो(मानक: पाठ, विभाजक: पाठ) -> सूची<पाठ>` literal separator पर nonoverlapping
matches चुनता है; regex नहीं है। Leading, adjacent और trailing empty pieces बचते
हैं। Empty input से एक empty piece मिलता है। Empty separator catchable error है:
`विभाजक या बदलने वाला पाठ खाली नहीं हो सकता।` परिणाम सामान्य mutable typed list है।

`जोड़ो(मानक: सूची<पाठ>, विभाजक: पाठ) -> पाठ` सभी pieces के बीच separator रखता
है। Empty separator अनुमत है; empty list से empty text मिलता है। Null list सामान्य
null-reference error देती है। Unicode, supplementary characters और embedded NUL
दोनों operations में सुरक्षित रहते हैं। Returned lists पर सामान्य indexing, mutation,
iteration और runtime casts उपलब्ध हैं; chained calls भी supported हैं।

## पढ़ना (JSON Parsing)

```bhash
आयात मानक.जेसन उपनाम ज;
कार्य मुख्य() -> शून्य {
    स्थिर मूल: सर्ववस्तु = ज.पढ़ो("{\"नाम\":\"भारत😀\",\"अंक\":[1,2.5,true,null]}");
    स्थिर म: मानचित्र<पाठ,सर्ववस्तु> = परिवर्तित<मानचित्र<पाठ,सर्ववस्तु>>(मूल);
    स्थिर अ: सूची<सर्ववस्तु> = परिवर्तित<सूची<सर्ववस्तु>>(म["अंक"]);
    लिखो(परिवर्तित<पाठ>(म["नाम"]), परिवर्तित<दशमलव>(अ[१]), अ[३] == रिक्त);
}
```

`पढ़ो(मानक: पाठ) -> सर्ववस्तु` strict JSON पढ़ता है। Runtime values:

| JSON value | Bhash type |
| --- | --- |
| object | `मानचित्र<पाठ,सर्ववस्तु>` |
| array | `सूची<सर्ववस्तु>` |
| string | `पाठ` |
| integer lexeme | signed 64-bit `पूर्णांक` |
| fractional/exponent lexeme | finite binary64 `दशमलव` |
| true/false | `तार्किक` |
| null | `रिक्त` |

Parsed containers सामान्य typed collections हैं। सभी values integers हों तब भी array
का type `सूची<सर्ववस्तु>` है; generic casts invariant हैं। Integer lexemes पूरे signed
64-bit क्षेत्र में exact रहते हैं। Fraction/exponent lexemes binary64 में round होते हैं;
negative zero और subnormal values बचते हैं। Underflow signed zero दे सकता है।

Leading zeros, plus-prefixed numbers, non-ASCII number digits, trailing tokens/commas,
duplicate keys, malformed escapes और out-of-range integers अस्वीकार होते हैं। Exponents
JSON में अनुमत हैं, जबकि सामान्य `परिवर्तित<दशमलव>(पाठ)` की अलग strict grammar है।
Real overflow/nonfinite values पर मौजूदा finite-value error मिलता है।

## लिखना (JSON Serialization)

```bhash
आयात मानक.जेसन उपनाम ज;
कार्य मुख्य() -> शून्य {
    स्थिर अ: सूची<सर्ववस्तु> = नया सूची<सर्ववस्तु>{१,१.०,"क😀",सत्य,रिक्त};
    स्थिर म: मानचित्र<पाठ,सर्ववस्तु> = नया मानचित्र<पाठ,सर्ववस्तु>{"डेटा":अ};
    लिखो(ज.पाठबनाओ(म));
}
```

`पाठबनाओ(मानक: सर्ववस्तु) -> पाठ` null, supported scalars, arrays, lists और maps
स्वीकारता है। Scalars जरूरत पर सामान्य `सर्ववस्तु` boxes में जाते हैं। Map keys text
होनी चाहिए; `मानचित्र<सर्ववस्तु,...>` में boxed text keys भी उपलब्ध हैं। Empty maps
का output `{}` है, भले declared key type अलग हो। Map insertion order बचता है।

Ordinary classes और exceptions को पहले explicit data map/list में बदलें; automatic
object reflection नहीं है। Records, enums और अन्य numeric widths अभी
native profile के बाहर हैं। Byte-buffer JSON support इस milestone में नहीं जुड़ता।

Real values में decimal/exponent marker रहता है ताकि `१.०` वापस `दशमलव` पढ़ा जाए।
Nonfinite reals अस्वीकार होते हैं। Floating-point spelling canonical cross-target byte
format नहीं है: पुराने Java, CLR और native formatters कुछ दुर्लभ अंतिम digits या
exponent presentation में अलग हो सकते हैं; values binary64 में round-trip होती हैं।
Integer output exact है। [Decimal formatting](native-decimals.md) भी देखें।

## Unicode और सीमाएँ (Unicode and Limits)

Native text UTF-8 में है, लेकिन compatibility के लिए JSON input/output का limit
**1,048,576 UTF-16 units** है। Supplementary character जैसे 😀 दो units लेता है।
यह `लंबाई` के Unicode-scalar count से अलग है। Limit failures का message है:
`जेसन पाठ सीमा से बाहर है।`

Parser errors `अमान्य जेसन पाठ, स्थान N।` में zero-based **UTF-16** position देते
हैं, byte offset या scalar index नहीं। Unicode surrogate escapes सही pairs में होने
चाहिए; closing quote तक पढ़ने के बाद unmatched surrogate पर `अमान्य यूनिकोड पाठ।`
आता है। Broken escape syntax/unterminated strings सामान्य JSON position error देते हैं।
Quotes/backslashes escaped हैं; control characters lowercase `\u00xx` रूप में लिखे
जाते हैं। Valid non-ASCII text और supplementary characters raw Unicode में रहते हैं।

Value depth root पर 0 से शुरू होती है और 64 तक उपलब्ध है। इसलिए 65 nested empty
containers valid हैं; depth 65 पर value पढ़ना invalid JSON है। Writer depth overflow
का message है `जेसन की गहराई सीमा पार हो गई।` Active-path identity से cycles पकड़े
जाते हैं: `चक्रीय संग्रह को जेसन में नहीं लिख सकते।` Shared child को अलग siblings में
बार-बार लिखना valid है। Error precedence, boundary counts और messages managed targets
से compare किए जाते हैं।

## GC और unwinding (Precise Roots and Cleanup)

Parser input, partial maps/lists, current key/value, scalar boxes और growing text
buffers precise writable roots में रहते हैं। Writer का active path भी rooted है।
Allocations के पार managed interior pointers नहीं रखे जाते: offsets/scalars बचते हैं
और source pointers root slots से फिर पढ़े जाते हैं। Temporary buffers managed हैं;
कोई unmanaged parser workspace manual free करने की जरूरत नहीं है।

On failure, C protection boundary abandoned parser/writer/helper root frames हटाती
है। Compiler protected region के expression/local roots भी साफ करता है। Outer live
bindings बचते हैं। Pending exception `अंततः` के allocations के दौरान rooted रहती है।
Unreachable partial graphs अगली collection में reclaim होते हैं; उन्हें program exit
तक बचाकर नहीं रखा जाता। यह existing precise, stop-the-world, nonmoving collector
और future relocation/generation contracts का उपयोग करता है।

निजी ABI v9 compiler को immutable structured metadata देता है: canonical list-of-text,
list-of-universal और text-to-universal map descriptors, तथा चार existing scalar-box
GC/type descriptors। Runtime-created values वही nominal IDs और layouts उपयोग करते
हैं जो source-created values करते हैं। Existing heap layouts नहीं बदलते।

## जाँच और उदाहरण (Validation and Example)

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/json-text.bhash --target native -o target/json-text-native
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./target/json-text-native
```

इस उदाहरण का standard output:

```text
४ अ||भारत😀|
४४८५० ३००
{"नाम":"भारत😀","अंक":[1,2]}
[["साझा",1.0],["साझा",1.0]]
चक्रीय संग्रह को जेसन में नहीं लिख सकते।
```

तीसरी पंक्ति में `मानचित्र` JSON object (`{}`) बनता है और उसके `अंक` field की
सूची JSON array (`[]`) बनती है। चौथी पंक्ति साझा nested lists का array दिखाती है।

Runnable example में 300 nested parse/write/reparse iterations, repeated partial
parse failures, split/join, shared children और cycle detection हैं। Integration tests
JVM/CLR output और exit status compare करते हैं। Real JSON spelling के बजाय numeric
round-trip/type preservation compare होता है। Forced-GC tests में exact size/depth
boundaries, supplementary characters, error offsets और uncaught failure के दौरान
allocating finally हैं। Independent C tests 1,000 successful/failed nested parse
cycles के बाद explicit collection से zero retained objects जाँचते हैं। Linux CI
LLVM verification, ASan/UBSan और clean-container standalone execution चलाता है।

Native byte और buffer JSON serialization भी उपलब्ध है: byte integer number बनता है
और packed buffer numeric JSON array। Parsing हमेशा मौजूदा integer/list types देता है।
[Native bytes](native-bytes.md) देखें।
