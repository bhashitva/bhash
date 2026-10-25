# Bhash / भाष — v0.9 language definition

Status: v0.9 core implemented on JVM, .NET 10 CLR and browser WebAssembly, open to revision. `Bhash.g4` defines
syntax. `compile` and `run` enforce the semantic rules below; `check` remains a
syntax-only command. JVM output uses checked Java source lowering; CLR output
uses checked C# source lowering. The browser target reuses CLR lowering with the
.NET WebAssembly runtime. All targets share one semantic checker. Browser input
is supplied before execution; see [browser target](wasm-browser.md).

## 1. Source and lexical rules

- UTF-8 source files; `.bhash` extension. The CLI accepts one optional leading BOM.
- Keywords and identifiers are case-sensitive and use Devanagari. No English keyword aliases.
- Identifiers start with a Devanagari letter. Later characters may also be
  Devanagari marks (including vowel signs, nukta and virama), digits or `_`.
  ASCII letters, leading digits, isolated leading marks, danda punctuation,
  ZWJ/ZWNJ and bidi controls are not accepted in identifiers. This initial profile
  covers the explicit ranges in the grammar, not every Indic Unicode extension.
- Symbol tables must use NFC-normalized identifier keys via `Identifiers.key`.
  Canonically equivalent names refer to the same symbol and duplicate definitions
  are rejected. The parser preserves original token spelling;
  strings and comments are not normalized as a side effect of name handling.
- Numeric literals use ASCII digits (`123`) or Devanagari digits (`१२३`).
  A single literal cannot mix scripts. Different literals in one file may use either.
- Integers are decimal digits. Real literals require digits on both sides of `.`:
  `१२.५`, `12.5`. No exponent, radix prefix or digit separator in v0.3.
- String literals use double quotes and allow arbitrary Unicode text. Escapes:
  `\"`, `\\`, `\n`, `\r`, `\t`, and `\uXXXX` with four hexadecimal digits.
  Literal newlines in strings are forbidden. The semantic literal decoder
  rejects unpaired surrogate escapes; supplementary characters can be typed
  directly or represented with a valid escaped surrogate pair.
- Whitespace is insignificant. `//` begins a line comment; `/* ... */` is a
  non-nesting block comment. Unterminated comments and invalid tokens are errors.
- Braces delimit blocks; semicolons terminate simple statements. Danda `।`
  does not substitute for a semicolon.

## 2. Vocabulary

| Spelling | Role | Example |
| --- | --- | --- |
| `संकुल` | Package declaration | `संकुल संस्था.गणित;` |
| `आयात` / `उपनाम` | Import / import alias | `आयात संस्था.गणित.गणक उपनाम गणना;` |
| `प्रत्येक` / `में` | Collection iteration | `प्रत्येक (अ: पूर्णांक में अंक) { ... }` |
| `कार्य` | Function declaration | `कार्य जोड़(...) -> पूर्णांक { ... }` |
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
| `शून्य` | No return value (void) | `कार्य मुख्य() -> शून्य { ... }` |

`दशमलव` is the proposed spelling for binary floating point, not exact decimal
arithmetic; revisit the name if exact decimal becomes a language requirement.
`शून्य` is a return-type marker, not null and not a value or variable type.

## 3. Functions and program structure

