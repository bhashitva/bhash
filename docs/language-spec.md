# Bhash / भाष — current language specification

Reviewed against main on 2026-11-22, including standalone compiler 0.28.0.
Extension and Jupyter package versions are independent; see [Downloads](../DOWNLOADS.md).
Older v0.3–v0.9 labels in linked feature documents identify historical milestones,
not the current compiler release.

**हिन्दी:** यह विनिर्देश २२ नवम्बर २०२६ के main स्रोत और standalone compiler
0.28.0 की सुविधाएँ बताता है। VS Code और Jupyter के संस्करण अलग हैं। पुराने
सुविधा-पृष्ठों के v0.3–v0.9 नाम ऐतिहासिक चरण हैं, वर्तमान compiler संस्करण नहीं।

[`Bhash.g4`](../src/main/antlr4/Bhash.g4) defines syntax. `compile` and `run`
validate names, types and control flow; `check` validates syntax only. JVM output
uses Java source lowering; CLR uses C#; `wasm-browser` uses CLR lowering and the
.NET WebAssembly runtime. Native compilation uses LLVM IR and the bundled Bhash
runtime. Shared checking/lowering defines the language contract; hosting and
library availability still depend on the target.

**हिन्दी:** grammar वाक्यरचना तय करती है। `check` केवल वाक्यरचना और `compile`/
`run` नाम, प्रकार तथा नियंत्रण-प्रवाह भी जाँचते हैं। JVM के लिए Java, CLR के
लिए C#, browser के लिए .NET WebAssembly और native के लिए LLVM IR बनता है। भाषा
के नियम साझा हैं; होस्ट और पुस्तकालय उपलब्धता लक्ष्य के अनुसार जाँचें।

| Target / लक्ष्य | Execution and scope / निष्पादन और दायरा |
| --- | --- |
| `jvm` | Java 17+; generated application requires a JVM / ऐप को JVM चाहिए। |
| `clr` | .NET 10; generated application requires its .NET runtime / ऐप को .NET चाहिए। |
| `native` | Linux x86-64/glibc, Windows x64/UCRT, macOS Apple Silicon ARM64; executables need neither JVM nor .NET / executable को JVM या .NET नहीं चाहिए। |
| `wasm-browser` | .NET WebAssembly; prepared input and restricted desktop filesystem/server/database access / इनपुट पहले दें; desktop फ़ाइल, सर्वर और डेटाबेस की सीमाएँ हैं। |

Native supports objects, interfaces, generics, collections, exceptions, closures,
async, null safety and tagged unions; it is no longer the initial Linux integer/
literal-text subset. The compiler itself still needs Java. Native builds require
a target toolchain; selected libraries require providers or system dependencies.
Android libraries have a separate C/JNI boundary, not unrestricted desktop API
availability. See [native distribution](native-releases.md), [Windows](native-windows.md),
[macOS](native-macos.md), [Android](android-native-mobile-design.md) and
[browser hosting](wasm-browser.md).

**हिन्दी:** native अब केवल पूर्णांक और literal पाठ वाला Linux प्रयोग नहीं है।
वर्ग, अनुबंध, generics, संग्रह, अपवाद, closures, async, रिक्त-सुरक्षा और
विकल्पसमूह उपलब्ध हैं। Compiler को Java और native निर्माण को लक्ष्य का
toolchain चाहिए। कुछ पुस्तकालय provider/system dependency माँगते हैं। Android
की C/JNI सीमा अलग है; सभी desktop API वहाँ उपलब्ध न मानें।

## 1. Source and lexical rules

Function declarations use `फलन`; collection iteration is
`प्रत्येक (sourceExpression में element: Type[, value: Type])`.
The previous `कार्य` function keyword and binding-first loop forms are no longer accepted.
See [Hindi syntax and migration examples](hindi-syntax.md).

**हिन्दी:** फलन के लिए `फलन` और संग्रह के लिए `प्रत्येक (संग्रह में तत्व: प्रकार[, मान: प्रकार])` लिखें। पुराना `कार्य` और पहले बंधन वाला क्रम अस्वीकार हैं। [वाक्यरचना और माइग्रेशन](hindi-syntax.md) देखें।

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
  `१२.५`, `12.5`. No exponent, radix prefix or digit separator is accepted.

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
| `वर्ग` / `विस्तार` | Class / single class inheritance | `वर्ग छात्र विस्तार व्यक्ति { ... }` |
| `निर्माता` / `नया` | Constructor / allocation | `निर्माता() { ... }`, `नया छात्र()` |
| `यह` / `आधार` | Current instance / base implementation | `यह.नाम`, `आधार.नाम()` |
| `सार्वजनिक` / `निजी` / `संरक्षित` | Access control | `निजी मान अंक: पूर्णांक;` |
| `अधिलेखन` | Explicit virtual override | `अधिलेखन फलन नाम() -> पाठ { ... }` |
| `रिक्त` | Absent value (null) | `स्थिर नाम: पाठ? = रिक्त;` |
| `सत्य` / `असत्य` | Boolean literals | `स्थिर तैयार: तार्किक = सत्य;` |
| `संसाधन` | Deterministic cleanup scope | `संसाधन (क: प्रकार = व्यंजक) { ... }` |
| `असमकालिक` / `प्रतीक्षा` | Async / await | `प्रतीक्षा मानक.कार्य.विलंब(१);` |
| `विकल्पसमूह` / `मिलान` | Contextual union / pattern matching | `विकल्पसमूह उत्तर { मिला(अंक: पूर्णांक); }` |
| `पाठपढ़ो` | Predeclared text input function | `स्थिर पंक्ति: पाठ = पाठपढ़ो();` |

`दशमलव` is IEEE 754 binary floating point, not an exact decimal or financial type.
`शून्य` is a return-type marker, not null and not a value or variable type.

**हिन्दी:** `दशमलव` binary floating point है। `शून्य` बिना वापसी मान का प्रकार चिह्न है। null के लिए `रिक्त` है।

