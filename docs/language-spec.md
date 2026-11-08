# Bhash / भाष — v0.9 language definition

Status: v0.9 core implemented on JVM, .NET 10 CLR and browser WebAssembly, open to revision. `Bhash.g4` defines
syntax. `compile` and `run` enforce the semantic rules below; `check` remains a
syntax-only command. JVM output uses checked Java source lowering; CLR output
uses checked C# source lowering. The browser target reuses CLR lowering with the
.NET WebAssembly runtime. All targets share one semantic checker. Browser input
is supplied before execution; see [browser target](wasm-browser.md).

**हिन्दी:** `Bhash.g4` वाक्यरचना तय करती है। `compile` और `run` अर्थ के नियम जाँचते हैं और `check` केवल वाक्यरचना देखता है। JVM के लिए जाँचा Java, CLR के लिए C# और ब्राउज़र के लिए उसी CLR lowering के साथ .NET WebAssembly रनटाइम उपयोग होता है। सभी लक्ष्य साझा अर्थ-जाँचकर्ता उपयोग करते हैं। [ब्राउज़र](wasm-browser.md) का इनपुट पहले दें।

The experimental native Linux backend supports only a core subset; it does not yet
implement the full v0.9 language. See [native profile and runtime design](native-compilation-design.md).

**हिन्दी:** नेटिव लक्ष्य का वर्तमान दायरा और कार्यान्वयन [नेटिव डिज़ाइन](native-compilation-design.md) तथा [वितरण निर्देश](native-releases.md) में देखें। ऊपर की managed भाषा स्थिति पुराने नेटिव milestone का वर्णन करती है।

## 1. Source and lexical rules

Function declarations use `फलन`; collection iteration is
`प्रत्येक (sourceExpression में element: Type[, value: Type])`.
The previous keyword and binding-first loop forms are no longer accepted.
See [Hindi syntax and migration examples](hindi-syntax.md).

**हिन्दी:** फलन के लिए `फलन` और संग्रह के लिए `प्रत्येक (संग्रह में तत्व: प्रकार[, मान: प्रकार])` लिखें। पुराने कीवर्ड और पहले बंधन वाला क्रम अस्वीकार हैं। [वाक्यरचना और माइग्रेशन](hindi-syntax.md) देखें।

- UTF-8 source files; `.bhash` extension. The CLI accepts one optional leading BOM.

  **हिन्दी:** UTF-8 `.bhash` फ़ाइल उपयोग करें। शुरुआत में एक वैकल्पिक BOM स्वीकार है।
- Keywords and identifiers are case-sensitive and use Devanagari. No English keyword aliases.

  **हिन्दी:** कीवर्ड और नाम देवनागरी में हैं। अंग्रेज़ी कीवर्ड उपनाम उपलब्ध नहीं हैं।
- Identifiers use the pinned Unicode 18.0.0 `bhash-devanagari-1` profile with
  102 starting characters, 205 unconditional continuation characters, and two
  contextual joiners. ASCII digits, Devanagari digits and underscore continue
  names. U+0953/U+0954 remain explicit continuation exceptions.

  **हिन्दी:** पिन किए Unicode 18.0.0 `bhash-devanagari-1` में १०२ प्रारंभ, २०५ सामान्य अनुवर्ती वर्ण और दो संदर्भाधीन जोड़-चिह्न हैं। ASCII/देवनागरी अंक और `_` नाम में आगे आ सकते हैं। U+0953/U+0954 स्पष्ट अनुवर्ती अपवाद हैं।
- ZWJ/ZWNJ are valid only after a virama preceded by an accepted letter and
  optional nukta, and immediately before an accepted letter. Valid joiners are
  removed from identifier comparison spelling before NFC. Bidi controls and
  other default-ignorables are rejected in names. Canonical keyword spellings
  retain their keyword token type and original source spelling.
  See the [identifier decisions](unicode-identifiers-decisions.md),
  [exact generated ranges](identifier-profile.json), and
  [Hindi guide](../tutorial/unicode-identifiers/README.md).

  **हिन्दी:** ZWJ/ZWNJ से पहले मान्य अक्षर, वैकल्पिक नुक़्ता और हलन्त हों तथा तुरंत बाद मान्य अक्षर हो। वैध जोड़-चिह्न पहचान तुलना से NFC से पहले हटते हैं। bidi controls और अन्य अदृश्य default-ignorable वर्ण अस्वीकार हैं। कीवर्ड का प्रकार और मूल वर्तनी बचती है। [हिन्दी मार्गदर्शिका](../tutorial/unicode-identifiers/README.md) और [सटीक सूची](identifier-profile.json) देखें।
