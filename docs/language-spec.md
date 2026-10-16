# Bhash / भाष — v0.1 language definition (draft)

Status: proposed design, open to revision. `Bhash.g4` implements the syntax below.
Semantic rules are an implementation contract for the next compiler phases;
they are not yet enforced by the syntax checker.

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
  must be rejected. The frontend currently preserves original token spelling;
  strings and comments must never be normalized as a side effect of name handling.
- Numeric literals use ASCII digits (`123`) or Devanagari digits (`१२३`).
  A single literal cannot mix scripts. Different literals in one file may use either.
- Integers are decimal digits. Real literals require digits on both sides of `.`:
  `१२.५`, `12.5`. No exponent, radix prefix or digit separator in v0.1.
- String literals use double quotes and allow arbitrary Unicode text. Escapes:
  `\"`, `\\`, `\n`, `\r`, `\t`, and `\uXXXX` with four hexadecimal digits.
  Literal newlines in strings are forbidden. A future semantic literal decoder
  must reject unpaired surrogate escapes; supplementary characters can be typed
  directly or represented with a valid escaped surrogate pair.
- Whitespace is insignificant. `//` begins a line comment; `/* ... */` is a
  non-nesting block comment. Unterminated comments and invalid tokens are errors.
- Braces delimit blocks; semicolons terminate simple statements. Danda `।`
  does not substitute for a semicolon.

## 2. Vocabulary

| Spelling | Role | Example |
| --- | --- | --- |
| `कार्य` | Function declaration | `कार्य जोड़(...) -> पूर्णांक { ... }` |
| `मान` | Mutable binding | `मान गिनती: पूर्णांक = ०;` |
| `स्थिर` | Immutable binding | `स्थिर सीमा: पूर्णांक = १०;` |
| `यदि` / `अन्यथा` | If / else | `यदि (सत्य) { ... } अन्यथा { ... }` |
| `जबतक` | While | `जबतक (गिनती < सीमा) { ... }` |
| `लौटाओ` | Return | `लौटाओ परिणाम;` |
| `विराम` / `जारी` | Break / continue | `विराम;` |
| `लिखो` | Console output statement | `लिखो("उत्तर", परिणाम);` |
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

A compilation unit contains top-level functions only. No top-level executable
statements, imports or globals in v0.1. Executables must define exactly one
`मुख्य()` returning `शून्य`. Empty source is syntactically valid but is not a
valid executable. Function names must be unique; no overloading. Forward calls
and recursion are permitted; collect function signatures before resolving bodies.
All parameters and return types are explicit. Parameters are immutable bindings.
Arguments evaluate left to right and must match the signature in count and type.

## 4. Bindings, scope and control flow

All bindings require a type and initializer. `मान` allows later assignment;
`स्थिर` prevents rebinding but is not restricted to compile-time constants.
Blocks introduce lexical scopes. Duplicate names within one scope, including
parameters in a function's outermost body scope, are errors. A nested block may
shadow an outer binding. A variable is not in scope within its own initializer.

Conditions must be `तार्किक`; integers are not truthy. `विराम` and `जारी`
are legal only within loops and affect the innermost loop. A void function may
fall through or use `लौटाओ;`. A value-returning function must return a compatible
value on every reachable path. Only function calls may be standalone expression
statements, although the grammar intentionally accepts a wider expression here.
Assignments are statements, not expressions; assignment targets are names only.

## 5. Operators and portable behavior

Precedence, highest to lowest; binary operators associate left to right:

| Level | Operators | Meaning |
| --- | --- | --- |
| 1 | `(...)`, named function calls | Grouping / invocation |
| 2 | `!`, unary `-`, unary `+` | Boolean negation / numeric sign |
| 3 | `*`, `/`, `%` | Multiply / divide / remainder |
| 4 | `+`, `-` | Add / subtract |
| 5 | `<`, `<=`, `>`, `>=` | Numeric comparison |
| 6 | `==`, `!=` | Value equality |
| 7 | `&&` | Short-circuit AND |
| 8 | `||` | Short-circuit OR |

- No implicit integer/real conversion in v0.1. Numeric binary operands must have
  the same type; conversion functions can be designed later.
- Integer range: −2^63 through 2^63−1. Out-of-range literals are compile-time
  errors. The constant checker must handle unary minus before range validation
  so that the minimum signed integer can be expressed.
- Integer arithmetic wraps in two's complement. Integer division truncates
  toward zero; remainder has the dividend's sign. Division/remainder by zero
  terminates with a runtime error. MIN / −1 yields MIN; MIN % −1 yields zero.
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

`लिखो` accepts zero or more primitive arguments, prints them separated by a
single space, then appends a newline. `लिखो();` prints a blank line. Output is
UTF-8. The proposed integer display uses Devanagari digits and boolean display
uses `सत्य` / `असत्य`. Exact real-number formatting must be specified before
runtime conformance; the formatter and all I/O are unimplemented today.

Strings are immutable sequences of Unicode scalars; their physical representation
may differ by backend. There is no indexing, null, character type, collection,
object model, user exception or memory-management syntax in this initial draft.

## 7. Deferred design questions

Confirm vocabulary; choose exact real formatting; define module/import syntax;
design arrays and records; choose error/exception behavior beyond arithmetic
traps; settle WASI vs browser environments and native OS/ABI support; define
foreign-function interfaces separately for each platform. Do not grow the grammar
to cover these until their semantics and tests are agreed.
