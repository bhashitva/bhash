# Tagged unions, pattern matching and typed Result: design proposal

Status: **Approved; implemented locally on `feature/tagged-unions`; remote CI verification remains pending.**
Baseline: the language contracts released in compiler/VS Code 0.26.0 and Jupyter 0.1.12.
[हिन्दी संस्करण (Hindi version)](tagged-unions-pattern-matching-design.md)

This is the English companion to the Hindi proposal, with the same 23 sections,
decisions, examples and review boundary. These are design examples; runnable programs
are available in the [Hindi tutorial](../tutorial/tagged-unions/README.md). Bhash keywords, identifiers,
strings and example comments remain in Hindi. English explanations do not introduce
English keyword aliases.

## 1. Goals

Null safety describes whether a value can be absent. Applications often need more
information: a student was found, no student was found, or the lookup failed. Each
state may carry different data. Under this proposal, a value contains exactly one
declared alternative at a time. The compiler checks how its data is extracted and
whether every possible alternative is handled. Programmers will not manage memory
allocation or deallocation.

The initial goal is identical public behavior on JVM, .NET CLR, browser WebAssembly
and native targets. Jupyter, VS Code and the Playground use the same language
contract. Existing HTTP, database, ORM, task and exception APIs retain their behavior.

## 2. Recommended decisions

| Topic | Recommendation | Consequence |
| --- | --- | --- |
| Declaration | Contextual keyword `विकल्पसमूह` | Numeric `परिगणना` remains separate and unchanged |
| Pattern selection | Contextual keyword `मिलान` | Existing `चयन` rules do not change |
| Case construction | `प्रकार.विकल्प(तर्क)` factory | Even a zero-argument case requires `()` |
| Structure | Closed nominal family with immutable fields in each case | External extension and inheritance are rejected |
| Exhaustiveness | Both match statements and expressions must handle every possible value | A missing case is a compilation error |
| Standard result | `मानक.परिणाम<त, द>` with `सफल` and `असफल` | Expected failures can be returned as data |
| Success without data | `परिणाम<मानक.इकाई, द>` | No general-purpose exception allowing `शून्य` generic arguments is needed |
| Equality | Structural equality of fields within the same closed family and case | The same rules apply to map/set keys and `सर्ववस्तु` |
| Native storage | Separate immutable heap object and fixed GC descriptor for each case | No overlapping C union or tag-dependent GC scan is needed |
| Compatibility | Additive syntax; existing APIs and numeric enum semantics remain intact | Existing source does not require migration |
| Initial boundary | No direct union/Result values in C/JNI exports | Android/JNI callers need explicit wrappers |

Vocabulary used in the examples:

| Bhash term | English meaning |
| --- | --- |
| `विकल्पसमूह` / `मिलान` | Tagged-union declaration / pattern matching |
| `विकल्प` / `शेष` | Case arm / default catch-all |
| `स्थिर` / `रिक्त` / `शून्य` | Immutable binding / null / void |
| `परिणाम` / `सफल` / `असफल` | Result / success case / failure case |
| `इकाई` / `कार्यफल` | Unit / task |
| `छात्र` / `खोजदोष` / `छात्रखोज` | Student / lookup error / student lookup outcome |
| `मिला` / `अनुपस्थित` | Found / not found |

## 3. Hindi declarations

```text
// प्रस्तावित वाक्यविन्यास; strict profile में उदाहरण
// bhash:null-safety=strict
अभिलेख छात्र(नाम: पाठ, क्रमांक: पूर्णांक);
अभिलेख खोजदोष(कोड: पाठ, संदेश: पाठ);

विकल्पसमूह छात्रखोज {
    मिला(विद्यार्थी: छात्र);
    अनुपस्थित();
    असफल(दोष: खोजदोष);
}
```

`छात्रखोज` is a nominal type. `मिला`, `अनुपस्थित` and `असफल` are its closed set of
alternatives. Payload fields have names and types; declaration order determines
both constructor argument order and positional pattern order. Different cases do
not need the same fields. A case with no fields is still a valid value.

Generic parameters and constraints use the existing generic declaration rules:

```text
सार्वजनिक विकल्पसमूह परिणाम<त, द> {
    सफल(मूल्य: त);
    असफल(दोष: द);
}
```

A family must have at least one case. Duplicate case names within a family,
duplicate field names within a case, invalid types and invalid generic arguments
are errors. Names follow NFC identifier equivalence and the current Unicode/joiner
profile.