The vocabulary includes reserved tokens and predeclared/contextual names.
`पढ़ो`, `पाठपढ़ो`, `अपवाद` and collection type names are predeclared;
`विकल्पसमूह` and `मिलान` are contextual identifiers, not globally reserved
keywords. `_` is a pattern wildcard, not a declaration name.

**हिन्दी:** सूची में reserved कीवर्ड के साथ पहले से उपलब्ध नाम भी हैं। `पढ़ो`,
`पाठपढ़ो`, `अपवाद` और संग्रह के नाम सामान्य reserved token नहीं हैं।
`विकल्पसमूह`/`मिलान` विशेष संदर्भ में पहचाने जाते हैं। `_` प्रतिरूप का wildcard है।

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
declarations, then top-level functions, classes, interfaces, enums, records and tagged unions. Project builds collect
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
Declared function/lambda parameters and return types are explicit. Local types
and generic call arguments may be inferred. Parameters are immutable bindings.
Arguments evaluate left to right and must match the signature in count and type.

**हिन्दी:** फ़ाइल में पहले वैकल्पिक `संकुल`, फिर `आयात`, फिर फलन, वर्ग या अनुबंध घोषणाएँ हैं। परियोजना `bhash.project` के स्रोत roots इकट्ठे करती है। सार्वजनिक चिह्न के बिना घोषणा संकुल तक और आयात फ़ाइल तक है। पूरे नाम और `उपनाम` से दूसरी फ़ाइल का नाम लें। ऊपरी स्तर पर executable कथन, globals और भीतर के वर्ग नहीं हैं। परियोजना में ठीक एक `मुख्य` चाहिए। उसका परिणाम `शून्य` है और वह बिना मापदंड या एक `पाठ[]` मापदंड के साथ लिखा जा सकता है। खाली फ़ाइल parse हो सकती है लेकिन ऐप नहीं है। ओवरलोड मापदंड से अलग हों। आगे घोषित फलन और पुनरावृत्ति मान्य हैं। प्रकार स्पष्ट हैं, मापदंड अचल हैं और तर्क बाएँ से दाएँ चलते हैं। [परियोजना नियम](projects-packages.md) देखें।

The entry point may also be `असमकालिक फलन मुख्य(...) -> शून्य`; its declared
return remains `शून्य`, while the host drives its task and structured cleanup.
Notebook/scratch hosts additionally support top-level executable statements and
a final expression. In Jupyter, reached top-level scalar bindings persist across
cells even when declared after executable statements; block locals remain local,
and early `लौटाओ;` skips unreached declarations. Non-scalar lifetime restrictions
are documented in the [kernel guide](../editors/jupyter/README.md).

**हिन्दी:** async मुख्य भी `शून्य` घोषित करता है; होस्ट उसका कार्य और सफ़ाई चलाता
है। Notebook/scratch में ऊपरी executable कथन और अंतिम व्यंजक भी मान्य हैं। Jupyter
में चले हुए ऊपरी scalar बंधन अगले cells में रहते हैं, भले वे कथन के बाद हों।
ब्लॉक का दायरा रहता है और जल्दी वापसी से छूटी घोषणा उपलब्ध नहीं होती। गैर-scalar
सीमाएँ kernel मार्गदर्शिका में हैं।

## 4. Bindings, scope and control flow

Local bindings require an initializer; an omitted type is inferred from it. Public signatures and fields
retain explicit types. Class fields follow the object-model
initialization rules. `मान` allows later assignment;
`स्थिर` prevents rebinding but is not restricted to compile-time constants.
Blocks introduce lexical scopes. Duplicate names within one scope, including
parameters in a function's outermost body scope, are errors. A nested block may
shadow an outer binding. A variable is not in scope within its own initializer.

**हिन्दी:** स्थानीय बंधन में आरंभक आवश्यक है। प्रकार छोड़ने पर उससे अनुमान होता है; केवल `रिक्त` पर्याप्त नहीं है। `मान` दोबारा लिखा जा सकता है; `स्थिर` नहीं। ब्लॉक अपना दायरा बनाता है और आरंभक में उसका अपना नाम नहीं दिखता। [निर्माता, generic methods और inference के नियम](../tutorial/safer-everyday/README.md) देखें।

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

An instance `स्थिर` field can have a declaration initializer or be assigned
exactly once on every successful constructor path in its declaring class.
Reading before assignment, reassigning it, assigning through another object or
from an ordinary method is rejected. Throwing paths need not finish initialization.
Base fields belong to their base constructor. Static immutable fields still need
declaration initializers. Field initializers remain constant expressions;
constructor bodies can allocate and call functions.

**हिन्दी:** अचल instance क्षेत्र को घोषणा में आरंभ करें या उसके अपने वर्ग के हर
सफल निर्माता-पथ में ठीक एक बार भरें। पहले पढ़ना, दोबारा लिखना, दूसरी वस्तु या
सामान्य विधि से बदलना अमान्य है। अपवाद से विफल पथ को आरंभ पूरा नहीं करना पड़ता।
मूल वर्ग अपना क्षेत्र भरता है। साझा अचल क्षेत्र को घोषणा में आरंभक चाहिए।
निर्माता के शरीर में allocation और calls कर सकते हैं।

```bhash
वर्ग व्यक्ति {
    निजी स्थिर नामपाठ: पाठ;
    निर्माता(नाम: पाठ) { यह.नामपाठ = नाम; }
    फलन नाम() -> पाठ { लौटाओ नामपाठ; }
}
फलन मुख्य() -> शून्य {
    स्थिर छात्र = नया व्यक्ति("सीमा");
    लिखो(छात्र.नाम());
}
```

## 5. Operators and portable behavior

Precedence, highest to lowest; binary operators associate left to right except
`??`, which associates right to left. Prefix unary operators nest from the right:

**हिन्दी:** ऊपर से नीचे घटती प्राथमिकता है। `??` दाएँ से बाएँ और अन्य द्विआधारी संक्रियाएँ बाएँ से दाएँ जुड़ती हैं। Unary उपसर्ग दाईं ओर भीतर जाते हैं:

| Level | Operators | Meaning |
| --- | --- | --- |
| 1 | `(...)`, calls, `.`, `?.`, `[...]` | Grouping, invocation, member/safe-member access, indexing |
| 2 | `!`, `~`, unary `-`, unary `+`, `प्रतीक्षा` | Negation / bitwise complement / sign / await |
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
| 13 | `??` | Lazy null coalescing; right-associative |

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
  raises a catchable `अपवाद`; an uncaught failure terminates execution. MIN / −1 yields MIN; MIN % −1 yields zero.

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
  no normalization. `पाठ + पाठ` concatenates text; other values are not implicitly
  converted to text. Records/unions have structural equality; ordinary class and
  collection references use identity. Boxing preserves exact scalar kinds.

  **हिन्दी:** पाठ की समानता Unicode क्रम से, बिना सामान्यीकरण है। `पाठ + पाठ` जोड़ता है; संख्या अपने आप पाठ नहीं बनती। अभिलेख/विकल्पसमूह संरचनात्मक और सामान्य वर्ग/संग्रह पहचान से मिलते हैं। Boxing में scalar का अपना प्रकार बचता है।
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
multiple values on one line, malformed UTF-8, EOF and out-of-range values raise
`अपवाद`. An uncaught input failure is reported on stderr with exit status 1. Multiple calls
consume successive lines. This is integer input only, not arbitrary text input.

**हिन्दी:** बिना तर्क का आरक्षित `पढ़ो()` एक UTF-8 पंक्ति से `पूर्णांक` पढ़ता है। किनारे की खाली जगह हटती है। वैकल्पिक ASCII `+`/`-` और एक लिपि के अंक दें। खाली पंक्ति, अनेक मान, गलत UTF-8, EOF और सीमा बाहर पर त्रुटि आती है। अगली कॉल अगली पंक्ति लेती है। पाठ के लिए `पाठपढ़ो()` है।

`पाठपढ़ो()` reads the next UTF-8 line as text, excluding its line terminator;
EOF and invalid UTF-8 are errors. Browser input comes from the prepared input
field instead of an interactive terminal.

**हिन्दी:** `पाठपढ़ो()` अगली UTF-8 पंक्ति का पाठ लौटाता है; पंक्ति-अंत हटता है।
EOF और गलत UTF-8 त्रुटि हैं। Browser में इनपुट पहले दिए गए क्षेत्र से मिलता है।

`लिखो` accepts zero or more printable scalar arguments, prints them separated by a
single space, then appends a newline and flushes stdout. `लिखो();` prints a blank
line. Output is UTF-8. Integers use Devanagari digits; booleans use `सत्य` /
`असत्य`. Reals use Java 17 `Double.toString` formatting with digits replaced by
Devanagari digits; punctuation, exponent markers, `NaN` and `Infinity` are retained.
Text is printed unchanged. CLR uses .NET invariant round-trip formatting with the
same Devanagari digit, decimal-point and exponent presentation. Some binary64
values have different shortest decimal spellings under .NET and Java 17 (for
example values near 10^23); exact last-digit cross-target real display parity is
not yet guaranteed. Integer, boolean and text display is portable across targets. Prompts should be printed before calling `पढ़ो()`.

**हिन्दी:** `लिखो` छापे जा सकने वाले scalar मानों को एक खाली जगह से अलग कर UTF-8 में छापता है और नई पंक्ति व flush देता है। खाली कॉल खाली पंक्ति देती है। संख्याओं में देवनागरी अंक और तार्किक में `सत्य`/`असत्य` हैं। पाठ जैसा है वैसा छपता है। JVM और CLR round-trip binary64 प्रदर्शन करते हैं। कुछ दशमलव के अंतिम अंक अलग हो सकते हैं। इनपुट से पहले संकेत लिखें।

The factorial sample validates 0–20 before computing with signed 64-bit integers.
Other integer arithmetic retains wrapping behavior; there is no arbitrary-precision
integer type yet.

**हिन्दी:** गुणनफल उदाहरण signed 64-bit गणना से पहले ०–२० सीमा जाँचता है। सामान्य गणना wrap होती है। मनमानी सटीकता वाला पूर्णांक अभी नहीं है।

General-purpose formatting uses `मानक.पाठ्य.स्वरूप` with indexed placeholders,
alignment and numeric precision; this is a library function, not interpolation
syntax. See the [Hindi formatting guide](../tutorial/standard-library/08-formatting.md).

**हिन्दी:** `मानक.पाठ्य.स्वरूप` में क्रमांक वाले placeholders, alignment और
संख्यात्मक precision हैं। यह पुस्तकालय फलन है, भाषा में interpolation syntax नहीं।

Strings are immutable sequences of Unicode scalars; their physical representation
may differ by backend. Text has no indexing or character type yet. Manual memory-management syntax is not implemented. Managed targets use host GC; native
uses precise, stop-the-world, nonmoving mark-and-sweep with traced descriptors
and compiler-maintained roots. Deterministic resource cleanup is separate from
GC; use explicit close/resource scopes for external resources.

**हिन्दी:** पाठ Unicode scalar का अचल क्रम है। अलग character प्रकार या सीधा पाठ index नहीं है। Managed लक्ष्य host GC और native सटीक nonmoving mark-and-sweep उपयोग करता है। बाहरी संसाधन की निश्चित सफ़ाई GC से अलग है; close या संसाधन scope उपयोग करें।

## 7. Object model

Classes, fields, constructors, instance methods, single inheritance, explicit virtual
method overrides, public/private/protected access and nullable class references are
implemented on JVM, CLR/browser and native. Class types can appear in locals, fields,
parameters and return positions; derived-to-base assignment is implicit. Text is
still a primitive value, while class equality uses reference identity.

