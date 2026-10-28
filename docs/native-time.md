# मूल-मशीन समय और दिनांक (Native UTC Date and Time)

Linux x86-64 / LLVM 18 पर `मानक.समय` के सभी छह functions उपलब्ध हैं। Unix epoch
`1970-01-01T00:00:00.000Z` से signed 64-bit **milliseconds** समय का मान हैं। Calendar
operations proleptic Gregorian UTC में हैं। Local timezone, locale, daylight saving और
host calendar APIs इनके परिणाम नहीं बदलते। Applications को JVM या .NET नहीं चाहिए।

[पूरा runnable example](../examples/native/time.bhash) और
[standard-library tutorial](../tutorial/standard-library/05-time.md) देखें।

## अभी (Current Time / Now)

`समय.अभी() -> पूर्णांक` OS की UTC wall clock से वर्तमान epoch milliseconds देता है।
यह monotonic stopwatch नहीं है; system clock बदली जा सकती है। Native target `timespec_get`
से clock पढ़ता है और nanoseconds को milliseconds तक घटाता है। OS clock उपलब्ध न होने
पर catchable `वर्तमान समय उपलब्ध नहीं है।` मिलता है।

## पढ़ो (Parse UTC Timestamp)

`समय.पढ़ो(मानक: पाठ) -> पूर्णांक` केवल 24-character ASCII format
`yyyy-MM-ddTHH:mm:ss.SSSZ` स्वीकार करता है। `T`/`Z` uppercase और fractional part ठीक
तीन digits होना चाहिए। वर्ष 0001–9999, घंटे 00–23 और minute/second 00–59 हैं। Leap-year
rule Gregorian है: 4 से विभाजित वर्ष leap है, पर century केवल 400 से विभाजित होने पर leap है।
इसलिए 1600/2000/2400 के leap days मान्य, 1700/1800/1900/2100 के अमान्य हैं।

खाली/extra text, whitespace, offsets, Devanagari digits, omitted milliseconds, leap seconds,
invalid day/month और वर्ष `0000` पर `अमान्य UTC समय पाठ।` मिलता है। Null input पर सामान्य
`रिक्त वस्तु के सदस्य का उपयोग नहीं किया जा सकता।` अपवाद मिलता है। JVM की year-zero error
और दोनों managed targets की null handling को इसी portable व्यवहार से मिलाया गया है।

## पाठबनाओ (Format UTC Timestamp)

`समय.पाठबनाओ(मानक: पूर्णांक) -> पाठ` उसी fixed ASCII UTC format में नया immutable
text देता है। Calendar की range `-62135596800000` से `253402300799999` inclusive है:
`0001-01-01T00:00:00.000Z` से `9999-12-31T23:59:59.999Z`। बाहर का integer
`समय सीमा से बाहर है।` देता है; integer overflow या calendar wrapping नहीं होता।

Epoch से पहले की fractional seconds सुरक्षित हैं: `-1` का पाठ
`1969-12-31T23:59:59.999Z` है। Formatting में ASCII digits आते हैं; `लिखो` से सीधे
integer print करने पर सामान्य Bhash/Devanagari numeric display मिलता है।

## वर्ष (Year), माह (Month), दिन (Day of Month)

- `समय.वर्ष(मानक: पूर्णांक) -> पूर्णांक`: UTC वर्ष 1–9999।
- `समय.माह(मानक: पूर्णांक) -> पूर्णांक`: UTC महीना 1–12।
- `समय.दिन(मानक: पूर्णांक) -> पूर्णांक`: UTC महीने का दिन 1–31।

तीनों की वही inclusive calendar range और `समय सीमा से बाहर है।` error है।

```bhash
आयात मानक.समय उपनाम स;
कार्य मुख्य() -> शून्य {
    स्थिर त: पूर्णांक = स.पढ़ो("2000-02-29T12:34:56.789Z");
    लिखो(स.पाठबनाओ(त));
    लिखो(स.वर्ष(त),स.माह(त),स.दिन(त));
}
```

```text
2000-02-29T12:34:56.789Z
२००० २ २९
```

## स्मृति और जाँच (Memory and Validation)

Calendar arithmetic fixed-size scalar working values का उपयोग करती है। Host `gmtime`,
local timezone conversion और 32-bit `time_t` calendar ranges पर निर्भरता नहीं है। Formatted
text मौजूदा precise GC heap पर आता है। Compiler उसके result और nested-call inputs को
writable roots में रखता है; allocating `अंततः` में return value सुरक्षित रहती है।

Independent C checks सभी **3,652,059 calendar days** के components जाँचते हैं और हर महीने
के पहले/अंतिम दिन formatted text parse करके expected date से मिलाते हैं। Forced collection,
repeated parse/range failures और zero retained objects debug/optimized तथा Linux ASan/UBSan
में जाँचे जाते हैं। Bhash tests हर वर्ष 0001–9999, pre-1970 milliseconds, leap centuries,
malformed timestamps, range extremes और Hindi messages को JVM/CLR से compare करते हैं।
Current-clock checks एक ही timestamp की cross-process equality पर निर्भर नहीं हैं: OS clock
को host-time bounds से जाँचते हैं और प्रत्येक target की parse/format round trip validate करते हैं।
Standalone stress example clean container में JVM/.NET के बिना चलता है।