```text
कार्य जोड़(पहला: पूर्णांक, दूसरा: पूर्णांक) -> पूर्णांक {
    लौटाओ पहला + दूसरा;
}

कार्य मुख्य() -> शून्य {
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
class declarations are supported. Executables must define exactly one
`मुख्य()` returning `शून्य` across the whole project. Empty source is syntactically valid but is not a
valid executable. Functions may overload by parameter types; return-type-only overloads are rejected. Classes have their own
member scopes, described in [Objects and inheritance](objects.md). Forward calls
and recursion are permitted; collect function signatures before resolving bodies.
All parameters and return types are explicit. Parameters are immutable bindings.
Arguments evaluate left to right and must match the signature in count and type.

## 4. Bindings, scope and control flow

Local bindings require a type and initializer. Class fields follow the object-model
initialization rules. `मान` allows later assignment;
`स्थिर` prevents rebinding but is not restricted to compile-time constants.
Blocks introduce lexical scopes. Duplicate names within one scope, including
parameters in a function's outermost body scope, are errors. A nested block may
shadow an outer binding. A variable is not in scope within its own initializer.

Conditions must be `तार्किक`; integers are not truthy. `विराम` exits the nearest
loop or selection. `जारी` is legal only within a loop and continues the innermost loop. A void function may
fall through or use `लौटाओ;`. A value-returning function must return a compatible
value on every reachable path. Analysis conservatively assumes a loop can execute
zero times, even for a literal true condition. Code after an unconditional return,
break or continue is rejected as unreachable. Only function calls may be standalone expression
statements, although the grammar intentionally accepts a wider expression here.
Assignments are statements, not expressions; targets may be mutable bindings, fields
or checked collection indices.

## 5. Operators and portable behavior

Precedence, highest to lowest; binary operators associate left to right:

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
- Integer range: −2^63 through 2^63−1. Out-of-range literals are compile-time
  errors. The constant checker handles unary minus before range validation
  so that the minimum signed integer can be expressed.
- Integer arithmetic wraps in two's complement. Integer division truncates
  toward zero; remainder has the dividend's sign. Division/remainder by zero
  terminates with a runtime error. MIN / −1 yields MIN; MIN % −1 yields zero.
- `&`, `|`, `^`, `~`, `<<`, `>>` and `>>>` accept integers only. Bitwise results
  preserve 64-bit two's-complement representation. Shift distance is `count & 63`,
  including negative and wide counts. `>>` sign-extends; `>>>` zero-fills, while
  retaining a signed result (a zero-distance shift preserves its input).
  Shift characters must be adjacent. Nested generic closers remain supported.
  See [bitwise rules and the bitboard example](bitwise.md).
- Real operations use binary64; division by zero and NaN follow IEEE 754.
  `%` is integer-only. Native backends must avoid unsafe fast-math assumptions.
- `&&` and `||` evaluate the right operand only when needed. Other operands
  evaluate left to right. `!` accepts booleans only; unary signs accept numbers.
- Ordering comparisons require numbers. Equality accepts matching numeric,
  boolean or text types. Text equality compares Unicode scalar sequences with
  no normalization; no implicit string concatenation or numeric coercion.
- Chained comparisons such as `१ < २ < ३` parse, but fail type checking because
  the first comparison produces a boolean. Use `१ < २ && २ < ३`.

These choices need explicit backend conformance tests; host-platform defaults
are not sufficient (notably native overflow and WASM signed division overflow).

## 6. Runtime surface

`पढ़ो()` is a predeclared, zero-argument function returning `पूर्णांक`. Its name
cannot be redeclared as a function. Each call reads one UTF-8 line from stdin,
strips leading/trailing whitespace using Java `String.strip`, and accepts an
optional ASCII `+` or `-` followed by one or more ASCII digits or one or more
Devanagari digits. Scripts cannot be mixed within an input integer. Empty lines,
multiple values on one line, malformed UTF-8, EOF and out-of-range values terminate
the program with a concise error on stderr and exit status 1. Multiple calls
consume successive lines. This is integer input only, not arbitrary text input.

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

The factorial sample validates 0–20 before computing with signed 64-bit integers.
Other integer arithmetic retains wrapping behavior; there is no arbitrary-precision
integer type yet.

Strings are immutable sequences of Unicode scalars; their physical representation
may differ by backend. Text has no indexing or character type yet. Manual memory-management syntax is not implemented. Class-based objects and host
garbage collection are described below.

## 7. Object model

Classes, fields, constructors, instance methods, single inheritance, explicit virtual
method overrides, public/private/protected access and nullable class references are
implemented on both managed targets. Class types can appear in locals, fields,
parameters and return positions; derived-to-base assignment is implicit. Text is
still a primitive value, while class equality uses reference identity.

New reserved keywords: `वर्ग`, `विस्तार`, `निर्माता`, `नया`, `यह`, `आधार`,
`सार्वजनिक`, `निजी`, `संरक्षित`, `अधिलेखन` and `रिक्त`. Existing `कार्य`, `मान`
and `स्थिर` are reused for members. `स्थिर` denotes immutability, not static storage.
All detailed syntax, initialization order, constructor-safety constraints and access
rules are specified in [Objects and inheritance](objects.md).

## 8. Interfaces, generics, overloads and static members

`अनुबंध` declares an interface; classes use `पालन` to implement one or more contracts.
`साझा` marks static fields/methods. Classes, interfaces and global functions support explicit
type parameters and arguments (`पात्र<पूर्णांक>`, `पहचान<पाठ>("नमस्ते")`). Functions,
methods and constructors can overload by their parameter type lists. All targets use the
same overload resolution, specialization and interface-validation rules.
See [the v0.3 specification](contracts-generics.md) for exact syntax and limits.

## 9. Selection statements

`चयन (expression) { विकल्प literal: statements शेष: statements }` supports integer,
text and boolean selectors. The selector evaluates once; sections have independent scopes
and do not fall through. Consecutive case labels may share a body. `विराम` exits the
nearest loop or selection; `जारी` continues the enclosing loop. See [selection rules](switch.md).

## 10. Arrays, collections and iteration

`त[]` (also `सरणी<त>`) is an invariant fixed-length array. `सूची<त>` and
`मानचित्र<क,व>` are predeclared invariant reference types. Collections support
indexing, checked writes and typed initializers; lists grow and maps preserve
insertion order. `प्रत्येक (element: type में source)` iterates elements or map
keys, and a second typed binding iterates map values alongside their keys.
See [v0.7 collection rules](collections.md) for APIs, equality, mutation checks,
array dimensions, evaluation order and multi-file examples.

## 11. Exception handling

`प्रयास block पकड़ो (name: type) block ... अंततः block` supports ordered typed
handlers and optional cleanup; try/finally without a catch is also valid.
`अपवाद` is the predeclared exception base class with a readonly `संदेश: पाठ`.
`फेंको expression;` throws an exception; `फेंको;` inside a catch rethrows its
current exception. Runtime failures are also catchable as `अपवाद`.
Exceptions are unchecked. Finally executes across normal and abrupt completion;
control cannot return or jump out of finally. See [exception semantics](exceptions.md)
for subtype matching, scopes, replacement exceptions and host limitations.

## 12. Types, conversions and binary data

`बाइट` is unsigned 8-bit and `लघुपूर्णांक` is signed 32-bit. Explicit numeric
conversions are checked; fractional-to-integer conversions truncate toward zero.
Small integral expression operands promote to `पूर्णांक`; assignments and
arguments still require explicit conversion. `सर्ववस्तु` stores boxed scalars
and references while preserving runtime type identity. `प्रकारहै<T>`,
`परिवर्तित<T>` and `सुरक्षितरूप<T>` implement tests, checked conversions/casts
and nullable reference casts, including invariant generic collections.

`परिगणना` introduces typed enum members/codes and named switch labels.
`अभिलेख` introduces sealed immutable records with generic parameters,
structural equality and hashing. `बाइटबफ़र` provides packed bytes, indexed
access/iteration, binary64/signed-64 binary reads/writes with explicit endianness,
copying and strict UTF-8 encoding/decoding. See [complete type rules](types-conversions.md).

## 13. Deferred design questions

Confirm vocabulary; choose exact real formatting; extend dependency package distribution;
extend records and generic member methods; extend exception metadata and resource management; settle WASI vs browser environments and native OS/ABI support; define
foreign-function interfaces separately for each platform. Do not grow the grammar
to cover these until their semantics and tests are agreed.