The first version excludes custom constructors, methods, static fields, interface
implementation, inheritance, per-case visibility and partial/open unions. Every
case and payload type of a public family is part of its public contract; exposing
a private type through it is rejected. Family visibility follows existing package
export rules. Private generated case classes do not become public Bhash names.

## 4. Contextual keywords

Do not reserve `विकल्पसमूह` and `मिलान` globally in the lexer. Keep them as ordinary
identifier tokens; the parser recognizes their special meaning only in the new
forms: `विकल्पसमूह नाम ... { ... }` and `मिलान (व्यंजक) { ... }`. A declaration
may also begin with `सार्वजनिक विकल्पसमूह ...`.

Existing `फलन मिलान(...)`, `मिलान(तर्क);`, `वस्तु.मिलान(...)` and
`मान विकल्पसमूह = ...;` retain their meanings. The `{` following the match
selector distinguishes matching from an ordinary function call. Lookahead examines
parser shape and the canonical word; it must not infer intent through symbol
lookup or backtracking.

`विकल्प`, `यदि`, `शेष`, `स्थिर` and `रिक्त` already exist. The new `=>` separates a
pattern arm from its expression; function signatures retain `->`. English `union`
and `match` aliases are outside this proposal. The Hindi word choices themselves
are subject to review.

## 5. Construction and nominal identity

```text
स्थिर क: छात्रखोज = छात्रखोज.मिला(नया छात्र("सीमा", १));
स्थिर ख: छात्रखोज = छात्रखोज.अनुपस्थित();
स्थिर ग: मानक.परिणाम<छात्र, खोजदोष> =
    मानक.परिणाम<छात्र, खोजदोष>.असफल(नया खोजदोष("अनुपलब्ध", "फिर प्रयास करें"));
```

The family has no public `नया छात्रखोज(...)` constructor. Each factory's name,
arity and argument types come from its case declaration. Arguments are evaluated
once, from left to right. Initially, a generic factory owner requires explicit
closed type arguments. The compiler must not infer a second generic argument from
the payload alone. Existing generic function inference is unchanged.

A case name is not a separately addressable public subtype: `छात्रखोज.मिला` cannot
be used as a variable type, generic argument or type argument to `प्रकारहै<...>`.
Matching extracts its data. Ordinary access such as `क.विद्यार्थी` is rejected
because `क` could hold a different case.

`प्रकारहै<छात्रखोज>(मूल)` is true for every constructed case of that family.
Existing checked and safe casts recognize the family and its closed generic
arguments. Conversion to `सर्ववस्तु` preserves the reference; it does not allocate
a new scalar box.

`परिणाम<छात्र, खोजदोष>` and `परिणाम<छात्र?, खोजदोष>` are distinct invariant types.
This proposal adds no covariance, implicit conversion between them or anonymous
`A | B` types. Recursive families are allowed under existing specialization and
constraint limits.

One closed family counts as one user-visible type specialization. Its private
case layouts do not count as separate user generic instantiations. Their size
and pattern-analysis work remain subject to separate compiler budgets.

## 6. Match expression

```text
फलन विवरण(उत्तर: छात्रखोज) -> पाठ {
    लौटाओ मिलान (उत्तर) {
        विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी) => विद्यार्थी.नाम,
        विकल्प छात्रखोज.अनुपस्थित() => "छात्र नहीं मिला",
        विकल्प छात्रखोज.असफल(स्थिर दोष) => दोष.संदेश,
    };
}
```

The selector is evaluated once, and its value is a stable snapshot throughout the
match. Arms are tested in source order. Only the expression belonging to the first
successful pattern with a true guard is evaluated. Other arms' guards and bodies
do not run.

All reachable arm results must type-check as one result type. Use an expected type
from an assignment, return or parameter when available. Otherwise, the existing
expression/type-inference rules must yield an unambiguous type. Do not silently
box incompatible results into `सर्ववस्तु`. Nullable results follow the existing
null-safety rules. Require an explicit result type when inference is ambiguous.

Initially, each `=>` is followed by one expression. There is no separate
block-result or `yield` syntax. Commas separate arms, with a trailing comma allowed.
Matching is a primary expression, making precedence clear in expressions such as
`मिलान (...) { ... } ?? विकल्पमान`.

This expression form must produce a value. Use the statement form for calls
returning `शून्य`. Do not introduce a throw expression; use a statement arm or an
existing typed helper where necessary.

## 7. Match statement and control flow

