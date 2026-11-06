# Types, conversions and binary data (v0.9)

These features use the same semantics on JVM, .NET CLR and browser .NET WebAssembly.
The [multi-file example](../examples/types/) combines records, enums, generics,
imports, conversions, runtime casts, UTF-8 and binary buffers.

**हिन्दी:** JVM, CLR और WebAssembly में इन सुविधाओं के समान नियम हैं। [बहु-फ़ाइल उदाहरण](../examples/types/) अभिलेख, परिगणना, सामान्य प्रकार, आयात, casts, UTF-8 और binary buffers साथ दिखाता है।

## Numeric types and explicit conversions

| Type | Range / representation | Default array/field value |
| --- | --- | --- |
| `बाइट` | Unsigned integer, 0–255 | 0 |
| `लघुपूर्णांक` | Signed 32-bit integer | 0 |
| `पूर्णांक` | Signed 64-bit integer | 0 |
| `दशमलव` | IEEE 754 binary64 floating point | 0.0 |

Numeric literals retain their existing types: integer literals are `पूर्णांक`,
and fractional literals are `दशमलव`. Use explicit conversions for other widths:

**हिन्दी:** पूर्णांक literal का प्रकार `पूर्णांक` और भिन्न literal का `दशमलव` है। दूसरी चौड़ाई के लिए स्पष्ट रूपांतरण दें:

```text
स्थिर छोटा: बाइट = परिवर्तित<बाइट>(२५५);
स्थिर गिनती: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>(१०००);
स्थिर पूरा: पूर्णांक = परिवर्तित<पूर्णांक>(गिनती);
स्थिर वास्तविक: दशमलव = परिवर्तित<दशमलव>(पूरा);
लिखो(परिवर्तित<पूर्णांक>(-३.९)); // -३
```

`परिवर्तित<T>(expression)` evaluates its operand once. Numeric narrowing checks
range and raises a catchable `अपवाद` instead of wrapping or saturating. Floating
point to integer conversions require a finite value and truncate toward zero.
The truncated result must fit the destination: `परिवर्तित<बाइट>(-०.५)` is 0,
while -1 or 256 fails. Conversion to `पूर्णांक` rejects values below -2^63 or
at/above 2^63; in particular the rounded binary64 representation of 2^63 is
rejected. Converting integers to binary64 may round and lose low bits, especially
above 2^53. This is an explicit rounding conversion, not arbitrary-precision math.

**हिन्दी:** `परिवर्तित<T>` operand को एक बार चलाता है। संख्यात्मक सीमा के बाहर पकड़ने योग्य `अपवाद` मिलता है। दशमलव से पूर्णांक के लिए finite मान चाहिए और दशमलव हिस्सा शून्य की ओर कटता है। `-०.५` से `बाइट` शून्य है, `-1` और `256` विफल हैं। `पूर्णांक` में −2^63 से कम या 2^63 और अधिक अमान्य हैं। binary64 में पूर्णांक बदलने पर खासकर 2^53 से ऊपर निचले बिट खो सकते हैं।

Assignment, parameters, return values and collection writes still require the
specified numeric type. Pure constant conversions are permitted in field initializers;
user calls and object allocation retain their existing restrictions. There is no implicit narrowing or widening for these
operations. Integer arithmetic, comparisons and bitwise expressions promote
`बाइट` and `लघुपूर्णांक` operands to `पूर्णांक`. Their result is therefore
64-bit; explicitly convert it back when assigning to a smaller numeric type.
Existing signed 64-bit wrapping arithmetic and shift-count masking remain in force.
Mixing integer and `दशमलव` arithmetic still requires an explicit conversion.

**हिन्दी:** assignment, मापदंड, परिणाम और संग्रह writes में बताया संख्यात्मक प्रकार ही चाहिए। शुद्ध स्थिर रूपांतरण क्षेत्र आरंभक में मान्य हैं। गणना, तुलना और bitwise में `बाइट`/`लघुपूर्णांक` से `पूर्णांक` बनता है। छोटा परिणाम चाहिए तो स्पष्ट रूपांतरण करें। 64-bit overflow और shift mask नियम लागू हैं। `दशमलव` तथा पूर्णांक मिलाने से पहले स्पष्ट रूपांतरण दें।