**हिन्दी:** वर्गों में क्षेत्र, निर्माता, instance विधि, एकल विरासत, स्पष्ट अधिलेखन, पहुँच और nullable संदर्भ हैं। वर्ग प्रकार बंधन, क्षेत्र, मापदंड और परिणाम में हैं। व्युत्पन्न से मूल assignment अपने आप है। पाठ आदिम है, वर्ग समानता पहचान से है।

New reserved keywords: `वर्ग`, `विस्तार`, `निर्माता`, `नया`, `यह`, `आधार`,
`सार्वजनिक`, `निजी`, `संरक्षित`, `अधिलेखन` and `रिक्त`. Existing `फलन`, `मान`
and `स्थिर` are reused for members. `स्थिर` denotes immutability, not static storage.
All detailed syntax, initialization order, constructor-safety constraints and access
rules are specified in [Objects and inheritance](objects.md).

**हिन्दी:** सदस्यों में `फलन`, `मान` और `स्थिर` फिर उपयोग होते हैं। `स्थिर` अचलता और `साझा` वर्ग भंडार बताता है। घोषणा, आरंभ और पहुँच के लिए [वस्तु नियम](objects.md) देखें।

Strict null safety changes reference/default-initialization rules; do not apply
legacy nullable-field assumptions to strict code. Abstract classes, user-defined
final/sealed modifiers, properties/indexers and user-defined operators are absent.

**हिन्दी:** strict रिक्त-सुरक्षा में संदर्भ और अनिवार्य क्षेत्र-आरंभ के नियम बदलते
हैं। पुराने उदाहरणों के null defaults वहाँ न मानें। Abstract वर्ग, user-defined
final/sealed चिह्न, properties/indexers और operator overload अभी नहीं हैं।

## 8. Interfaces, generics, overloads and static members

`अनुबंध` declares an interface; classes use `पालन` to implement one or more contracts.
`साझा` marks static fields/methods. Classes, interfaces, global functions and
member methods support type parameters and arguments (`पात्र<पूर्णांक>`, `पहचान<पाठ>("नमस्ते")`). Functions,
methods and constructors can overload by their parameter type lists. All targets use the
same overload resolution, specialization and interface-validation rules.
See [contracts/static-member rules](contracts-generics.md) for the original model
and [current generic-method/inference rules](../tutorial/safer-everyday/README.md) for additions.

**हिन्दी:** `अनुबंध` से interface और `पालन` से कार्यान्वयन है। `साझा` static सदस्य है। वर्ग, अनुबंध और फलन में स्पष्ट प्रकार मापदंड और तर्क हैं। फलन, विधि और निर्माता मापदंड प्रकार से ओवरलोड हो सकते हैं। [नियम](contracts-generics.md) देखें।

Generic function/method calls infer type arguments from actual arguments,
including nested generic/array structure, not the expected return type.
Ambiguous/conflicting arguments require explicit types. Class type arguments and
lambda signatures remain explicit. Generic parameters are invariant; variance,
wildcards and default arguments are absent. `<त: नामित & मानक.बंदनीय>` requires
nominal conformance to both bounds. Closed types/calls check constraints; bodies
are checked for used concrete specializations, not fully at an unused template's
definition site. Class and member type-parameter names must not collide.

**हिन्दी:** Generic calls के प्रकार actual arguments और उनकी संरचना से निकलते
हैं, अपेक्षित परिणाम से नहीं। अस्पष्टता पर स्पष्ट प्रकार दें। वर्ग के type
arguments और lambda हस्ताक्षर स्पष्ट रहते हैं। Generics invariant हैं; variance,
wildcards और default arguments नहीं हैं। `&` से जोड़ी सभी nominal सीमाएँ पूरी
हों। उपयोग किए concrete विशेषीकरण का शरीर जाँचा जाता है, unused template का
पूर्ण definition-site परीक्षण नहीं। वर्ग/विधि के type-parameter नाम अलग हों।

```bhash
अनुबंध नामित { फलन नाम() -> पाठ; }
वर्ग छात्र पालन नामित {
    फलन नाम() -> पाठ { लौटाओ "आशा"; }
    फलन पहचान<त>(मूल्य: त) -> त { लौटाओ मूल्य; }
}
फलन नामलो<त: नामित>(वस्तु: त) -> पाठ { लौटाओ वस्तु.नाम(); }
फलन मुख्य() -> शून्य {
    स्थिर विद्यार्थी = नया छात्र();
    लिखो(नामलो(विद्यार्थी), विद्यार्थी.पहचान(७));
}
```

Static members belong to the declaring class; explicit access uses a type receiver.
Static methods do not dispatch virtually and cannot be overridden. Static field
initializers remain constant-only; no user-defined static constructor exists.
Native static references are precise GC roots.

**हिन्दी:** साझा सदस्य अपने घोषक वर्ग के हैं; स्पष्ट access में प्रकार receiver
दें। साझा विधि virtual नहीं है और उसका अधिलेखन नहीं होता। साझा आरंभक स्थिर
व्यंजक हैं; user-defined साझा निर्माता नहीं है। Native साझा संदर्भ GC roots हैं।

## 9. Selection statements

`चयन (expression) { विकल्प literal: statements शेष: statements }` supports integer,
text, boolean and enum selectors. Typed enum labels belong to the selector family;
raw integral labels do not substitute for enum members. The selector evaluates once; sections have independent scopes
and do not fall through. Consecutive case labels may share a body. `विराम` exits the
nearest loop or selection; `जारी` continues the enclosing loop. See [selection rules](switch.md).