```text
मिलान (उत्तर) {
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी): {
        लिखो("नाम:", विद्यार्थी.नाम);
        लिखो("क्रमांक:", विद्यार्थी.क्रमांक);
    }
    विकल्प छात्रखोज.अनुपस्थित(): {
        लिखो("छात्र नहीं मिला");
    }
    विकल्प छात्रखोज.असफल(स्थिर दोष): {
        लिखो("खोज असफल:", दोष.संदेश);
    }
}
```

Statement arms use `:` followed by a mandatory block. Do not mix these with `=>`
expression arms. Each block has its own scope. The first version excludes
fallthrough and grouped labels. Normal completion of the selected arm ends the
match.

`लौटाओ`, `फेंको` and `संसाधन` retain their normal behavior. `विराम` exits the
nearest enclosing loop or selection/match statement; `जारी` targets the nearest
loop. A match expression is not itself a break/continue target. A return inside a
function called from it returns from that function. Definite-return analysis
treats a match statement as returning only if every path returns.

## 8. Patterns and bindings

| Form | Meaning |
| --- | --- |
| `छात्रखोज.अनुपस्थित()` | The zero-payload case of that family |
| `छात्रखोज.मिला(स्थिर विद्यार्थी)` | Test the case and bind its field to an immutable local |
| `छात्रखोज.मिला(छात्र(स्थिर नाम, _))` | Deconstruct a nested record in field declaration order |
| `रिक्त` | Match only an absent/null value |
| `स्थिर क: छात्र` | Non-null runtime type test and typed binding |
| `स्थिर क` | Bind the entire selector/payload, potentially including null |
| `_` | Discard the value without introducing a binding |
| `१`, `"तैयार"`, `सत्य`, `स्थिति.तैयार` | Constant matching under existing type and equality rules |

Only union payloads and actual immutable `अभिलेख` fields can be deconstructed.
Ordinary class fields, computed properties, getter calls, arbitrary expressions,
range/list patterns, OR patterns and user-defined extractors are outside the
initial scope. Pattern testing therefore does not implicitly call user code.

`स्थिर` bindings are available to the guard and that arm only. Duplicate binding
names within a pattern are rejected. Shadowing an outer local follows existing
block-scope rules. Assignment cannot replace a binding, but this introduces no
deep-immutability guarantee for a referenced mutable object/list. Closure capture
uses the current rules and retains required fields/references.

A standalone `_` is a wildcard in a pattern; elsewhere it remains a valid existing
identifier. Reject `स्थिर _` rather than giving it an ambiguous discard/binding
meaning. A type pattern cannot specify `T?`; use a null arm or catch-all binding.
A successful type binding is non-null and preserves the exact primitive type
identity used by current casts.

A union pattern must name its owner. When a closed selector supplies the same
generic family, its arguments may be omitted in the pattern, as in
`मानक.परिणाम.सफल(स्थिर क)`. With a `सर्ववस्तु` selector, the full closed owner is
required. Generic arguments in record patterns are initially explicit. Field
counts and nested types must match; no reordering or hidden numeric conversions
are introduced.

Integral literals obey the range rules of the expected integral type. Without
such context on `सर्ववस्तु`, `१` has type `पूर्णांक` and does not match a boxed
`बाइट`. Defer decimal/NaN literal patterns; bind the decimal type and use a guard.
Text matching uses ordinal value equality. Identifier normalization is not applied
to string contents.

## 9. Guards, exhaustiveness and reachability

```text
मिलान (उत्तर) {
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी) यदि (विद्यार्थी.क्रमांक > १०): {
        लिखो("वरिष्ठ:", विद्यार्थी.नाम);
    }
    विकल्प छात्रखोज.मिला(स्थिर विद्यार्थी): { लिखो(विद्यार्थी.नाम); }
    विकल्प छात्रखोज.अनुपस्थित(): { लिखो("अनुपस्थित"); }
    विकल्प छात्रखोज.असफल(स्थिर दोष): { लिखो(दोष.कोड); }
}
```

A guard must have type `तार्किक`. It executes once after its pattern succeeds.
Ordinary calls, side effects and, in an appropriate async context, `प्रतीक्षा` are
allowed. A false guard proceeds to the next arm. A throwing guard propagates the
exception rather than trying the next arm. No guard runs before its pattern.

Both new match forms must be exhaustive. Use a shared coverage checker for closed
unions, booleans, nullable presence, typed enums and nested record patterns. Guards
do not contribute coverage, even if a guard is the literal `सत्य`. A guarded
`मिला` therefore needs an unguarded `मिला` or catch-all after it.