Text can be converted to integer types or binary64. Accepted input has an optional
ASCII `+`/`-`, digits in one script (ASCII or Devanagari), and, for binary64 only,
an optional decimal point with digits on both sides. Whitespace, mixed digit
scripts, exponents and NaN/Infinity spellings are rejected. Integer parsing is
signed 64-bit before checked narrowing. Invalid or overflowing input raises
`अपवाद`. For example:

**हिन्दी:** पाठ से पूर्णांक या binary64 बन सकता है। वैकल्पिक ASCII चिह्न, एक ही लिपि के अंक और binary64 के लिए वैकल्पिक दशमलव बिंदु के दोनों ओर अंक दें। whitespace, मिश्रित लिपि, exponent और NaN/Infinity अस्वीकार हैं। संकरा करने से पहले signed 64-bit parsing होती है। गलत या बहुत बड़ा मान `अपवाद` देता है।

```text
लिखो(परिवर्तित<लघुपूर्णांक>("१२३"));
लिखो(परिवर्तित<दशमलव>("-१२.५"));
लिखो(परिवर्तित<पाठ>(१२३)); // text "123", useful for serialization
```

Numeric-to-text conversions use invariant ASCII digits; boolean-to-text produces
`सत्य` or `असत्य`. Enum-to-text produces the member name. Other scalar text
conversions preserve existing target-specific shortest binary64 formatting;
exact last-digit display parity for every double is not yet promised. Booleans
are not implicitly numeric and cannot be converted to numbers.

**हिन्दी:** संख्या से पाठ में ASCII अंक और तार्किक से `सत्य`/`असत्य` मिलता है। परिगणना अपना सदस्य नाम देती है। binary64 के छोटे प्रदर्शन में अंतिम अंक लक्ष्य पर अलग हो सकते हैं। तार्किक मान को संख्या में नहीं बदल सकते।

## General values, tests and casts

`सर्ववस्तु` can hold any Bhash value, including boxed primitives, classes,
interfaces, arrays, lists, maps, buffers, enums, records and `रिक्त`. Boxing into
this type is implicit; unboxing is explicit. The spelling preserves compatibility
with existing user classes called `वस्तु`.

**हिन्दी:** `सर्ववस्तु` में boxed आदिम मान, वर्ग, अनुबंध, संग्रह, बफ़र, परिगणना, अभिलेख और `रिक्त` आ सकते हैं। boxing अपने आप, unboxing स्पष्ट होती है। उपयोगकर्ता का `वस्तु` नाम अलग रहता है।

| Expression | Result |
| --- | --- |
| `प्रकारहै<T>(value)` | Boolean: whether a non-null value has type T or a subtype |
| `परिवर्तित<T>(value)` | Checked conversion/cast; mismatch raises `अपवाद` |
| `सुरक्षितरूप<T>(value)` | Reference cast; mismatch or null returns `रिक्त` |

```text
स्थिर मूल: सर्ववस्तु = नया बिल्ली();
यदि (प्रकारहै<बिल्ली>(मूल)) {
    स्थिर बिल्लीका: बिल्ली = परिवर्तित<बिल्ली>(मूल);
}
स्थिर शायद: बिल्ली = सुरक्षितरूप<बिल्ली>(मूल);
```

Tests and casts recognize class inheritance, implemented interfaces, records,
enums and concrete generic specializations. Collection tests preserve invariant
runtime element/key/value types even on the JVM, where host generics are erased:

**हिन्दी:** runtime जाँच और casts विरासत, अनुबंध, अभिलेख, परिगणना और ठोस सामान्य प्रकार पहचानते हैं। JVM के host generics मिटने पर भी संग्रह तत्व, कुंजी और मान के invariant प्रकार बचे रहते हैं:

```text
स्थिर मूल: सर्ववस्तु = नया सूची<पूर्णांक>{१};
लिखो(प्रकारहै<सूची<पूर्णांक>>(मूल)); // सत्य
लिखो(प्रकारहै<सूची<पाठ>>(मूल));     // असत्य
```

