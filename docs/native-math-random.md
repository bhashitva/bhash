# मूल-मशीन गणित और यादृच्छिक संख्याएँ (Native Math and Random Numbers)

प्रायोगिक Linux x86-64 / LLVM 18 लक्ष्य पर `मानक.गणित` और `मानक.यादृच्छिक`
के सभी मौजूदा functions उपलब्ध हैं: गणित के १३ नाम/१६ signatures और random के
३ signatures। नए भाषा keywords या API नाम नहीं जुड़ते। अनुप्रयोग native executable
है; JVM या .NET की आवश्यकता नहीं है। Linux C library और `libm` runtime dependencies
हैं; Java, LLVM और cross-target tests का .NET SDK केवल build/test dependencies हैं।

## गणितीय कार्य (Math functions)

```bhash
आयात मानक.गणित उपनाम ग;
कार्य मुख्य() -> शून्य {
    लिखो(ग.निरपेक्ष(-४२), ग.निरपेक्ष(-२.५));
    लिखो(ग.न्यूनतम(३, ९), ग.न्यूनतम(३.०, ९.०));
    लिखो(ग.अधिकतम(३, ९), ग.अधिकतम(३.०, ९.०));
    लिखो(ग.वर्गमूल(८१.०), ग.घात(२.०, १०.०));
    लिखो(ग.फर्श(-२.२५), ग.छत(-२.२५));
    लिखो(ग.निकटतम(२.५), ग.निकटतम(३.५));
    लिखो(ग.ज्या(०.०), ग.कोज्या(०.०));
    लिखो(ग.लघुगणक(१.०), ग.घातांक(०.०), ग.पाई());
}
```

| कार्य (English name) | signatures और अर्थ |
| --- | --- |
| निरपेक्ष (Absolute value) | `निरपेक्ष(पूर्णांक) -> पूर्णांक`, `निरपेक्ष(दशमलव) -> दशमलव`: magnitude; negative zero से positive zero |
| न्यूनतम (Minimum) | दो पूर्णांक या दो दशमलव; उसी प्रकार का छोटा मान |
| अधिकतम (Maximum) | दो पूर्णांक या दो दशमलव; उसी प्रकार का बड़ा मान |
| वर्गमूल (Square root) | `वर्गमूल(दशमलव) -> दशमलव` |
| घात (Power) | `घात(आधारक: दशमलव, घातक: दशमलव) -> दशमलव` |
| फर्श (Floor) | `फर्श(दशमलव) -> दशमलव`: नीचे की ओर integer-valued double |
| छत (Ceiling) | `छत(दशमलव) -> दशमलव`: ऊपर की ओर integer-valued double |
| निकटतम (Round to even) | `निकटतम(दशमलव) -> दशमलव`: nearest integer; ties-to-even, जैसे 2.5→2 और 3.5→4 |
| ज्या (Sine) | `ज्या(दशमलव) -> दशमलव`: कोण radians में |
| कोज्या (Cosine) | `कोज्या(दशमलव) -> दशमलव`: कोण radians में |
| लघुगणक (Natural logarithm) | `लघुगणक(दशमलव) -> दशमलव`: base e |
| घातांक (Exponential) | `घातांक(दशमलव) -> दशमलव`: e raised to the input |
| पाई (Pi) | `पाई() -> दशमलव`: वही binary64 constant जो अन्य targets पर है |

पूर्णांक और दशमलव arguments के बीच implicit conversion नहीं है। उदाहरण के लिए
`वर्गमूल(परिवर्तित<दशमलव>(८१))` लिख सकते हैं। Module imports, aliases और fully
qualified calls मौजूदा resolver से चलते हैं। Arguments एक बार, बाएँ से दाएँ evaluate
होते हैं; परिणाम निकालने से पहले सभी arguments evaluate होते हैं।

## सीमा और त्रुटियाँ (Domains and errors)

दशमलव math functions में सभी inputs finite होने चाहिए। NaN और दोनों infinities
पर `अपवाद("एक सीमित दशमलव मान आवश्यक है।")` आता है। `वर्गमूल`, `घात`,
`लघुगणक` और `घातांक` के nonfinite results पर भी यही error है। उदाहरण: negative
square root, `लघुगणक(०.०)`, negative-base fractional power, zero की negative
power, और overflow। बहुत छोटे परिणाम का finite zero/subnormal होना अनुमत है।
`घात(०.०, ०.०)` का परिणाम `१.०` है। यह finite checking सामान्य decimal arithmetic
से अलग है: arithmetic में `१.० / ०.०` Infinity दे सकता है, लेकिन उसे math function
में argument बनाकर देने पर catchable error आता है।

`निरपेक्ष(-2^63)` पर `निरपेक्ष पूर्णांक सीमा से बाहर है।` आता है। Integer min/max
पूरे signed 64-bit क्षेत्र में काम करते हैं। Decimal min में दोनों signed zeros का
परिणाम negative zero होता है यदि कोई input negative zero है; max में positive zero
जीतता है। दोनों inputs negative zero हों तो max भी negative zero है। Square root,
sine, rounding और powers के sign-sensitive zero परिणाम बचते हैं।

```bhash
आयात मानक.गणित;
कार्य मुख्य() -> शून्य {
    प्रयास { लिखो(गणित.वर्गमूल(-१.०)); }
    पकड़ो (क: अपवाद) { लिखो(क.संदेश); }
    अंततः { लिखो("साफ"); }
}
```