For infinite domains such as numbers/text and open class hierarchies, examples
or currently known subclasses do not constitute the complete domain. Require a
catch-all, or a type pattern covering the whole non-null domain, plus a null case
where needed. A record pattern covers only what its subpatterns cover. `_` and an
untyped binding cover the whole domain, including null.

`शेष` is an alternative readable spelling of an unguarded catch-all:
`शेष: { ... }` in a statement and `शेष => व्यंजक,` in an expression. It must be
last. A duplicate catch-all using `_` and `शेष` is an unreachable-arm error.
A completely covered later arm is a compilation error; partial overlap is valid
and follows source order.

Diagnostics should show at least one uncovered possibility, such as
`छात्रखोज.अनुपस्थित()` or `मिला(छात्र(_, रिक्त))` where the types allow it.
Use memoized usefulness/coverage analysis for recursive and product patterns.
Proposed internal limits are 10,000 analysis states and 64 pattern nesting levels.
On reaching a limit, issue a clear Hindi diagnostic suggesting a simpler pattern
or catch-all. Never silently accept exhaustiveness. Validate these provisional
limits against ordinary programs during implementation.

## 10. Null safety and defaults

In strict mode, `छात्रखोज` is non-null and `छात्रखोज?` is nullable. Cover a nullable
selector's null possibility with an explicit `रिक्त` arm or catch-all. If existing
flow analysis provides a valid non-null proof, check that effective domain. A
match does not permanently refine a mutable outer receiver; arm bindings come
from the selector snapshot.

In legacy mode, the possible `रिक्त` state of a reference selector must also be
covered by the new `मिलान`. This is a rule of the new syntax; existing `चयन`
no-match/default behavior is unchanged. Main tutorial examples begin with the
strict directive.

Do not make any case the implicit default. In strict mode, `नया छात्रखोज[१०]`
is rejected because its non-null entries are not initialized. Use a nullable
array, complete literal or existing permitted builder. Constructor and static
initialization rules still apply.

`सफल(रिक्त)` in `परिणाम<छात्र?, दोष>` is a fully constructed success value. It is
different from the entire value being `रिक्त` in `परिणाम<छात्र, दोष>?`.
Similarly, `असफल(रिक्त)` is valid only if the error payload type permits it.

## 11. Equality, hashing and display

Union values are equal only when their closed nominal family, case and every
payload field are equal. Matching declaration order or field shapes do not make
two different families equal. Generic arguments are part of the identity.

Reuse the existing record field-comparison contract: exact scalar type rules,
text value equality, structural equality for nested records/unions, and reference
identity for ordinary objects/collections. Decimal fields use collection equality
rules for NaN and signed zero. Preserve existing comparability checks on typed
operands; do not implicitly convert incompatible operands to universal values
merely to return `असत्य`.

These rules apply to direct equality, `सर्ववस्तु`, map keys and sets. Hashes include
nominal family, closed arguments, case and fields. Equal values have equal hashes
within a process; raw hashes and case ordinals are not a portable wire format.

Comparison/hashing of deep or shared immutable structures must be iterative and
memoized, avoiding unsafe C-stack recursion and exponential repeated traversal.
Do not traverse mutable references' contents. Immutability is shallow, as with
records.

The first version adds no implicit JSON, SQL, ORM discriminator, text formatting
or union-to-text conversion. Use explicit matches/mappers. Debugger/Jupyter
inspection should display a readable family, active case and fields without
calling application formatting methods or automatically logging sensitive payloads.

Native comparison/hash helpers do not allocate managed objects or trigger GC on
the normal path. If an error path allocates an exception, root its live operands
precisely first. Existing fatal/runtime allocation-failure policy remains in force.

## 12. Standard Result and Unit

`मानक.परिणाम<त, द>` is the ordinary two-case standard-library type declared above.
Neither parameter is implicitly constrained to `अपवाद` or any other class. An
error can be text, an enum, a record or another union. Qualified names and explicit
imports follow existing shadowing rules. `परिणाम` does not become a globally
reserved identifier.

`मानक.इकाई` is an immutable zero-field record. An operation without success data
uses `परिणाम<मानक.इकाई, दोष>` with success payload `नया मानक.इकाई()`.
`परिणाम<शून्य, दोष>` remains invalid. The existing `कार्यफल<शून्य>` exception
remains specific to the task API.

Keep the initial API to case factories and matching. Implicit unwrap, error
propagation `?`, implicit exception conversion, custom union methods and callback
helpers such as map/flatMap are separate later decisions. There is no need to
start with a throwing `मूल्य` getter that extracts the wrong case's payload.