The same applies to nested arrays, map types and generic records/classes.
`सरणी<T>` and `T[]` remain aliases for the same type. Buffers are distinct from
`बाइट[]`. Tests and casts evaluate their operand once and never copy collections.
A successful cast preserves the original reference. Runtime tests do not narrow
variable types automatically; use an explicit cast after a successful test.

**हिन्दी:** भीतर की सरणियों, मानचित्र और सामान्य अभिलेख/वर्ग पर भी यही लागू है। `सरणी<T>` और `T[]` एक हैं, buffer और `बाइट[]` अलग। cast operand एक बार चलता है और प्रति नहीं बनाता। सफल जाँच के बाद भी नाम का संकलनकालीन प्रकार अपने आप नहीं बदलता। स्पष्ट cast दें।

Boxing preserves the exact primitive type. A boxed `बाइट` is not a boxed
`पूर्णांक`; `परिवर्तित<पूर्णांक>(boxedByte)` fails. First unbox to `बाइट`, then
convert numerically to `पूर्णांक`. A boxed value can be unboxed as `पाठ` only
when it contains text; casting a boxed number to text does not format it.

**हिन्दी:** boxing में मूल प्रकार बचता है। boxed `बाइट` को पहले `बाइट` में खोलें, फिर `पूर्णांक` बनाएँ। boxed मान में पाठ होने पर ही `पाठ` cast सफल है। संख्या cast करने से उसका प्रदर्शन पाठ नहीं बनता।

`प्रकारहै<T>(रिक्त)` is always false. Checked casts of null to nullable reference
types succeed and return null. Unboxing null to a primitive fails. Safe casts
require a nullable reference target; numeric, boolean and non-nullable `पाठ`
targets are rejected at compile time.

**हिन्दी:** `प्रकारहै<T>(रिक्त)` हमेशा असत्य है। nullable संदर्भ cast `रिक्त` लौटा सकता है। आदिम unboxing विफल है। सुरक्षित cast को nullable संदर्भ लक्ष्य चाहिए। संख्या, तार्किक या non-nullable पाठ संकलन में अस्वीकार हैं।

A statically general value must be cast before member access or printing.
Equality through `सर्ववस्तु` uses exact-type scalar value equality (with the
collection rules for NaN and signed zero), record value equality, and ordinary
reference identity for classes and collections. Thus boxed byte 2 and boxed
64-bit integer 2 are unequal. The same rules govern general map keys.

**हिन्दी:** सामान्य प्रकार के मान पर सदस्य पढ़ने या छापने से पहले cast दें। समानता में आदिम प्रकार भी समान होना चाहिए, NaN/zero पर संग्रह नियम हैं, अभिलेख मान से और वर्ग/संग्रह पहचान से मिलते हैं। boxed byte 2 और 64-bit integer 2 अलग हैं। सामान्य map कुंजी में भी यही नियम हैं।

## Enums

```text
परिगणना स्थिति { लंबित, तैयार = ५, पूर्ण }
स्थिर क: स्थिति = स्थिति.तैयार;
लिखो(क.नाम, क.अंक); // तैयार ५
स्थिर ख: स्थिति = परिवर्तित<स्थिति>(५);
चयन (ख) {
    विकल्प स्थिति.तैयार: लिखो("तैयार");
    शेष: लिखो("अन्य");
}
```

Enum members are immutable, typed singletons. Codes are signed 64-bit integers;
the first implicit code is 0 and subsequent implicit codes increment the preceding
code. Explicit signed literal codes may reset the sequence. Duplicate names/codes,
implicit overflow and empty enums are rejected. `नाम` and `अंक` are reserved
readonly instance properties. Names use normalized identifier spelling.

**हिन्दी:** परिगणना सदस्य अचल, प्रकारयुक्त एकल वस्तु है। code signed 64-bit है। पहला implicit code 0 और बाद वाला पिछले से एक अधिक है। स्पष्ट चिह्नयुक्त literal क्रम बदल सकता है। दोहरे नाम/code, overflow और खाली परिगणना अस्वीकार हैं। `नाम` और `अंक` readonly आरक्षित गुण हैं।