**हिन्दी:** `चयन` में पूर्णांक, पाठ, तार्किक या परिगणना selector एक बार चलता है। परिगणना के लेबल उसी प्रकार के सदस्य हों; सीधे अंक उनका विकल्प नहीं हैं। खंड के अपने दायरे हैं। अगला खंड अपने आप नहीं चलता। पास-पास लेबल शरीर साझा करते हैं। `विराम` निकटतम चक्र या चयन और `जारी` निकटतम चक्र पर लागू है।

## 10. Arrays, collections and iteration

`त[]` (also `सरणी<त>`) is an invariant fixed-length array. `सूची<त>` and
`मानचित्र<क,व>` are predeclared invariant reference types. Collections support
indexing, checked writes and typed initializers; lists grow and maps preserve
insertion order. `प्रत्येक (source में element: type)` iterates elements or map
keys, and a second typed binding iterates map values alongside their keys.
See [collection rules](collections.md) for APIs, equality, mutation checks,
array dimensions, evaluation order and multi-file examples.

**हिन्दी:** `त[]`/`सरणी<त>` तय आकार की invariant सरणी है। सूची बढ़ती है और मानचित्र जोड़ने का क्रम रखता है। index और writes जाँचे जाते हैं। `प्रत्येक` में एक बंधन तत्व या कुंजी तथा दूसरा साथ का मान लेता है। [संग्रह नियम](collections.md) देखें।

`मानक.समुच्चय<त>` is an invariant, insertion-ordered Set from the library,
not a reserved keyword. `जोड़ो`, `हटाओ` and `है` return booleans; `संघ`,
`प्रतिच्छेद` and `अंतर` produce new sets. `प्रत्येक` accepts one typed element
binding. Equality/hashing follow the scalar, structural record/union and ordinary
reference-identity rules. See the [Hindi Set guide](../tutorial/standard-library/09-sets.md)
and [complete API](../tutorial/standard-library/reference/types/समुच्चय.md).

**हिन्दी:** `मानक.समुच्चय<त>` पुस्तकालय का insertion-order वाला invariant Set है।
जोड़ना, हटाना और सदस्यता जाँचना तार्किक परिणाम देते हैं। संघ, प्रतिच्छेद और अंतर
नए समुच्चय देते हैं। `प्रत्येक` में एक प्रकारयुक्त तत्व लें। समानता/hash में
scalar, अभिलेख/विकल्पसमूह और reference identity के सामान्य नियम लागू हैं।

```bhash
आयात मानक.समुच्चय;
फलन मुख्य() -> शून्य {
    स्थिर नाम = नया समुच्चय<पाठ>();
    नाम.जोड़ो("आशा");
    नाम.जोड़ो("आशा");
    प्रत्येक (नाम में सदस्य: पाठ) { लिखो(सदस्य); }
}
```

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

Not implemented: exact decimal/arbitrary-precision numeric types, abstract
classes and user-defined final/sealed modifiers, generic variance/wildcards,
full definition-site generic checking, user-defined operators/properties,
user-defined iteration protocols, general shared-memory threads, and a public
package registry. Native support does not imply arbitrary host-library access,
WASI or identical OS-specific library availability. Record bodies and richer
list/property/range/OR patterns remain future work. Tail-call optimization is not
guaranteed; deep recursion can exhaust the host stack.

**हिन्दी:** सटीक decimal/मनमानी precision, abstract/final/sealed घोषणाएँ,
variance/wildcards, पूर्ण generic definition-site जाँच, user-defined operators,
properties, iterator protocol, सामान्य साझा-memory threads और public package
registry अभी नहीं हैं। Native से मनमाना host API या WASI नहीं मिलता। अभिलेख के
शरीर और list/property/range/OR प्रतिरूप आगे की सुविधाएँ हैं। Tail-call optimization
की गारंटी नहीं है; गहरी recursion से stack समाप्त हो सकता है।

Generic member methods, record/case patterns, async I/O and typed C bindings are
implemented, even where an older linked milestone document uses future tense.

**हिन्दी:** सामान्य member methods, अभिलेख/case प्रतिरूप, async I/O और प्रकारयुक्त
C bindings लागू हैं; पुराने डिज़ाइन का भविष्य काल उनकी वर्तमान अनुपलब्धता नहीं है।

## Function values, lambdas and closures

Function types use `फलन(T1, T2) -> R`; lambdas use
`फलन(नाम: प्रकार) -> परिणाम { ... }`. Signatures are structural and invariant;
parameters and returns remain explicit. Functions can be passed, returned, stored
and invoked. Unambiguous named functions and closed generic specializations can
be values. Choose overloads/method callbacks with an explicit typed lambda.
No implicit single-method-interface conversion is provided.

**हिन्दी:** फलन प्रकार और lambda में मापदंड तथा परिणाम स्पष्ट लिखें। हस्ताक्षर
structural और invariant हैं। फलन पास, वापस, संग्रहित और बुलाया जा सकता है।
अस्पष्ट overload या method callback के लिए typed lambda लिखें। single-method
interface में अपने आप रूपांतरण नहीं होता।

Immutable bindings are captured by value (object references retain identity);
mutable locals share a heap cell. Each declaration execution and foreach iteration
has its own binding. Escaping closures retain captures; capturing a resource does
not postpone its close. Captured `यह` retains its object; direct `आधार` in a
lambda is rejected. See [function-value rules](function-values.md) and the
[Hindi tutorial](../tutorial/functions/README.md).

**हिन्दी:** अचल बंधन मान से capture होते हैं; वस्तु की पहचान बनी रहती है। Mutable
local के लिए साझा heap cell होता है। घोषणा की हर execution और foreach iteration
का अपना बंधन है। बाहर जाने वाला closure captures जीवित रखता है, पर संसाधन की
सफ़ाई नहीं रोकता। `यह` capture हो सकता है; lambda में सीधा `आधार` अमान्य है।

```bhash
फलन मुख्य() -> शून्य {
    मान गिनती = ०;
    स्थिर अगला: फलन() -> पूर्णांक = फलन() -> पूर्णांक {
        गिनती = गिनती + १;
        लौटाओ गिनती;
    };
    लिखो(अगला(), अगला());
}
```