`असफल(दोष)` is an ordinary value. The compiler does not assume it should be logged,
thrown, retried or returned from the caller automatically. The first milestone
does not add a Result-specific must-use warning; the existing task-valued
expression diagnostic remains. A must-use policy needs separate review.

## 13. Proposed application example

This small future program demonstrates all three states without a database or
network. Once the syntax is approved and implemented, it becomes a runnable fixture
and Hindi tutorial.

```text
// bhash:null-safety=strict
अभिलेख छात्र(नाम: पाठ, क्रमांक: पूर्णांक);
अभिलेख खोजदोष(कोड: पाठ, संदेश: पाठ);
विकल्पसमूह छात्रखोज {
    मिला(विद्यार्थी: छात्र);
    अनुपस्थित();
    असफल(दोष: खोजदोष);
}

फलन खोजो(क्रमांक: पूर्णांक) -> छात्रखोज {
    यदि (क्रमांक == १) {
        लौटाओ छात्रखोज.मिला(नया छात्र("सीमा", १));
    }
    यदि (क्रमांक == ०) { लौटाओ छात्रखोज.अनुपस्थित(); }
    लौटाओ छात्रखोज.असफल(नया खोजदोष("अमान्य", "क्रमांक जाँचें"));
}

फलन बताओ(उत्तर: छात्रखोज) -> पाठ {
    लौटाओ मिलान (उत्तर) {
        विकल्प छात्रखोज.मिला(छात्र(स्थिर नाम, _)) => "मिला: " + नाम,
        विकल्प छात्रखोज.अनुपस्थित() => "छात्र नहीं मिला",
        विकल्प छात्रखोज.असफल(स्थिर दोष) => दोष.संदेश,
    };
}

फलन मुख्य() -> शून्य {
    लिखो(बताओ(खोजो(१)));
    लिखो(बताओ(खोजो(०)));
    लिखो(बताओ(खोजो(-१)));
}
```

Expected output: `मिला: सीमा`, then `छात्र नहीं मिला`, then `क्रमांक जाँचें`:
“Found: Seema”, “Student not found”, and “Check the student number”.

A separate standard Result example:

```text
// इसी strict उदाहरण के छात्र और खोजदोष प्रकार
स्थिर उत्तर: मानक.परिणाम<छात्र, खोजदोष> =
    मानक.परिणाम<छात्र, खोजदोष>.सफल(नया छात्र("सीमा", १));
स्थिर विवरण: पाठ = मिलान (उत्तर) {
    विकल्प मानक.परिणाम.सफल(स्थिर विद्यार्थी) => विद्यार्थी.नाम,
    विकल्प मानक.परिणाम.असफल(स्थिर दोष) => दोष.संदेश,
};
लिखो(विवरण);
```

A later HTTP/database demo should connect this model to a real lookup. Convert
only explicit expected conditions to domain values. Do not blanket-catch unknown
exceptions into ordinary `असफल` values, or expose raw SQL/network details to clients.
Existing exceptions for transport failure, cancellation and unknown transaction
outcome retain their contracts. Database transaction resources close under the
existing cleanup rules for every case, return, exception and cancellation path.

## 14. Tasks, cancellation and cleanup

`कार्यफल<परिणाम<त, द>>` has two distinct levels. If a task completes with
`असफल(दोष)`, awaiting it returns that ordinary Result value. If the task faults or
is cancelled, `प्रतीक्षा` retains its current exception/cancellation behavior.
Do not automatically add a cancellation case to Result or convert a task fault
into a Result failure.

On suspension in an async guard or selected arm, spill the selector, required
extracted bindings and pending results into typed heap-frame fields. Payload
extracted before the guard remains part of the same snapshot; do not reevaluate
the selector on resume. After a false guard, release its bindings and proceed to
the next arm. Existing await yielding and cancellation checks apply only on the
actual execution path.

A match is not an implicit resource scope or transaction commit. `संसाधन` and
`असमकालिक संसाधन` work normally in the selected arm. Normal completion, `विराम`,
`जारी`, return, throw and cancellation perform cleanup in the existing order.
If a cleanup exception replaces a pending return, release temporary roots for
the abandoned returned union/payload too. No ownership or destructor contract is added.

## 15. Compiler architecture

The current pipeline assigns separate responsibilities to ProjectResolver,
GenericExpander, ManagedEmitter and native CoreProgram/LLVM lowering. Add this
feature without requiring a complete compiler rewrite, but do not implement
different pattern semantics independently in each backend.