Errors source location सहित मौजूदा native exception mechanism का उपयोग करते हैं।
`अंततः` चलता है और abandoned GC roots साफ होते हैं। Unhandled errors cleanup के
बाद status 1 देते हैं। Math operations स्वयं normal execution में managed allocation
नहीं करतीं; boxing/formatting या error construction आवश्यकता पर allocation करते हैं।

## पुनरुत्पादनीय random अनुक्रम (Seeded random sequences)

```bhash
आयात मानक.यादृच्छिक उपनाम य;
कार्य मुख्य() -> शून्य {
    य.बीज(०);
    लिखो(य.पूर्णांकचुनो(-१०, १०)); // -३
    लिखो(य.अंश()); // 0.0 <= value < 1.0
    य.बीज(०);
    लिखो(य.पूर्णांकचुनो(-१०, १०)); // फिर -३
}
```

- बीज (Seed): `बीज(मानक: पूर्णांक) -> शून्य` process-wide stream reset करता है।
  Zero, negative seeds, minimum और maximum signed 64-bit seeds सभी उपलब्ध हैं।
- पूर्णांकचुनो (Bounded integer): `पूर्णांकचुनो(निम्न: पूर्णांक, ऊपरी: पूर्णांक) -> पूर्णांक`
  interval `[निम्न, ऊपरी)` में uniform draw देता है। Lower inclusive, upper exclusive
  है। Width 1 से `2^63-1` तक होना चाहिए; empty, reversed और इससे चौड़े intervals
  पर `यादृच्छिक पूर्णांक की सीमा अमान्य है।` आता है। Invalid range कोई draw consume
  नहीं करती। Width 1 वाला valid call draw consume करता है।
- अंश (Unit fraction): `अंश() -> दशमलव` में `0.0 <= value < 1.0` है। अगले draw
  के ऊपरी 53 bits को `2^-53` से गुणा किया जाता है, इसलिए result exact binary64 है।

Native, JVM और CLR एक ही SplitMix64 stream और bounded rejection sampling उपयोग
करते हैं। समान explicit seed और समान ordered calls से **exact वही integer और unit
fraction values** मिलते हैं। Integer और fraction calls एक ही state साझा करते हैं।
Rejected candidates भी state आगे बढ़ाते हैं; इसलिए केवल range बदल देने से बाद के
results बदल सकते हैं। अलग modules/packages के calls भी वही process-wide stream लेते
हैं। Seed न देने पर startup clock से best-effort initialization होता है; उस sequence
की cross-run/cross-target समानता का वादा नहीं है। यह cryptographic generator नहीं है।
मौजूदा native runtime single-threaded है; concurrent stream access नया feature नहीं है।

## संख्यात्मक समानता (Cross-target numerical agreement)

Integer operations, pi, signed-zero rules, ties-to-even rounding, seeded randomness
और error messages के exact contracts हैं। Transcendental calculations में native
`libm`, JVM `StrictMath` और CLR `Math` अलग implementations हैं। अंतिम कुछ bits
अलग हो सकते हैं; universal bit-for-bit equality का वादा नहीं है।

Tests representative square-root, power, sine, cosine, logarithm और exponential
results को JVM और CLR से **चार ULP** tolerance में compare करते हैं। ULP उस
magnitude पर पास-पास binary64 numbers के बीच दूरी है। यह selected conformance
vectors की tolerance है, हर possible input के लिए libm error-bound की specification
नहीं है। Zero के signs अलग से exact compare होते हैं। बाकी exact contracts में
कोई numerical tolerance नहीं लगती। Formatting के अलग नियम [decimal guide](native-decimals.md)
में हैं।

## Runtime और validation (Runtime and validation)

निजी native ABI v9 typed numeric intrinsics जोड़ता है; v7 के object, decimal,
collection और root-frame layouts नहीं बदलते। LLVM compiler `double`/`i64` arguments
और result types सीधे emits करता है। Native linker `libm` जोड़ता है। C generator में
state updates, multiplication और shifts unsigned 64-bit हैं, ताकि C signed-overflow
undefined behavior न हो। Range width unsigned subtraction से जाँची जाती है; rejection
condition managed wraparound contract से मेल खाती है। Mathematical rounding process
के initial nearest/ties-to-even mode को मानती है; supported Bhash native profile इसे
बदलने का API नहीं देता। भविष्य की foreign interoperability को यह contract बचाना होगा।

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/math-random.bhash --target native -o target/math-random-native
BHASH_GC_STRESS=1 BHASH_GC_STATS=1 ./target/math-random-native
```

Runnable example math, seeded sampling, decimal boxing, collections और exception
cleanup को जोड़ता है। Native/JVM/CLR tests सभी १९ signatures, signed zeros, rounding,
mixed sequences, negative/extreme seeds, rejection-prone ranges, invalid bounds,
single evaluation और caught/unhandled failures जाँचते हैं। Independent C tests में
separate golden vectors, ४०,००० mixed bounded/unit iterations, domain errors, root
unwinding और restart हैं। Debug, optimized तथा Linux ASan/UBSan builds चलते हैं।
CI example को compiler/JVM/.NET के बिना साफ Linux container में चलाता है।

UTC time, file operations, बाकी byte-buffer helpers और अन्य numeric widths अभी
[native profile](native-compilation-design.md) के बाहर हैं।

[Native structured text](native-json-text.md) अब split/join और JSON उपलब्ध करता है।