## रिक्त-सुरक्षा (Null safety)

Legacy behavior remains the default. Enable strict checking through
`nullSafety=strict` in `bhash.project`, `--null-safety strict`, or an initial
`// bhash:null-safety=strict` source directive. A strict source directive is not
disabled by a legacy CLI flag. Source dependencies must use the same profile.
In Jupyter select the directive before creating bindings; restart the kernel
to change an established profile.

**हिन्दी:** डिफ़ॉल्ट legacy है। परियोजना में `nullSafety=strict`, CLI में
`--null-safety strict` या फ़ाइल की शुरुआत में `// bhash:null-safety=strict` दें।
फ़ाइल का strict निर्देश legacy CLI flag से बंद नहीं होता। Source dependencies
का profile समान रखें। Jupyter में bindings से पहले mode चुनें; बदलने पर restart करें।

In strict mode `T` is non-null and `T?` permits absence, including nullable scalars.
`शून्य?` is invalid. `T?[]` and `T[]?` distinguish nullable elements from a nullable
array. `(फलन()->पूर्णांक)?` is a nullable callback; `फलन()->पूर्णांक?` is a non-null
callback returning a nullable integer. Collections preserve invariant nullability.
An initializer containing only `रिक्त` cannot infer a type.

**हिन्दी:** strict mode में `T` मौजूद और `T?` वैकल्पिक है, scalar भी। `शून्य?`
अमान्य है। `T?[]` में तत्व वैकल्पिक और `T[]?` में पूरी सरणी वैकल्पिक है। फलन के
चारों ओर कोष्ठक लगाने से वैकल्पिक callback और वैकल्पिक परिणाम में अंतर बताएँ।
केवल `रिक्त` से प्रकार नहीं निकलता।

`?.` evaluates its receiver once and skips member/method-argument evaluation when
absent; its result is nullable. `??` evaluates fallback only if needed. Null tests
narrow eligible locals on each flow path. Writes, mutable captures, loops,
exceptions and suspension invalidate unsafe proofs; snapshot mutable fields into
immutable locals before narrowing. Non-null references require successful
constructor initialization; non-null static references need initializers.
Numeric/boolean/empty-text defaults remain. Non-null reference arrays need full
initializers or nullable element types. Overloads cannot differ only by nullability.

**हिन्दी:** `?.` receiver एक बार पढ़ता है; रिक्त होने पर arguments भी नहीं चलते।
`??` fallback केवल आवश्यकता पर चलता है। Null जाँच से सुरक्षित locals संकुचित
होते हैं। Writes, captures, loops, exceptions और suspension पर असुरक्षित अनुमान
हटते हैं। Mutable field का अचल snapshot लें। Non-null संदर्भ constructor में
भरें और साझा संदर्भ को initializer दें। Numeric/boolean/empty-text defaults रहते
हैं। Reference array में सभी non-null तत्व दें या तत्व nullable बनाएँ। केवल
nullability से overload अलग नहीं होता।

Strict safe casts such as `सुरक्षितरूप<पूर्णांक>(value)` return `पूर्णांक?`;
failed checked casts to non-null types raise `अपवाद`. There is no postfix
null-forgiving `!`; prefix `!` is boolean negation. Missing lookup/JSON/database
values have explicit library contracts, not an automatic non-null conversion.
See the [Hindi guide](../tutorial/null-safety/README.md) and
[design decisions](null-safety-design.md).

**हिन्दी:** strict safe cast का परिणाम वैकल्पिक है। Non-null checked cast विफल
होने पर अपवाद देता है। Postfix null-forgiving `!` नहीं है। Prefix `!` तार्किक
निषेध है। Lookup, JSON और database में अनुपस्थिति का अर्थ उनके स्पष्ट API से लें।

```bhash
// bhash:null-safety=strict
अभिलेख छात्र(नाम: पाठ);
फलन मुख्य() -> शून्य {
    स्थिर विद्यार्थी: छात्र? = रिक्त;
    लिखो(विद्यार्थी?.नाम ?? "अज्ञात");
}
```

## Async functions and tasks

The task-core contract is specified in [task-core-implementation.md](task-core-implementation.md).
An `असमकालिक फलन` returns `कार्यफल<T>` to callers while its body returns T.
`प्रतीक्षा` suspends only inside async bodies. An async `मुख्य` drives
structured task completion. `असमकालिक संसाधन` awaits `बंदकरो()->कार्यफल<शून्य>`;
both sync and async resources drain children before disposal. See the
[Hindi tutorial](../tutorial/async/README.md) and [approved design](async-await-design.md).

**हिन्दी:** `असमकालिक फलन` के शरीर से T और कॉल से `कार्यफल<T>` मिलता है। `प्रतीक्षा` async शरीर रोकती और बाद में आगे बढ़ाती है। async `मुख्य` कार्यों का समापन चलाता है। `असमकालिक संसाधन` `बंदकरो()->कार्यफल<शून्य>` की प्रतीक्षा करता है। दोनों तरह के संसाधन सफ़ाई से पहले अपने बाल कार्य पूरे करते हैं। [हिन्दी अभ्यास](../tutorial/async/README.md) देखें।


## Tagged unions, exhaustive matching and typed results

`विकल्पसमूह` and `मिलान` are contextual identifiers. A union has one or more
named cases, each with parenthesized immutable payload fields and a semicolon.
The family is closed and nominal. Construct `Family<types>.Case(values)`, including
`Case()` without payload. Cases are not public source types; the family cannot
be directly instantiated or extended.

**हिन्दी:** विकल्पसमूह (Tagged union) की निश्चित अवस्थाएँ और अचल payload हैं।
हर case में कोष्ठक और अर्धविराम दें। परिवार बन्द और nominal है। `परिवार.case(...)`
से मान बनाएँ; खाली case में भी `()` दें। परिवार को सीधे `नया` या विस्तार नहीं कर सकते।