The shared checked model should include `UnionFamily`, `UnionCase`, typed
`Pattern`, coverage results and a source-located `MatchPlan`. Resolve family
visibility and aliases first. After specialization, determine concrete payload
types, constraints and representation. Preserve the current checking policy for
unused templates.

MatchPlan explicitly represents the evaluate-once selector, pure case/type/field
tests, success-only bindings, guard edge, next-arm edge, selected body and
merge/result edge. Guards and bodies containing ordinary expressions or await
lower through the existing control-flow machinery. Sharing/reordering
optimizations must preserve side effects.

Return, definite-assignment and null-flow analysis use the same plan. Preserve
original source spans for patterns, payload fields, guards and arm bodies.
Private generated names must not replace public names in diagnostics, stack
traces or F2/Find References.

This is a scoped checked plan. A public IR, separately compiled union libraries
or a complete typed-AST migration are not prerequisites for this milestone.

## 16. Cross-target behavior

| Target/host | Proposed representation and validation |
| --- | --- |
| JVM | Internal family base and final generated case classes; Bhash checks exhaustiveness without depending on host Java pattern syntax |
| .NET CLR | Equivalent logical family and sealed case objects; primitive payloads avoid unnecessary universal boxing |
| Browser WebAssembly | CLR semantics in the existing isolated worker/prebuilt host; no new HTTP/file/database capabilities |
| Native Linux/Windows x64, macOS ARM64 | Concrete case heap layouts, precise roots and the shared checked plan |
| Jupyter JVM | Declarations/case metadata persist in the session; later cells use values, patterns and Result |
| Android native library | Internal union use is allowed; existing C/JNI export signatures do not pass unions directly |

The same source must select the same case and preserve side-effect order, null
checks, typed equality, visible output and diagnostic locations across targets.
Object bytes, allocation addresses, native case ordinals and raw hashes are not
portable across targets.

The existing kernel persists scalar bindings; object bindings retain their existing cell-local lifetime. Reuse union declarations and factory functions across cells, and construct/match an object within the same cell. Extending persistent object storage is a separate notebook concern.

Jupyter must not hot-reload a changed case set into existing live values. Apply
current declaration replacement rules and issue a clear restart-required
diagnostic when live nominal identity would need to change. Do not commit an
incomplete case set to the session symbol table before the whole declaration
has been parsed and resolved.

Playground scratch mode supports local bindings/matches; top-level family
declarations require Full Program mode. Package the compiler, kernel, extension
and browser host from the same grammar/runtime revision. Validate standalone
and prebuilt browser paths separately.

## 17. Precise GC representation

Keep the collector **precise, stop-the-world, nonmoving mark-and-sweep**. Each case
is a separate object with a nominal metadata pointer and its own field carriers.
Keep the class-info prefix compatible with current runtime helpers; the union
extension contains closed family identity, a private case discriminator and
field-kind metadata. The logical tag can live in immutable case metadata.
Do not initially use overlapping payload storage or a mutable active tag.

For example, the descriptor for `मिला(छात्र)` traces its student reference.
`अनुपस्थित()` has no payload reference slot. Integer bits in `गिनती(पूर्णांक)`
are not pointers. Different case sizes use the ordinary allocator. Do not
reinterpret existing record/class headers in a way that gives the collector
incorrect offsets.

Each closed specialization has an exact descriptor. Nullable scalar payloads
use the implemented boxed-value-or-null representation; do not invent a sentinel
or inline optional-scalar ABI. Code generation classifies references using types,
not source spelling alone.

Required construction order:

1. Evaluate arguments once, left to right. If a later argument allocates or
   suspends, earlier reference arguments remain in writable roots or an async frame.
2. After all arguments are ready, allocate a zero-initialized object with the
   exact case descriptor. Root its reference before filling fields.
3. Populate reference fields through the current `bh_reference_store` contract.
   Scalar/reference classification is already correct for the unfinished object;
   user code cannot access it.
4. Publish only the completed object. On failure/unwinding, release the temporary
   object and argument roots through existing protected-region cleanup.

During matching, read metadata and payload from a rooted selector. If a guard,
call, await or error helper can allocate, retain live extracted references in
separate precise slots/frame fields. Do not retain a field's interior address
across a safepoint. Release dead temporaries after failed guards and arm exit;
retain escaped captures and pending return values for their actual lifetimes.