Enums support assignment, equality, collections, runtime tests/casts, package
exports/import aliases and typed switch labels. Conversion from a numeric value
(or integer text) requires a declared code; unknown codes raise `अपवाद`.
Conversion to a numeric type uses its code, with the ordinary range checks.
Conversion to `पाठ` uses the member name. Enums have no public constructor,
inheritance or implicit integer/flag operations.

**हिन्दी:** परिगणना assignment, समानता, संग्रह, casts, निर्यात/आयात और typed चयन में चलती है। संख्या या पूर्णांक पाठ से बदलने पर घोषित code चाहिए, वरना `अपवाद`। संख्या में code सीमा सहित मिलता है और पाठ में नाम। सार्वजनिक निर्माता, विरासत और implicit integer/flags नहीं हैं।

Like Java enums and other Bhash references, enum variables/array defaults may be
`रिक्त`. A null enum selector matches no named case and can reach `शेष`.
Switch labels must name members of the selector's enum type; raw integer labels
are not accepted. Include a default when return-path analysis requires one.
The keyword `परिगणना` leaves existing `गणना` names available for methods/variables.

**हिन्दी:** परिगणना संदर्भ और सरणी डिफ़ॉल्ट `रिक्त` हो सकते हैं। null selector किसी नामित लेबल से नहीं मिलता और `शेष` तक पहुँच सकता है। लेबल उसी परिगणना का नामित सदस्य हो। वापसी जाँच के लिए आवश्यकता पर `शेष` दें। `गणना` साधारण नाम रह सकता है।

## Immutable records

```text
अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
अभिलेख पात्र<त>(मानक: त);
```

Records generate a public constructor in field order and public readonly fields:

**हिन्दी:** अभिलेख में क्षेत्र क्रम में सार्वजनिक निर्माता और सार्वजनिक अचल क्षेत्र बनते हैं:

```text
स्थिर क: बिंदु = नया बिंदु(२,३);
लिखो(क.अ, क == नया बिंदु(२,३)); // २ सत्य
```

Records are sealed reference types with structural equality and hashing. Two
records are equal only when they have the same concrete record specialization
and equal fields. Scalar fields use collection value equality, nested records
compare structurally, and class/collection fields compare by reference identity.
Records can be map keys and may contain nullable reference fields. Null records
remain distinct from constructed records.

**हिन्दी:** अभिलेख sealed संदर्भ प्रकार हैं। समान ठोस विशेषीकरण और समान क्षेत्रों पर संरचनात्मक समानता/hash है। आदिम क्षेत्र संग्रह नियम से, अंदर के अभिलेख संरचना से और वर्ग/संग्रह पहचान से मिलते हैं। अभिलेख कुंजी हो सकते हैं और उनके संदर्भ क्षेत्र nullable हैं। `रिक्त` बनी वस्तु से अलग है।

Immutability is shallow: a readonly list/buffer field still references mutable
contents. Replacing a field is rejected. Record declarations currently contain
only typed fields; custom bodies, inheritance, custom equality, positional
patterns and copy-with syntax are not part of this version. Use ordinary classes
when custom construction or methods are needed. Records can be public package
exports and can use explicit generic type parameters.

**हिन्दी:** अचलता केवल क्षेत्र संदर्भ तक है। सूची या बफ़र की सामग्री बदल सकती है। क्षेत्र बदलना अमान्य है। घोषणा में केवल प्रकारयुक्त क्षेत्र हैं। अपने निर्माता, विधि या विशेष व्यवहार के लिए वर्ग लें। अभिलेख सार्वजनिक निर्यात और स्पष्ट सामान्य मापदंड ले सकते हैं।

## Packed byte buffers

`बाइटबफ़र` is a fixed-length, mutable, packed sequence of unsigned bytes. It uses
one host byte per element, rather than the generic representation of `बाइट[]`.
Lengths/offsets use 64-bit Bhash integers and are checked before host narrowing.
Lengths must be 0–2^31−1 and actual allocation is limited by host memory.