- Symbol tables must use NFC-normalized identifier keys via `Identifiers.key`.
  Canonically equivalent names refer to the same symbol and duplicate definitions
  are rejected. The parser preserves original token spelling;
  strings and comments are not normalized as a side effect of name handling.

  **हिन्दी:** symbol table `Identifiers.key` से NFC नाम रखती है। समतुल्य वर्तनी एक ही नाम है और दोहरी घोषणा अस्वीकार है। parser मूल token वर्तनी रखता है। पाठ literals और टिप्पणियाँ नहीं बदलतीं।
- Numeric literals use ASCII digits (`123`) or Devanagari digits (`१२३`).
  A single literal cannot mix scripts. Different literals in one file may use either.

  **हिन्दी:** एक संख्या में या तो ASCII `123` या देवनागरी `१२३` लिखें। अलग संख्याएँ अलग लिपि में हो सकती हैं।
- Integers are decimal digits. Real literals require digits on both sides of `.`:
  `१२.५`, `12.5`. No exponent, radix prefix or digit separator in v0.3.

  **हिन्दी:** पूर्णांक दशाधार अंक हैं। भिन्न में `.` के दोनों ओर अंक दें। literal में exponent, radix prefix और अंक विभाजक नहीं हैं।
- String literals use double quotes and allow arbitrary Unicode text. Escapes:
  `\"`, `\\`, `\n`, `\r`, `\t`, and `\uXXXX` with four hexadecimal digits.
  Literal newlines in strings are forbidden. The semantic literal decoder
  rejects unpaired surrogate escapes; supplementary characters can be typed
  directly or represented with a valid escaped surrogate pair.

  **हिन्दी:** पाठ दोहरे उद्धरणों में है। ऊपर दिए escapes मान्य हैं। सीधी नई पंक्ति और अकेला surrogate escape अमान्य हैं। पूरक Unicode वर्ण सीधे या वैध surrogate जोड़े से लिखें।
- Whitespace is insignificant. `//` begins a line comment; `/* ... */` is a
  non-nesting block comment. Unterminated comments and invalid tokens are errors.

  **हिन्दी:** खाली जगह महत्वहीन है। `//` पंक्ति टिप्पणी और `/* ... */` बिना nesting की टिप्पणी है। अधूरी टिप्पणी और अमान्य token त्रुटि हैं।
- Braces delimit blocks; semicolons terminate simple statements. Danda `।`
  does not substitute for a semicolon.

  **हिन्दी:** ब्रेस ब्लॉक बनाते हैं। सरल कथन अर्धविराम से समाप्त हों। दंड `।` उसका विकल्प नहीं है।

## 2. Vocabulary