Union objects behave as ordinary managed references in arrays, lists, maps, sets,
`सर्ववस्तु`, static roots, closure captures, exception objects and suspended tasks.
Initially, even a zero-payload case allocates an ordinary object on each factory
call. Do not add singleton caches or process-lifetime managed roots. Structural
equality allows a later, separately evaluated interning optimization.

Retain writable roots, owner-plus-offset reference stores and
reload-after-safepoint rules for future moving/generational collection. Immutable
fields must not bypass the initial store barrier. This milestone adds no moving
GC, conservative scanning, programmer-visible allocation/deallocation or new
ownership model.

Future relocation must also preserve the identity hashes of referenced mutable
objects so union/record map keys remain findable after movement. This is a future
whole-runtime collector contract. Writable roots alone do not mean moving GC is
supported today. Do not serialize address-derived hash numbers.

The cost is explicit: each factory call normally allocates a managed object;
a newly created payload record or `इकाई` is a separate allocation. Union arrays
hold references to case objects. Compared with inline/value-type unions, headers,
indirection and GC work can increase. In return, the current reference ABI,
collection storage and precise roots stay simple. This is not a zero-allocation
or Rust-style inline-layout guarantee. Consider interning, allocation elision or
inline representation separately after measuring representative workloads.

## 18. ABI and interoperability

The current source header declares native internal ABI **17**. Treat union
metadata, runtime value equality/hash and debugger schema support as requiring
a new ABI revision. Propose **18 if no intervening ABI change occurs**; select
the next available revision during implementation and record its provenance.

The compiler, bundled C runtime and generated program must agree on the link-time
ABI symbol and runtime/cache fingerprint. Reject incompatible old objects,
runtimes or caches clearly rather than guessing their layouts. Current source
packages are rebuilt; this does not establish a stable public binary union ABI.
Generated native applications remain independent of JVM/.NET. Do not assume a
metadata change alone requires a new LLVM SDK; test compatibility with the newly
compiled runtime.

The existing C/JNI export validator must explicitly reject union, Result and
unsupported export types containing them. Internal functions can use them.
Use explicit wrappers with already supported scalars/text/handles at the boundary.
Do not infer pointer ownership, presence flags, serialized tags or Kotlin sealed
hierarchies. Generated Kotlin/JNI union bindings require a separate versioned design.

## 19. Compatibility and evolution

- Preserve numeric `परिगणना` codes, `नाम`/`अंक`, casts, equality and `चयन` labels.
- Preserve non-exhaustive `चयन` statements, grouped labels and default behavior.
- Preserve record declarations/construction and existing class/interface subtyping.
- Preserve nullable returns, typed getters and exceptions in existing HTTP/database/ORM APIs.
- Preserve `कार्यफल` success, fault, cancellation and resource cleanup.
- Preserve the legacy null-safety default and compilation-wide dependency profile rules.
- Keep the source lockfile format; exact Git/local dependencies use normal restore/verify.

Adding a case to a closed family can make consumers' exhaustive matches fail on
recompilation. This is an intentional compatibility signal. Changing payload
order, type or name is also an API change. Library authors must document this in
version/release notes and pinned dependency updates. A `शेष` arm can automatically
catch future alternatives; choose that fallback deliberately rather than hiding
a new application state accidentally.

Version JSON/wire/database representations through explicit separate mappings.
Do not serialize numeric case ordinals. SQL NULL, not-found and domain failure
must not be implicitly converted into one another.

## 20. Editor and diagnostics

Completion shows case factories and payload parameters. A missing-cases Quick Fix
inserts placeholders with correct owner names, not a silent runtime fallback.
Hover explains family and pattern-binding types. F2/references understand scopes
for declarations, case construction, patterns and payload bindings; `_` is not a binding.

The proposed diagnostic categories follow. Before implementation, allocate final
BH codes from available catalog space. This proposal does not reuse an existing code.

| Category | Examples |
| --- | --- |
| Declaration/identity | Duplicate case, inaccessible payload type, variant outside its family |
| Construction | Wrong arity/type, unresolved generic arguments |
| Pattern | Wrong family, field count, duplicate binder, nullable type pattern |
| Coverage | Missing variant/null; bounded-analysis limit |
| Reachability | Fully unreachable arm, duplicate catch-all |
| Result typing | Incompatible arm results or a `शून्य` generic argument |
| Host/boundary | Unsupported C/JNI export, notebook identity change |

Messages include a Hindi explanation, original file/cell and pattern/arm span.
Test CLI and LSP UTF-16 positions after supplementary Unicode and NFC-equivalent
names. Debuggers display the active case and payload through read-only memory
inspection. New Watch grammar may follow later; existing Watch must explicitly
reject unsupported layouts rather than reading them incorrectly.