`मिलान(selector)` evaluates once and tries arms in order. Expression arms use
`विकल्प pattern यदि(guard) => expression` separated by commas; statement arms
use `विकल्प pattern यदि(guard): { statements }`. Guards are optional. `_` is a
wildcard; `स्थिर name` binds an immutable arm-local value; `स्थिर name: NonNullType`
performs a non-null type test. Null, boolean, integral, text and typed enum
constants, nested record patterns and nested case patterns are supported.
Decimal literal, OR/range/list/property patterns are absent. Generic case patterns
can omit owner arguments only when the selector supplies a closed family;
universal selectors require explicit closed arguments. Record type arguments
remain explicit.

**हिन्दी:** मिलान (Pattern matching) selector एक बार पढ़ता और पहली स्वीकार शाखा
चुनता है। `=>` व्यंजक का मान देता है और `: { ... }` कथन चलाता है। Guard वैकल्पिक
है। `_` मान छोड़ता है और `स्थिर नाम` शाखा का अचल बंधन बनाता है। प्रकार वाला
बंधन non-null type test है। रिक्त, तार्किक, पूर्णांक, पाठ, परिगणना, nested अभिलेख
और case प्रतिरूप मान्य हैं। दशमलव literal और OR/range/list/property प्रतिरूप नहीं
हैं। Selector से परिवार न निकले तो generic case arguments स्पष्ट दें।

Both forms require exhaustive coverage. Guards do not establish coverage.
An unguarded final `शेष` is a catch-all; fully covered later arms are errors.
Nullable/legacy reference domains include null. Analysis is limited to 10,000
coverage states and nesting depth 64; excessive complexity is rejected. Branch
results need a common assignable type or explicit expected type. Return/throw/
break/continue follow the enclosing function/control-flow rules. Await in guards
or results requires async context and preserves cancellation/resource cleanup.

**हिन्दी:** दोनों रूप हर सम्भावना सँभालें। Guard सम्पूर्णता सिद्ध नहीं करता। अंतिम
`शेष` बची सम्भावनाएँ लेता है। पहले से पूरी ढकी अगली शाखा त्रुटि है। Nullable और
legacy संदर्भ में रिक्त भी लें। १०,००० coverage states और ६४ nesting स्तर की सीमा
है। शाखाओं के परिणाम का संगत प्रकार चाहिए। Return/throw/break/continue के सामान्य
नियम और async में await/cancellation/सफ़ाई के नियम बने रहते हैं।

Equality/hashing require the same closed family and case; payloads use record
rules: exact scalar kinds, ordinal text, structural nested record/union values
and identity for ordinary mutable references. Traversal is iterative and memoizes
shared values. Numeric enum semantics and non-exhaustive `चयन` remain unchanged.

**हिन्दी:** समानता/hash में वही closed परिवार और case चाहिए। Payload में scalar
का अपना प्रकार, पाठ का ordinal मान, अभिलेख/विकल्पसमूह की संरचना और सामान्य
mutable वस्तु की पहचान मिलती है। Shared values की पुनरावृत्ति सँभाली जाती है।
परिगणना और बिना अनिवार्य सम्पूर्णता का `चयन` नहीं बदलता।

`मानक.परिणाम<त, द>` has `सफल(मूल्य:त)` and `असफल(दोष:द)`.
`मानक.इकाई` is an empty immutable record for success without useful payload;
union arguments cannot be `शून्य`. Result does not implicitly unwrap, propagate
errors or convert exceptions, task faults, cancellation, SQL NULL or not-found
values. Direct C/JNI union/Result exports are rejected; supported wrappers can
use them internally. See the [Hindi tutorial](../tutorial/tagged-unions/README.md)
and [Hindi](tagged-unions-pattern-matching-design.md)/[English design](tagged-unions-pattern-matching-design.en.md).

**हिन्दी:** प्रकारयुक्त परिणाम (Typed Result) में सफल मान या असफल दोष है। उपयोगी
सफलता payload न हो तो इकाई (Unit) रखें; `शून्य` union argument नहीं है। Result
अपने आप unwrap, error propagate या exception/task fault/cancellation/SQL NULL
में नहीं बदलता। सीधे C/JNI exports नहीं हैं; समर्थित wrapper के भीतर उपयोग मान्य है।

```bhash
// bhash:null-safety=strict
फलन दुगुना(अंक: पूर्णांक) -> मानक.परिणाम<पूर्णांक, पाठ> {
    यदि (अंक < ०) { लौटाओ मानक.परिणाम<पूर्णांक, पाठ>.असफल("ऋणात्मक अंक"); }
    लौटाओ मानक.परिणाम<पूर्णांक, पाठ>.सफल(अंक * २);
}
फलन मुख्य() -> शून्य {
    स्थिर उत्तर: मानक.परिणाम<पूर्णांक, पाठ> = दुगुना(३);
    स्थिर विवरण: पाठ = मिलान (उत्तर) {
        विकल्प मानक.परिणाम.सफल(स्थिर मूल्य) => परिवर्तित<पाठ>(मूल्य),
        विकल्प मानक.परिणाम.असफल(स्थिर दोष) => दोष,
    };
    लिखो(विवरण);
}
```

## Task scheduling, cancellation and asynchronous I/O

Tasks use a cooperative dispatcher; async does not make CPU work parallel.
Await evaluates its operand once and yields through the scheduler, including
for a settled task. Invocations own child tasks; resource scopes drain children
before close. Faults request cancellation; cleanup is shielded from implicit
cancellation, while explicit tokens remain effective. No public synchronous
blocking wait or implicit task/Result conversion exists.