| Spelling | Role | Example |
| --- | --- | --- |
| `संकुल` | Package declaration | `संकुल संस्था.गणित;` |
| `आयात` / `उपनाम` | Import / import alias | `आयात संस्था.गणित.गणक उपनाम गणना;` |
| `प्रत्येक` / `में` | Collection iteration | `प्रत्येक (अंक में अ: पूर्णांक) { ... }` |
| `फलन` | Function declaration | `फलन जोड़(...) -> पूर्णांक { ... }` |
| `अनुबंध` / `पालन` | Interface / implementation | `वर्ग क पालन ख { ... }` |
| `साझा` | Static member | `साझा मान गिनती: पूर्णांक = ०;` |
| `मान` | Mutable binding | `मान गिनती: पूर्णांक = ०;` |
| `स्थिर` | Immutable binding | `स्थिर सीमा: पूर्णांक = १०;` |
| `यदि` / `अन्यथा` | If / else | `यदि (सत्य) { ... } अन्यथा { ... }` |
| `चयन` / `विकल्प` / `शेष` | Selection / case / default | `चयन (संख्या) { विकल्प १: ... शेष: ... }` |
| `प्रयास` / `पकड़ो` / `अंततः` | Try / catch / finally | `प्रयास { ... } पकड़ो (ग: अपवाद) { ... } अंततः { ... }` |
| `फेंको` | Throw / rethrow | `फेंको नया अपवाद("त्रुटि");` / `फेंको;` |
| `अपवाद` | Predeclared exception base class | `गलती.संदेश` |
| `जबतक` | While | `जबतक (गिनती < सीमा) { ... }` |
| `लौटाओ` | Return | `लौटाओ परिणाम;` |
| `विराम` / `जारी` | Break / continue | `विराम;` |
| `लिखो` | Console output statement | `लिखो("उत्तर", परिणाम);` |
| `पढ़ो` | Predeclared integer input function | `स्थिर संख्या: पूर्णांक = पढ़ो();` |
| `बाइट` / `लघुपूर्णांक` | Unsigned 8-bit / signed 32-bit | `परिवर्तित<बाइट>(२५५)` |
| `परिगणना` / `अभिलेख` | Enum / immutable record | `अभिलेख बिंदु(अ: पूर्णांक);` |
| `सर्ववस्तु` | General boxed/reference value | `स्थिर क: सर्ववस्तु = १;` |
| `परिवर्तित` / `प्रकारहै` / `सुरक्षितरूप` | Convert/cast / type test / safe reference cast | `प्रकारहै<बिंदु>(क)` |
| `बाइटबफ़र` | Packed mutable byte sequence | `नया बाइटबफ़र(८)` |
| `पूर्णांक` | Signed 64-bit integer | `४२` |
| `दशमलव` | IEEE 754 binary64 floating-point | `३.१४` |
| `तार्किक` | Boolean | `सत्य`, `असत्य` |
| `पाठ` | Immutable Unicode string | `"भाष"` |
| `शून्य` | No return value (void) | `फलन मुख्य() -> शून्य { ... }` |

`दशमलव` is the proposed spelling for binary floating point, not exact decimal
arithmetic; revisit the name if exact decimal becomes a language requirement.
`शून्य` is a return-type marker, not null and not a value or variable type.

**हिन्दी:** `दशमलव` binary floating point है। `शून्य` बिना वापसी मान का प्रकार चिह्न है। null के लिए `रिक्त` है।

## 3. Functions and program structure

```text
फलन जोड़(पहला: पूर्णांक, दूसरा: पूर्णांक) -> पूर्णांक {
    लौटाओ पहला + दूसरा;
}

फलन मुख्य() -> शून्य {
    लिखो(जोड़(२, ३));
}
```

