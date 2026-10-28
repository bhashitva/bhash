# Text operations for the chess showcase

This additive extension to Bhash v0.4 implements the operations required by the
native chess port. There are no new keywords or grammar productions.

| Expression / function | Signature | Contract |
| --- | --- | --- |
| `पहला + दूसरा` | `पाठ + पाठ -> पाठ` | Immutable concatenation, left associative; mixed numeric/text operands are rejected |
| `पाठपढ़ो()` | `() -> पाठ` | Read one UTF-8 line, preserving spaces and excluding the line terminator; EOF/invalid UTF-8 fail |
| `लंबाई(पाठ्य)` | `(पाठ) -> पूर्णांक` | Number of Unicode scalar values, not UTF-16 code units or grapheme clusters |
| `अंश(पाठ्य, आरंभ, अंत)` | `(पाठ, पूर्णांक, पूर्णांक) -> पाठ` | Scalar slice `[start,end)`; require `0 <= start <= end <= length` |
| `अंकपाठ(संख्या)` | `(पूर्णांक) -> पाठ` | Signed ASCII decimal serialization, suitable for FEN/JSON; ordinary `लिखो` numeric display stays Devanagari |
| `पूर्णांकपाठ(पाठ्य)` | `(पाठ) -> पूर्णांक` | Optional ASCII sign followed by all ASCII digits or all Devanagari digits, within signed 64-bit range; no whitespace/mixed scripts/partial numbers |
| `बड़ाअक्षर(पाठ्य)` | `(पाठ) -> पाठ` | Map ASCII `a`–`z` to `A`–`Z`; preserve every other scalar unchanged |
| `त्रुटि(संदेश)` | `(पाठ) -> शून्य` | Terminate with a standard Bhash runtime diagnostic and status 1 |

ASCII-only case mapping is deliberate: it is deterministic across JVM, CLR and
browser CLR lowering, and sufficient for chess symbols and coordinates.
Unicode normalization is not applied to text. For example, `लंबाई("a😀क")` is 3,
and `अंश("a😀क", १, २)` is `"😀"`; combining marks count separately.

The functions are predeclared globals and cannot be redeclared/overloaded by
source programs. Ordinary methods/fields may still use the same spellings.
Arguments and results are checked by the existing shared semantic checker.
`त्रुटि` is a void function, not a new bottom type, so a value-returning function
still requires a syntactic return path after calling it.

Implemented in `ManagedEmitter.java` and both managed runtime templates.
The browser target inherits the CLR implementation; only JVM and CLR execution
have been validated for this extension. Arrays, Unicode case folding and host-language
interop remain outside this text-operations change. Multi-file projects and packages
are implemented separately in [v0.5](projects-packages.md).

Tests in `ManagedCompilerTest` exercise Unicode supplementary characters,
ASCII case behavior, input, script validation, overflow, bounds, EOF, explicit
failures, reserved names and rejected mixed-type concatenation on both backends.

## Native target

The Linux LLVM target supports concatenation and the globals above except `पाठपढ़ो`,
plus the scalar `मानक.पाठ्य` subset. Allocated results are automatically collected;
see [native text and rooting](native-text.md). Input and collection operations remain
outside the native profile.