**हिन्दी:** कार्य cooperative dispatcher से चलते हैं; async CPU गणना को अपने आप
parallel नहीं करती। Await एक बार operand पढ़कर scheduler को अवसर देता है,
पहले से पूरे कार्य पर भी। संसाधन बंद होने से पहले बाल कार्य पूरे होते हैं।
विफलता cancellation माँगती है; सफ़ाई implicit cancellation से सुरक्षित है, पर
स्पष्ट token लागू है। Task और Result अपने आप एक-दूसरे में नहीं बदलते।

`मानक.कार्य.विलंब` and `छोड़ो` provide timers/yielding. Tokens/sources support
cooperative cancellation and deadlines. Cancelling a wait does not automatically
cancel its producer. Await-free loops and blocking synchronous calls cannot stop
cooperatively until yielding. Browser Stop allows a bounded cleanup grace period
before forced termination; forced termination cannot guarantee cleanup.

**हिन्दी:** विलंब/छोड़ो timer और अवसर देना प्रदान करते हैं। Token/source से
cancellation और deadline हैं। Wait रद्द करने से producer अपने आप रद्द नहीं होता।
बिना await के चक्र और blocking calls तुरंत नहीं रुकते। Browser Stop की grace
अवधि के बाद forced termination में सफ़ाई निश्चित नहीं है।

Asynchronous HTTP/database adapters are implemented; older task-core designs
are not current availability lists. Use explicit asynchronous operations to avoid
blocking the dispatcher. Browser networking obeys CORS/capability restrictions;
database drivers and server APIs remain target-dependent. See [async I/O](../tutorial/async-io/README.md),
[task semantics](task-core-implementation.md) and the [API reference](../tutorial/standard-library/reference/README.md).

**हिन्दी:** Async HTTP/database अनुकूलक लागू हैं। Async फलन के भीतर synchronous
call रखने से वह nonblocking नहीं होती। स्पष्ट async API चुनें। Browser में CORS
और capability सीमाएँ हैं; database/server API की लक्ष्यवार शर्तें देखें।

```bhash
असमकालिक फलन मुख्य() -> शून्य {
    प्रतीक्षा मानक.कार्य.विलंब(१);
    लिखो("कार्य पूरा हुआ");
}
```


## Typed C-library interoperability and runtime ownership

C bindings use a reviewed `bhash.ffi.json` manifest, generated typed declarations,
validated ABI contracts and checksummed per-target artifacts. Native uses the C
boundary, JVM uses JNI, and CLR uses generated interop. Supported wire types
include fixed-width scalars, UTF-8/byte buffers and opaque owned resources.
Call-scoped borrowed/copied buffers cannot be retained. Owned results use the
declared release function, including when conversion fails.

**हिन्दी:** प्रकारयुक्त C binding में manifest, generated घोषणाएँ, ABI और
लक्ष्यवार checksum-जाँचे artifacts हैं। Native में C, JVM में JNI और CLR में
interop wrapper है। Scalar, UTF-8/byte buffer और opaque owned resource समर्थित
हैं। Call के उधार buffers न रखें। Owned परिणाम घोषित release function से मुक्त
होता है, conversion विफल होने पर भी।

No Bhash heap pointer escapes to C. Managed arguments/results remain rooted
across conversion. Explicit close is idempotent and invalidates resource aliases.
Finalization queues reserved cleanup records; the owner thread runs vendor
destructors. Reentry, cross-runtime/thread use and busy resources are rejected.
No raw-pointer syntax, callbacks, varargs, C++ ABI, arbitrary struct exports or
general Java/.NET foreign-object model is provided. Direct union/Result exports
are excluded; use supported wrappers. Browser builds reject unsupported provider
capabilities. See [binding contracts and packaging](typed-c-library-bindings.md).

**हिन्दी:** Bhash heap pointer C में नहीं जाता। Arguments/results GC से सुरक्षित
रहते हैं। Explicit close दोहराना सुरक्षित है; उसी resource के aliases बंद माने
जाते हैं। Finalization रिकॉर्ड queue करती है और owner thread destructor चलाता है।
Reentry, दूसरे runtime/thread और busy resource का उपयोग अस्वीकार है। Raw pointers,
callbacks, varargs, C++ ABI, मनमाने struct exports और Java/.NET की हर वस्तु का
सीधा उपयोग नहीं है। Union/Result के सीधे exports नहीं हैं; wrapper उपयोग करें।
Browser असमर्थ provider capability अस्वीकार करता है।

`मानक.संपीड़न` provides bounded zlib/gzip with typed results through verified
bundled providers on supported desktop targets. Source packages use local or
pinned-Git dependencies, `bhash.project` declarations and `bhash.lock`. Restore
is explicit; compilation/editor lookup does not silently download binding
artifacts. See [compression](../tutorial/compression/README.md) and
[package management](source-packages.md).

**हिन्दी:** `मानक.संपीड़न` verified provider से सीमित zlib/gzip और typed Result
देता है। Source packages में local या pinned Git dependency, project घोषणा और
lockfile हैं। Restore स्पष्ट चरण है; compilation/editor lookup अपने आप artifact
download नहीं करता।

## Standard library and developer documentation

This specification defines language rules. The standard library also provides
text/formatting, math/random, JSON, dates/time, streams/files/paths,
configuration/logging/testing, HTTP/TLS/WebSockets, authentication, database
providers, ORM and migrations. Their APIs and installation/capability limits are
library contracts, not new language keywords. Begin with the
[complete Hindi API index](../tutorial/standard-library/reference/README.md),
[Hindi book](../book/README.md) and [tutorial index](../tutorial/README.md).

**हिन्दी:** यह पृष्ठ भाषा के नियम देता है। मानक पुस्तकालय में पाठ/स्वरूपण,
गणित/random, JSON, समय, streams/files/paths, configuration/logging/testing,
HTTP/TLS/WebSocket, authentication, database, ORM और migrations हैं। ये नए
कीवर्ड नहीं हैं; API की लक्ष्य और स्थापना शर्तें लागू हैं। पूरी हिन्दी सूची,
पुस्तक और ट्यूटोरियल के लिंक ऊपर हैं।