**हिन्दी:** `बाइटबफ़र` unsigned bytes का तय लंबाई वाला बदलने योग्य packed क्रम है। प्रति तत्व एक host byte है। लंबाई और offset 64-bit हैं और host में बदलने से पहले जाँचे जाते हैं। लंबाई 0–2^31−1 और उपलब्ध स्मृति तक है।

```text
स्थिर ब: बाइटबफ़र = नया बाइटबफ़र(१६);
ब[०] = परिवर्तित<बाइट>(२५५);
ब.पूर्णांकलिखो(०,४२,सत्य);       // big-endian signed 64-bit
ब.दशमलवलिखो(८,१.५,असत्य);      // little-endian binary64
लिखो(ब.पूर्णांकपढ़ो(०,सत्य), ब.दशमलवपढ़ो(८,असत्य));
प्रत्येक (ब में अंक: बाइट) { लिखो(अंक); }
```

| API | Behavior |
| --- | --- |
| `नया बाइटबफ़र(size)` | Zero-filled buffer |
| `नया बाइटबफ़र{byte, ...}` | Typed byte initializer |
| `ब[index]`, `ब[index] = byte` | Checked byte reads/writes |
| `.लंबाई`, `.आकार`, `.लंबाई()`, `.आकार()`, `लंबाई(ब)` | Size as `पूर्णांक` |
| `.लो(index)`, `.रखो(index, byte)`, `.है(byte)`, `.खाली()` | Standard sequence access/membership |
| `.भरो(byte)` | Fill the buffer |
| `.काटो(start, end)` | Independent copy of the half-open range |
| `.सरणी()` | Independent copy as `बाइट[]` |
| `.पूर्णांकपढ़ो(offset, bigEndian)` | Read signed 64-bit bits |
| `.पूर्णांकलिखो(offset, value, bigEndian)` | Write signed 64-bit bits |
| `.दशमलवपढ़ो(offset, bigEndian)` | Read IEEE binary64 bits |
| `.दशमलवलिखो(offset, value, bigEndian)` | Write IEEE binary64 bits |
| `यूटीएफ८बफ़र(text)` | Encode text to a new UTF-8 buffer without a BOM |
| `यूटीएफ८पाठ(buffer)` | Strict UTF-8 decoding; invalid sequences raise `अपवाद` |

The endian argument is a `तार्किक`: `सत्य` means big-endian; `असत्य` means
little-endian. Binary accesses need eight bytes starting at the offset; they do
not require alignment. Bounds are checked before any write, so failed writes
leave the buffer unchanged. Offsets/ranges cannot wrap through narrowing.
Floating binary access preserves the raw binary64 bit pattern.

**हिन्दी:** endianness तर्क `तार्किक` है। `सत्य` big-endian और `असत्य` little-endian। offset से आठ bytes चाहिए। alignment जरूरी नहीं। लिखने से पहले सीमा जाँची जाती है, इसलिए विफल write बफ़र नहीं बदलता। offset wrap नहीं होता। binary64 का कच्चा बिट क्रम बचता है।

Buffers share the collection iteration rules for arrays: replacement is allowed
and subsequent reads see the current contents. Copies are independent; ordinary
buffer assignment and casts retain the same reference. Buffers do not resize.
Runtime bounds, conversion, enum-code, cast and UTF-8 errors are all catchable as
`अपवाद`.

**हिन्दी:** बफ़र में सरणी की तरह बदलने पर अगली पढ़ाई नया byte देखती है। प्रतियाँ स्वतंत्र हैं। assignment/cast उसी संदर्भ को रखते हैं। आकार नहीं बदलता। सीमा, रूपांतरण, enum code, cast और UTF-8 त्रुटियाँ `अपवाद` से पकड़ी जा सकती हैं।

## Build and run

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/types
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/types --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/types --target wasm-browser -o target/types-web
```

Serve browser output over HTTP as described in [browser setup](wasm-browser.md).

**हिन्दी:** [ब्राउज़र निर्देश](wasm-browser.md) के अनुसार HTTP से परोसें।
