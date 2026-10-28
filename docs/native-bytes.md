# मूल-मशीन बाइट और बाइटबफ़र (Native Bytes and Buffers)

Linux x86-64 / LLVM 18 पर unsigned `बाइट`, packed `बाइटबफ़र`, UTF-8 encode/decode
और binary file I/O उपलब्ध हैं। बने executable को JVM या .NET नहीं चाहिए।
[पूरा उदाहरण](../examples/native/bytes.bhash) UTF-8 buffer को binary फ़ाइल में लिखकर
वापस पढ़ता है और failures में allocating `अंततः` चलाता है।

## बाइट (Unsigned Byte)

`बाइट` का मान ०–२५५ है। `परिवर्तित<बाइट>(मान)` पूर्णांक, दशमलव या integer पाठ से
checked conversion करता है। Decimal पहले truncate होता है; NaN/Infinity और integer
range से बाहर input अस्वीकार होते हैं। Byte सीमा से बाहर मान पर
`बाइट परिवर्तन सीमा से बाहर है।` मिलता है। Mixed-script integer text के मौजूदा नियम लागू हैं।

```bhash
स्थिर ब: बाइट = परिवर्तित<बाइट>(२५५);
लिखो(ब, ब+ब, -ब, परिवर्तित<दशमलव>(ब)); // २५५ ५१० -२५५ २५५.०
स्थिर वस्तु: सर्ववस्तु = ब;
लिखो(प्रकारहै<बाइट>(वस्तु), प्रकारहै<पूर्णांक>(वस्तु)); // सत्य असत्य
```

Arithmetic और unary `+`/`-` byte को `पूर्णांक` में promote करते हैं। Boxing में byte और
integer अलग types रहते हैं; boxed byte को integer में सीधे unbox नहीं कर सकते। पहले byte
में unbox कर फिर numeric conversion करें। Fields का default शून्य है; byte arrays, lists,
map keys/values और `सर्ववस्तु` collections समर्थित हैं। JSON byte को integer number की तरह लिखता है।

## बाइटबफ़र (Packed Byte Buffer)

```bhash
स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(८); // शून्य से भरा
ब[०] = परिवर्तित<बाइट>(२५५);
स्थिर क: बाइटबफ़र = नया बाइटबफ़र{परिवर्तित<बाइट>(१), परिवर्तित<बाइट>(२)};
प्रत्येक (व: बाइट में क) { लिखो(व); }
```

| Operation | व्यवहार |
| --- | --- |
| `आकार`, `लंबाई`, `आकार()`, `लंबाई()`, global `लंबाई(ब)` | bytes की गिनती |
| `खाली()` | आकार शून्य है या नहीं |
| `[इ]`, `लो(इ)`, `रखो(इ,बाइट)` | checked index read/write |
| `है(बाइट)` | byte खोजता है |
| `भरो(बाइट)` | सभी bytes replace करता है |
| `काटो(आरंभ,अंत)` | end-exclusive स्वतंत्र buffer copy |
| `सरणी()` | स्वतंत्र `बाइट[]` copy |
| `प्रत्येक` | index order में bytes; value mutation से आकार नहीं बदलता |

आकार ० से २१४७४८३६४७ तक है और उपलब्ध memory से सीमित है। Buffer fixed-size है;
list जैसी append/insert/remove operations नहीं हैं। Length error:
`सरणी की लंबाई सीमा से बाहर है।`; index error: `संग्रह सूचकांक सीमा से बाहर है।`।
Buffer reference equality, runtime casts और `सर्ववस्तु` में storage साधारण reference rules मानते हैं।
JSON में buffer bytes की numeric array बनता है; JSON parsing सामान्य list लौटाती है।

## बाइनरी मान (Binary Integer and Decimal Layout)

`पूर्णांकपढ़ो(offset,bigEndian)` और `पूर्णांकलिखो(offset,value,bigEndian)` signed
64-bit two’s-complement bits पढ़ते/लिखते हैं। `दशमलवपढ़ो` और `दशमलवलिखो` binary64
bits का प्रयोग करते हैं। सत्य big-endian, असत्य little-endian है। Unaligned offsets
मान्य हैं; हर operation को offset से आठ bytes चाहिए। NaN payloads, infinity और negative
zero को raw representation में रखा जाता है। Slice/span error:
`बाइटबफ़र सीमा से बाहर है।`।

## यूटीएफ८ (UTF-8 Encoding) और यूटीएफ८पाठ (UTF-8 Decoding)

```bhash
आयात मानक.पाठ्य उपनाम प;
स्थिर ब: बाइटबफ़र = प.यूटीएफ८("भारत😀\u0000");
लिखो(प.यूटीएफ८पाठ(ब));
```

`पाठ्य.यूटीएफ८` packed UTF-8 bytes लौटाता है और `पाठ्य.यूटीएफ८पाठ` strict decode करता है।
Legacy globals `यूटीएफ८बफ़र` और `यूटीएफ८पाठ` भी उपलब्ध हैं। BOM और line endings
ज्यों के त्यों रहते हैं; embedded NUL text में मान्य है। Invalid continuation bytes,
overlong sequences, surrogate encodings, truncation और U+10FFFF से बाहर values पर
`अमान्य UTF-8 बाइटबफ़र।` मिलता है। Null buffer पर सामान्य null-reference अपवाद है।

## बाइटपढ़ो (Read Bytes) और बाइटलिखो (Write Bytes)

`मानक.फ़ाइल.बाइटपढ़ो(पथ)` raw buffer लौटाता है; `बाइटलिखो(पथ,बफर)` bytes बिना
encoding के लिखता है। सीमा **16 MiB encoded/raw bytes** है; ठीक सीमा मान्य है।
Read limit actual stream पर लागू होती है; oversized write validation destination
truncation से पहले पूरी होती है। Read/write/close failures और paths के नियम
[फ़ाइल guide](native-files.md) वाले हैं। यह append/atomic replacement/streaming API नहीं है।

## संग्रह और स्मृति (Garbage Collection)

Private ABI v10 में buffer सामान्य collection header और GC-managed packed scalar
storage का उपयोग करता है। प्रत्येक byte एक byte लेता है; raw payload में कोई managed
reference नहीं है। Pointer-जैसे bytes किसी object को जीवित नहीं रखते। Byte boxes की
nominal membership `-7` है; उनके आठ-byte scalar payload का tracing नहीं होता।

Compiler/runtime helpers writable roots रखते हैं और allocations के बाद pointers फिर
लोड करते हैं। Slice/array/UTF-8/file results partial failure या allocating finally में
सुरक्षित रहते हैं। Open file handles exception cleanup stack से बंद होते हैं। Programmer
को allocation/free या handles close नहीं करने होते।

Cross-target tests सभी २५६ values, conversions/boxing, typed collections, copies,
byte order, UTF-8, 16 MiB boundaries और errors की तुलना JVM/CLR से करते हैं। C runtime
tests forced GC, pointer-like scalar bytes, repeated failed decoding और zero retained
objects को debug/optimized और Linux ASan/UBSan में जाँचते हैं।