## 21. Validation and release gates

| Area | Required evidence |
| --- | --- |
| Frontend | Contextual-name compatibility, aliases, NFC/joiners, parser recovery, source spans |
| Types | Payload arity, visibility, generics/constraints/invariance, recursive types, casts |
| Coverage | Missing/guarded/unreachable arms, null, bool, enums, nested products, complexity limits |
| Evaluation | Selector/arguments/guards once; source order; no calls/awaits in skipped arms |
| Values | Exact-type universal equality, nested unions/records, NaN fields, deep shared graphs, map/set keys |
| Async/cleanup | Awaited guard/arm, captures, cancellation, throwing cleanup, return replacement |
| Native GC | Collection at every allocation, partial construction, failed guards, static/collection/task roots |
| Packaging | Consistent support in the CLI JAR, VSIX, kernel and regenerated browser host |

A shared golden corpus compares JVM, CLR and native output, exit codes and
diagnostic codes/spans. Compile-fail tests must not produce executables. Both
browser publishing paths run actual execution, isolation, Stop/cleanup and restart tests.

Native tests independently verify that descriptors do not trace scalar bits,
and check actual reference offsets, nullable boxes, cyclic mutable references and
deep shared immutable structures. Full collection reclaims unreachable objects.
Fixtures with persistent roots test bounded retention and an allowed shutdown
baseline. Run Linux ASan/UBSan where available and debug/optimized LLVM execution.

Run Windows x64 and Linux native validation for relevant changes. Keep Apple
Silicon tests in the existing **weekly workflow**, with a manual run before the
feature release; do not add a per-PR macOS native trigger. Full real-client Jupyter
smoke runs on Linux, Windows and macOS, covering cross-cell declarations, values,
matches and restart. Android runs existing binding/regression tests and unsupported
union-export tests without declaring a new Kotlin union ABI.

A compatibility corpus compiles existing `परिगणना`, `चयन`, records, nullable legacy
code and identifiers named `मिलान`/`विकल्पसमूह` unchanged. Update the Hindi tutorial,
API reference, book and package documentation before publication. This
documentation-only proposal does not require compiler/native test suites.

## 22. Delivery plan and review boundary

1. **Approved contract:** review vocabulary, factory/pattern syntax, equality,
   null domains, Result/Unit and native layout/ABI. This is the current task.
2. **Family core:** resolver/specialization, construction, nominal casts,
   equality/hash and exact GC layouts on all targets; no public release yet.
3. **Matching:** shared checked plan, nested patterns, coverage, async/cleanup,
   source diagnostics, statement/expression parity and cross-target corpus.
4. **Library and tools:** Result/Unit catalog, editor/Jupyter/debug inspection,
   runnable Hindi examples and an explicit HTTP/database adapter demo.
5. **Release gates:** forced-GC, clean-host, browser and package tests, weekly Mac
   evidence, user documentation and separately authorized package/deployment release.

The first complete milestone includes the core syntax and Result described here.
List/property patterns, custom extractors, union methods, implicit error
propagation, ABI exports, automatic serialization/ORM mapping and allocation
elision remain later work.

**Questions for review:**

- Are `विकल्पसमूह` and `मिलान` suitable Hindi words?
- Is a separate `मिलान`, leaving existing `चयन` unchanged, acceptable?
- Are exhaustive statements, structural equality, explicit Result handling and
  the benefits/costs of per-case heap allocation acceptable?
- Is excluding direct C/JNI union exports from the first release acceptable?

Compiler, runtime and editor implementation starts only after approval.

## 23. Repository references

- [Language specification](language-spec.md) and [types/conversions](types-conversions.md)
- [Null-safety design and implemented decisions](null-safety-design.md)
- [Safer everyday programming Hindi tutorial](../tutorial/safer-everyday/README.md)
- [Generics/contracts](contracts-generics.md) and [native specialization](native-generics.md)
- [Native records and equality/GC contracts](native-records.md)
- [Native selection/control flow](native-control-flow.md) and [exception root cleanup](native-exceptions.md)
- [Task core and suspended frames](task-core-implementation.md)
- [Native debugger inspection](native-vscode-debug.md) and [Jupyter design](jupyter-notebook-design.md)
- [Current native runtime header](../src/main/resources/dev/bhash/backend/native/bhash_runtime.h)
- [Current ANTLR grammar](../src/main/antlr4/Bhash.g4)