A compilation unit has an optional `संकुल` declaration, then zero or more `आयात`
declarations, then top-level functions, classes and interfaces. Project builds collect
all source files from `bhash.project` source roots. Top-level declarations are
package-private unless marked `सार्वजनिक`; imports are scoped to their file.
Qualified names and `उपनाम` aliases resolve cross-file references. See the
[project and package rules](projects-packages.md) for source discovery, resolution,
visibility and commands. No top-level executable statements, globals or nested
class declarations are supported. Executables must define exactly one `मुख्य`
returning `शून्य` across the whole project. It can take zero parameters or one
`पाठ[]` parameter containing the [command-line arguments](cli.md#command-line-arguments).
Empty source is syntactically valid but is not a
valid executable. Functions may overload by parameter types; return-type-only overloads are rejected. Classes have their own
member scopes, described in [Objects and inheritance](objects.md). Forward calls
and recursion are permitted; collect function signatures before resolving bodies.
All parameters and return types are explicit. Parameters are immutable bindings.
Arguments evaluate left to right and must match the signature in count and type.

**हिन्दी:** फ़ाइल में पहले वैकल्पिक `संकुल`, फिर `आयात`, फिर फलन, वर्ग या अनुबंध घोषणाएँ हैं। परियोजना `bhash.project` के स्रोत roots इकट्ठे करती है। सार्वजनिक चिह्न के बिना घोषणा संकुल तक और आयात फ़ाइल तक है। पूरे नाम और `उपनाम` से दूसरी फ़ाइल का नाम लें। ऊपरी स्तर पर executable कथन, globals और भीतर के वर्ग नहीं हैं। परियोजना में ठीक एक `मुख्य` चाहिए। उसका परिणाम `शून्य` है और वह बिना मापदंड या एक `पाठ[]` मापदंड के साथ लिखा जा सकता है। खाली फ़ाइल parse हो सकती है लेकिन ऐप नहीं है। ओवरलोड मापदंड से अलग हों। आगे घोषित फलन और पुनरावृत्ति मान्य हैं। प्रकार स्पष्ट हैं, मापदंड अचल हैं और तर्क बाएँ से दाएँ चलते हैं। [परियोजना नियम](projects-packages.md) देखें।

## 4. Bindings, scope and control flow

Local bindings require a type and initializer. Class fields follow the object-model
initialization rules. `मान` allows later assignment;
`स्थिर` prevents rebinding but is not restricted to compile-time constants.
Blocks introduce lexical scopes. Duplicate names within one scope, including
parameters in a function's outermost body scope, are errors. A nested block may
shadow an outer binding. A variable is not in scope within its own initializer.

**हिन्दी:** स्थानीय बंधन को प्रकार और आरंभक चाहिए। `मान` दोबारा लिखा जा सकता है। `स्थिर` का आरंभक केवल compile-time constant होना जरूरी नहीं। ब्लॉक अपना दायरा बनाता है। एक ही दायरे में समान नाम, मापदंड समेत, त्रुटि है। अंदर का ब्लॉक बाहरी नाम ढक सकता है। आरंभक में उसका अपना नाम नहीं दिखता।

Conditions must be `तार्किक`; integers are not truthy. `विराम` exits the nearest
loop or selection. `जारी` is legal only within a loop and continues the innermost loop. A void function may
fall through or use `लौटाओ;`. A value-returning function must return a compatible
value on every reachable path. Analysis conservatively assumes a loop can execute
zero times, even for a literal true condition. Code after an unconditional return,
break or continue is rejected as unreachable. Only function calls may be standalone expression
statements, although the grammar intentionally accepts a wider expression here.
Assignments are statements, not expressions; targets may be mutable bindings, fields
or checked collection indices.

**हिन्दी:** शर्त `तार्किक` हो। `विराम` निकटतम चक्र या चयन छोड़ता है। `जारी` भीतर के चक्र को आगे बढ़ाता है। `शून्य` फलन अंत तक आ या `लौटाओ;` कर सकता है। मान लौटाने वाला फलन हर पहुँच योग्य रास्ते पर संगत मान लौटाए। चक्र को शून्य बार चल सकने वाला माना जाता है। बिना शर्त jump के बाद का कथन त्रुटि है। व्यंजक कथन में केवल फलन कॉल है। assignment कथन है और बदलने वाले बंधन, क्षेत्र या जाँचे index में जाता है।

## 5. Operators and portable behavior

Precedence, highest to lowest; binary operators associate left to right:

**हिन्दी:** ऊपर से नीचे घटती प्राथमिकता है। समान स्तर की द्विआधारी संक्रियाएँ बाएँ से दाएँ जुड़ती हैं:

| Level | Operators | Meaning |
| --- | --- | --- |
| 1 | `(...)`, named function calls | Grouping / invocation |
| 2 | `!`, `~`, unary `-`, unary `+` | Boolean negation / bitwise complement / numeric sign |
| 3 | `*`, `/`, `%` | Multiply / divide / remainder |
| 4 | `+`, `-` | Add / subtract |
| 5 | `<<`, `>>`, `>>>` | Left / arithmetic right / logical right shift |
| 6 | `<`, `<=`, `>`, `>=` | Numeric comparison |
| 7 | `==`, `!=` | Value equality |
| 8 | `&` | Bitwise AND |
| 9 | `^` | Bitwise XOR |
| 10 | `\|` | Bitwise OR |
| 11 | `&&` | Short-circuit AND |
| 12 | `\|\|` | Short-circuit OR |

- Integer/real mixing requires `परिवर्तित<T>(value)`. `बाइट` and `लघुपूर्णांक`
  operands promote to `पूर्णांक` in integer expressions; assignment/argument
  conversions remain explicit. See [conversion rules](types-conversions.md).

  **हिन्दी:** पूर्णांक और दशमलव मिलाने से पहले `परिवर्तित<T>` दें। गणना में `बाइट` और `लघुपूर्णांक` `पूर्णांक` बनते हैं, लेकिन assignment और तर्क के लिए स्पष्ट रूपांतरण चाहिए।
- Integer range: −2^63 through 2^63−1. Out-of-range literals are compile-time
  errors. The constant checker handles unary minus before range validation
  so that the minimum signed integer can be expressed.

  **हिन्दी:** सीमा −2^63 से 2^63−1 तक है। बाहर के literal संकलन त्रुटि हैं। न्यूनतम मान लिखने के लिए unary minus सीमा से पहले जाँचा जाता है।
- Integer arithmetic wraps in two's complement. Integer division truncates
  toward zero; remainder has the dividend's sign. Division/remainder by zero
  terminates with a runtime error. MIN / −1 yields MIN; MIN % −1 yields zero.

  **हिन्दी:** पूर्णांक two's complement में wrap होते हैं। भाग शून्य की ओर कटता है और शेष का चिह्न भाज्य जैसा है। शून्य से भाग/शेष त्रुटि है। MIN / −1 = MIN और MIN % −1 = 0 है।
- `&`, `|`, `^`, `~`, `<<`, `>>` and `>>>` accept integers only. Bitwise results
  preserve 64-bit two's-complement representation. Shift distance is `count & 63`,
  including negative and wide counts. `>>` sign-extends; `>>>` zero-fills, while
  retaining a signed result (a zero-distance shift preserves its input).
  Shift characters must be adjacent. Nested generic closers remain supported.
  See [bitwise rules and the bitboard example](bitwise.md).

  **हिन्दी:** ऊपर की bitwise संक्रियाएँ पूर्णांक पर हैं। 64-bit बिट क्रम रहता है। shift दूरी `count & 63` है। `>>` चिह्न बढ़ाता और `>>>` शून्य भरता है। परिणाम signed है। shift चिह्न साथ लिखें। सामान्य प्रकार के समापन मान्य हैं। [विस्तार](bitwise.md) देखें।
- Real operations use binary64; division by zero and NaN follow IEEE 754.
  `%` is integer-only. Native backends must avoid unsafe fast-math assumptions.

  **हिन्दी:** दशमलव binary64 और IEEE 754 का पालन करता है। `%` पूर्णांक के लिए है। नेटिव में असुरक्षित fast-math नहीं मानते।
- `&&` and `||` evaluate the right operand only when needed. Other operands
  evaluate left to right. `!` accepts booleans only; unary signs accept numbers.

  **हिन्दी:** `&&` और `||` दायाँ operand केवल जरूरत पर चलाते हैं। बाकी operands बाएँ से दाएँ हैं। `!` तार्किक और unary चिह्न संख्या पर हैं।
- Ordering comparisons require numbers. Equality accepts matching numeric,
  boolean or text types. Text equality compares Unicode scalar sequences with
  no normalization; no implicit string concatenation or numeric coercion.

  **हिन्दी:** क्रम तुलना संख्या पर है। समानता संगत संख्या, तार्किक और पाठ पर है। पाठ का Unicode क्रम बिना सामान्यीकरण मिलता है। संख्यात्मक coercion अपने आप नहीं है।
- Chained comparisons such as `१ < २ < ३` parse, but fail type checking because
  the first comparison produces a boolean. Use `१ < २ && २ < ३`.

  **हिन्दी:** `१ < २ < ३` में पहली तुलना तार्किक बनाती है। `१ < २ && २ < ३` लिखें।

These choices need explicit backend conformance tests; host-platform defaults
are not sufficient (notably native overflow and WASM signed division overflow).

**हिन्दी:** साझा व्यवहार backend परीक्षण से जाँचें। होस्ट के डिफ़ॉल्ट से नेटिव overflow और WASM signed division overflow जैसे नियम अपने आप तय नहीं होते।

## 6. Runtime surface

`पढ़ो()` is a predeclared, zero-argument function returning `पूर्णांक`. Its name
cannot be redeclared as a function. Each call reads one UTF-8 line from stdin,
strips leading/trailing whitespace using Java `String.strip`, and accepts an
optional ASCII `+` or `-` followed by one or more ASCII digits or one or more
Devanagari digits. Scripts cannot be mixed within an input integer. Empty lines,
multiple values on one line, malformed UTF-8, EOF and out-of-range values terminate
the program with a concise error on stderr and exit status 1. Multiple calls
consume successive lines. This is integer input only, not arbitrary text input.

**हिन्दी:** बिना तर्क का आरक्षित `पढ़ो()` एक UTF-8 पंक्ति से `पूर्णांक` पढ़ता है। किनारे की खाली जगह हटती है। वैकल्पिक ASCII `+`/`-` और एक लिपि के अंक दें। खाली पंक्ति, अनेक मान, गलत UTF-8, EOF और सीमा बाहर पर त्रुटि आती है। अगली कॉल अगली पंक्ति लेती है। पाठ के लिए `पाठपढ़ो()` है।

`लिखो` accepts zero or more primitive arguments, prints them separated by a
single space, then appends a newline and flushes stdout. `लिखो();` prints a blank
line. Output is UTF-8. Integers use Devanagari digits; booleans use `सत्य` /
`असत्य`. Reals use Java 17 `Double.toString` formatting with digits replaced by
Devanagari digits; punctuation, exponent markers, `NaN` and `Infinity` are retained.
Text is printed unchanged. CLR uses .NET invariant round-trip formatting with the
same Devanagari digit, decimal-point and exponent presentation. Some binary64
values have different shortest decimal spellings under .NET and Java 17 (for
example values near 10^23); exact last-digit cross-target real display parity is
not yet guaranteed. Integer, boolean and text display match across both targets. Prompts should be printed before calling `पढ़ो()`.

**हिन्दी:** `लिखो` आदिम मानों को एक खाली जगह से अलग कर UTF-8 में छापता है और नई पंक्ति व flush देता है। खाली कॉल खाली पंक्ति देती है। संख्याओं में देवनागरी अंक और तार्किक में `सत्य`/`असत्य` हैं। पाठ जैसा है वैसा छपता है। JVM और CLR round-trip binary64 प्रदर्शन करते हैं। कुछ दशमलव के अंतिम अंक अलग हो सकते हैं। इनपुट से पहले संकेत लिखें।

The factorial sample validates 0–20 before computing with signed 64-bit integers.
Other integer arithmetic retains wrapping behavior; there is no arbitrary-precision
integer type yet.

**हिन्दी:** गुणनफल उदाहरण signed 64-bit गणना से पहले ०–२० सीमा जाँचता है। सामान्य गणना wrap होती है। मनमानी सटीकता वाला पूर्णांक अभी नहीं है।

Strings are immutable sequences of Unicode scalars; their physical representation
may differ by backend. Text has no indexing or character type yet. Manual memory-management syntax is not implemented. Class-based objects and host
garbage collection are described below.

**हिन्दी:** पाठ Unicode scalar का अचल क्रम है। backend उसका अलग भौतिक रूप रख सकता है। पाठ index या अलग character प्रकार नहीं है। स्मृति अपने आप सँभलती है।

## 7. Object model

Classes, fields, constructors, instance methods, single inheritance, explicit virtual
method overrides, public/private/protected access and nullable class references are
implemented on both managed targets. Class types can appear in locals, fields,
parameters and return positions; derived-to-base assignment is implicit. Text is
still a primitive value, while class equality uses reference identity.

**हिन्दी:** वर्गों में क्षेत्र, निर्माता, instance विधि, एकल विरासत, स्पष्ट अधिलेखन, पहुँच और nullable संदर्भ हैं। वर्ग प्रकार बंधन, क्षेत्र, मापदंड और परिणाम में हैं। व्युत्पन्न से मूल assignment अपने आप है। पाठ आदिम है, वर्ग समानता पहचान से है।

New reserved keywords: `वर्ग`, `विस्तार`, `निर्माता`, `नया`, `यह`, `आधार`,
`सार्वजनिक`, `निजी`, `संरक्षित`, `अधिलेखन` and `रिक्त`. Existing `फलन`, `मान`
and `स्थिर` are reused for members. `स्थिर` denotes immutability, not static storage.
All detailed syntax, initialization order, constructor-safety constraints and access
rules are specified in [Objects and inheritance](objects.md).

**हिन्दी:** सदस्यों में `फलन`, `मान` और `स्थिर` फिर उपयोग होते हैं। `स्थिर` अचलता और `साझा` वर्ग भंडार बताता है। घोषणा, आरंभ और पहुँच के लिए [वस्तु नियम](objects.md) देखें।

## 8. Interfaces, generics, overloads and static members

`अनुबंध` declares an interface; classes use `पालन` to implement one or more contracts.
`साझा` marks static fields/methods. Classes, interfaces and global functions support explicit
type parameters and arguments (`पात्र<पूर्णांक>`, `पहचान<पाठ>("नमस्ते")`). Functions,
methods and constructors can overload by their parameter type lists. All targets use the
same overload resolution, specialization and interface-validation rules.
See [the v0.3 specification](contracts-generics.md) for exact syntax and limits.

**हिन्दी:** `अनुबंध` से interface और `पालन` से कार्यान्वयन है। `साझा` static सदस्य है। वर्ग, अनुबंध और फलन में स्पष्ट प्रकार मापदंड और तर्क हैं। फलन, विधि और निर्माता मापदंड प्रकार से ओवरलोड हो सकते हैं। [नियम](contracts-generics.md) देखें।

## 9. Selection statements

`चयन (expression) { विकल्प literal: statements शेष: statements }` supports integer,
text and boolean selectors. The selector evaluates once; sections have independent scopes
and do not fall through. Consecutive case labels may share a body. `विराम` exits the
nearest loop or selection; `जारी` continues the enclosing loop. See [selection rules](switch.md).

**हिन्दी:** `चयन` में पूर्णांक, पाठ और तार्किक selector एक बार चलता है। खंड के अपने दायरे हैं। अगला खंड अपने आप नहीं चलता। पास-पास लेबल शरीर साझा करते हैं। `विराम` निकटतम चक्र या चयन और `जारी` निकटतम चक्र पर लागू है।

## 10. Arrays, collections and iteration

`त[]` (also `सरणी<त>`) is an invariant fixed-length array. `सूची<त>` and
`मानचित्र<क,व>` are predeclared invariant reference types. Collections support
indexing, checked writes and typed initializers; lists grow and maps preserve
insertion order. `प्रत्येक (source में element: type)` iterates elements or map
keys, and a second typed binding iterates map values alongside their keys.
See [v0.7 collection rules](collections.md) for APIs, equality, mutation checks,
array dimensions, evaluation order and multi-file examples.

**हिन्दी:** `त[]`/`सरणी<त>` तय आकार की invariant सरणी है। सूची बढ़ती है और मानचित्र जोड़ने का क्रम रखता है। index और writes जाँचे जाते हैं। `प्रत्येक` में एक बंधन तत्व या कुंजी तथा दूसरा साथ का मान लेता है। [संग्रह नियम](collections.md) देखें।

## 11. Exception handling

`प्रयास block पकड़ो (name: type) block ... अंततः block` supports ordered typed
handlers and optional cleanup; try/finally without a catch is also valid.
`अपवाद` is the predeclared exception base class with a readonly `संदेश: पाठ`.
`फेंको expression;` throws an exception; `फेंको;` inside a catch rethrows its
current exception. Runtime failures are also catchable as `अपवाद`.
Exceptions are unchecked. Finally executes across normal and abrupt completion;
control cannot return or jump out of finally. See [exception semantics](exceptions.md)
for subtype matching, scopes, replacement exceptions and host limitations.

**हिन्दी:** `प्रयास`, प्रकारयुक्त `पकड़ो` और वैकल्पिक `अंततः` उपलब्ध हैं। बिना `पकड़ो` का प्रयास/अंततः मान्य है। `अपवाद.संदेश` अचल है। `फेंको;` handler का वही अपवाद फिर भेजता है। रनटाइम विफलता पकड़ सकते हैं। अपवाद हस्ताक्षर में घोषित करना जरूरी नहीं। `अंततः` सामान्य और अचानक समापन पर चलता है। उससे बाहर jump या return नहीं है।

## 12. Types, conversions and binary data

`बाइट` is unsigned 8-bit and `लघुपूर्णांक` is signed 32-bit. Explicit numeric
conversions are checked; fractional-to-integer conversions truncate toward zero.
Small integral expression operands promote to `पूर्णांक`; assignments and
arguments still require explicit conversion. `सर्ववस्तु` stores boxed scalars
and references while preserving runtime type identity. `प्रकारहै<T>`,
`परिवर्तित<T>` and `सुरक्षितरूप<T>` implement tests, checked conversions/casts
and nullable reference casts, including invariant generic collections.

**हिन्दी:** `बाइट` unsigned 8-bit और `लघुपूर्णांक` signed 32-bit है। रूपांतरण सीमा जाँचते हैं। भिन्न से पूर्णांक शून्य की ओर कटता है। गणना में छोटे operands `पूर्णांक` बनते हैं। `सर्ववस्तु` वास्तविक प्रकार बचाता है। `प्रकारहै<T>`, `परिवर्तित<T>` और `सुरक्षितरूप<T>` जाँच और casts हैं।

`परिगणना` introduces typed enum members/codes and named switch labels.
`अभिलेख` introduces sealed immutable records with generic parameters,
structural equality and hashing. `बाइटबफ़र` provides packed bytes, indexed
access/iteration, binary64/signed-64 binary reads/writes with explicit endianness,
copying and strict UTF-8 encoding/decoding. See [complete type rules](types-conversions.md).

**हिन्दी:** `परिगणना` नामित मान, code और typed चयन देती है। `अभिलेख` sealed अचल प्रकार, सामान्य मापदंड और संरचनात्मक समानता/hash देता है। `बाइटबफ़र` packed bytes, अनुक्रमण, चक्र, byte order वाले 64-bit reads/writes, प्रतियाँ और सख्त UTF-8 देता है। [प्रकार नियम](types-conversions.md) देखें।

## 13. Resource scopes

`संसाधन (name: Type = expression) { ... }` introduces an immutable local resource
binding. `संसाधन` is reserved. Evaluate the initializer once, check assignment
compatibility and reject null before entering the body. Type must expose an
accessible instance `बंदकरो() -> शून्य`; `मानक.बंदनीय` supplies the common interface.
The binding is visible only in the body, not its initializer or subsequent code.

**हिन्दी:** `संसाधन (नाम: प्रकार = व्यंजक) { ... }` स्थानीय अचल बंधन है। आरंभक एक बार चले, प्रकार संगत और परिणाम non-null हो। पहुँच योग्य instance `बंदकरो() -> शून्य` चाहिए। `मानक.बंदनीय` सामान्य अनुबंध है। नाम केवल शरीर में है।

Invoke close when the body exits normally, returns, breaks, continues or throws.
An initializer that fails has acquired no resource to close. Nested scopes close
in reverse order. Cleanup follows the existing `अंततः` exception-precedence rule:
a close failure replaces a pending exception or return; there is no suppressed
exception list in this edition. Fatal process/host failures are outside the
cleanup guarantee. See [application foundations](application-foundations.md) for
stream ownership, GC-root handling and target restrictions.

**हिन्दी:** सामान्य समापन, वापसी, विराम, जारी और अपवाद पर बंद करें। विफल आरंभक का संसाधन नहीं है। अंदर से बाहर उलटे क्रम में सफ़ाई है। `अंततः` की तरह सफ़ाई का अपवाद लंबित वापसी या अपवाद बदल देता है। fatal host failure में गारंटी नहीं है।

## 14. Deferred design questions

Confirm vocabulary; choose exact real formatting; extend dependency package distribution;
extend records and generic member methods; extend exception metadata; settle WASI vs browser environments and native OS/ABI support; define
foreign-function interfaces separately for each platform. Do not grow the grammar
to cover these until their semantics and tests are agreed.

**हिन्दी:** आगे के निर्णयों में शब्दावली, सटीक दशमलव प्रदर्शन, संकुल वितरण, अभिलेख और सामान्य विधियाँ, अपवाद metadata, WASI, OS/ABI और बाहरी भाषा interfaces हैं। अर्थ और परीक्षण तय होने पर ही grammar बढ़ाएँ।


## Function values, lambdas and closures

Function types, capture semantics and cross-target lowering are specified in [function-values.md](function-values.md). See the [Hindi tutorial](../tutorial/functions/README.md) for executable examples.

**हिन्दी:** [फलन मान](function-values.md) में फलन प्रकार, capture और विभिन्न लक्ष्य की lowering है। [हिन्दी ट्यूटोरियल](../tutorial/functions/README.md) चलाएँ।

## Async functions and tasks

The task-core contract is specified in [task-core-implementation.md](task-core-implementation.md).
An `असमकालिक फलन` returns `कार्यफल<T>` to callers while its body returns T.
`प्रतीक्षा` suspends only inside async bodies. An async `मुख्य` drives
structured task completion. `असमकालिक संसाधन` awaits `बंदकरो()->कार्यफल<शून्य>`;
both sync and async resources drain children before disposal. See the
[Hindi tutorial](../tutorial/async/README.md) and [approved design](async-await-design.md).

**हिन्दी:** `असमकालिक फलन` के शरीर से T और कॉल से `कार्यफल<T>` मिलता है। `प्रतीक्षा` async शरीर रोकती और बाद में आगे बढ़ाती है। async `मुख्य` कार्यों का समापन चलाता है। `असमकालिक संसाधन` `बंदकरो()->कार्यफल<शून्य>` की प्रतीक्षा करता है। दोनों तरह के संसाधन सफ़ाई से पहले अपने बाल कार्य पूरे करते हैं। [हिन्दी अभ्यास](../tutorial/async/README.md) देखें।
