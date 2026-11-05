# Unicode identifiers and Hindi input reliability

Status — approved for implementation, with the repertoire decisions recorded in [Unicode identifier decisions](unicode-identifiers-decisions.md).

Date — 5 November 2026.

## Purpose

A Bhash identifier entered with one keyboard should resolve consistently when a user references it with a canonically equivalent spelling from another keyboard. A permitted conjunct-shaping variation should have defined behavior. When a character is invalid, the compiler should identify the character and explain how to fix it in Hindi.

This milestone covers the compiler frontend, all compilation targets, VS Code language services, playground editing and compilation, and Jupyter sessions. Its observable behavior must agree across these entry points.

## Current implementation

The existing frontend already provides part of this behavior.

- `Identifiers.key` applies NFC when constructing identifier comparison keys. Existing tests cover precomposed and decomposed nukta spellings and canonical duplicate declarations.
- The ANTLR grammar uses explicit Devanagari letter and mark ranges. Digits and underscore may continue an identifier. The language specification rejects joiners, bidi controls, ASCII letters, leading digits, and leading marks in identifiers.
- Original token spelling is retained. Identifier normalization leaves string values and comments intact.
- Keyword recognition uses literal lexer rules. Canonical symbol lookup alone does not establish equivalent keyword recognition.
- The LSP converts ANTLR code-point positions to UTF-16 positions. Jupyter has a separate notebook parsing entry point that must receive the same lexical rules.
- Native, JVM, and CLR compilation share frontend and semantic infrastructure. The playground browser target reuses managed lowering.

This is a source audit of the inspected implementation. The implementation PR must confirm these findings against current main and inventory every identifier comparison, including comparisons outside the compiler frontend. The existing explicit ranges are a Bhash lexical profile, with conformance to UAX #31 still to be established.

Relevant sources are [Identifiers.java](../src/main/java/dev/bhash/frontend/Identifiers.java), [Bhash.g4](../src/main/antlr4/Bhash.g4), [Frontend.java](../src/main/java/dev/bhash/frontend/Frontend.java), [NotebookWorker.java](../src/main/java/dev/bhash/backend/jvm/NotebookWorker.java), [TextCoordinates.java](../src/main/java/dev/bhash/lsp/TextCoordinates.java), and the [language specification](language-spec.md).

## Decisions proposed for approval

| Area | Proposed decision | Implication |
| --- | --- | --- |
| Unicode baseline | Pin Unicode 18.0 data and a versioned Bhash identifier profile | Accepted characters are reproducible across installed JDK versions |
| Identifier repertoire | Derive a Devanagari profile from XID properties and publish its exact character ranges | Some characters accepted by the old broad ranges may require a diagnostic |
| Canonical equivalence | Retain NFC comparison of identifiers | Canonically equivalent spellings resolve to one symbol |
| Joiners | Permit ZWJ and ZWNJ only in the narrow conjunct context defined below and ignore permitted joiners in comparison keys | Accepted shaping variations share symbol identity |
| Keywords | Apply the same identifier comparison policy before reserved-word classification | A spelling equivalent to a keyword remains reserved |
| Source text | Retain original source, token text, and offsets | Diagnostics and editor edits point into the user's actual text |
| Filesystem paths | Preserve actual filesystem names and use explicit ambiguity checks during module resolution | Identical logical names cannot select a different file by OS |
| Delivery | One shared policy with tests across compiler and interactive entry points | The chosen compilation target does not change identifier meaning |

These decisions are proposals. The main review choices are the repertoire, joiner equivalence, and module-resolution behavior.

## Identifier repertoire

Define a named profile such as `bhash-devanagari-1` whose generated tables are checked into the repository with the Unicode version, input checksums, and generation instructions.

The proposed starting rules are as follows.

1. A starting character belongs to `XID_Start` and has Devanagari in its `Script_Extensions` value.
2. Continuing letters and marks belong to `XID_Continue` and have Devanagari in `Script_Extensions`. Shared marks are accepted only when this property includes Devanagari.
3. ASCII digits, Devanagari digits, and underscore are explicit continuation additions. The current rules for numeric literal digit scripts are retained separately.
4. Join controls have the contextual exception described below. Other default-ignorable characters and bidi controls are excluded from identifiers.
5. Unassigned characters and characters outside the pinned tables are diagnosed explicitly.

The profile is a lexical rule. It does not attempt to validate every identifier as a correctly spelled Hindi word. Arbitrary reordering of marks is equivalent only where the selected normalization rules actually make it equivalent.

Before coding, generate a reviewable old-versus-proposed repertoire report showing every added or removed code point, its Unicode name, category, and reason. Review normalization closure, including decomposed nukta forms, and any excluded start characters whose normalized spelling remains usable. Resolve every difference explicitly before claiming conformance. Bhash should document the relevant UAX #31 requirements and all profile adjustments. [Unicode identifier profiles and conformance](https://www.unicode.org/reports/tr31/tr31-45.html)

Changing the Unicode baseline later is an explicit language-profile update. The compiler must use pinned tables rather than implicitly inheriting a host JDK's newest identifier properties. NFC behavior on the accepted repertoire must also be verified across supported JDKs. If a supported JDK cannot supply the required normalization, use a pinned implementation or narrow the reviewed repertoire before release.

## Normalization and symbol identity

Keep the original UTF-8 source and decoded character stream. For each identifier-like token, preserve its spelling and source span, validate its repertoire and joining context, remove only permitted join controls from its comparison spelling, and apply NFC to produce the comparison key. Validate the resulting key against the profile too.

For example, `क़दम` beginning with U+0958 and `क़दम` beginning with U+0915 U+093C must resolve to the same binding. The original spelling remains available for messages and editor display. `क्ष` consists of U+0915 U+094D U+0937 and `ज्ञ` of U+091C U+094D U+091E. Their rendering as conjuncts does not make them individual precomposed Unicode letters. [Unicode normalization definitions](https://www.unicode.org/reports/tr15/tr15-58.html)

Apply keys consistently to declarations, references, fields, methods, parameters, generic parameters, overload names, enum members, record components, imports, aliases, and qualified names. Equality of keys follows the existing scoping and overload rules. An inner binding may shadow an outer binding where normal Bhash rules permit it. Two declarations of one logical name in the same namespace and scope receive the existing duplicate-declaration treatment, enhanced with both spellings and locations.

String literal contents, comments, file contents, SQL strings, HTTP payloads, runtime map keys, and user input retain their existing semantics. Identifier comparison does not change general text equality. A string containing a joiner must retain that joiner.

## ZWJ and ZWNJ policy

The recommended initial exception allows either U+200D ZWJ or U+200C ZWNJ inside an identifier only in this immediate sequence of original code points.

`accepted Devanagari letter [U+093C NUKTA] U+094D VIRAMA JOINER accepted Devanagari letter`

The final letter must also satisfy the profile's starting-letter predicate. A single joiner may occupy the indicated position. Leading, trailing, doubled, whitespace-separated, and other placements are invalid. Every occurrence is checked individually. The predicate is evaluated before normalization or removal so an invalid occurrence cannot disappear during comparison.

This narrow rule targets consonant conjunct shaping. It is a Bhash customization informed by Unicode's joining-control guidance. The conformance audit must document the exact differences from the Unicode security profile. Accepting this proposal does not constitute a claim of full UTS #39 conformance. [Unicode joining controls](https://www.unicode.org/reports/tr39/tr39-34.html#Joining_Controls)

The following comparison spellings would be equivalent.

| Visible example | Code-point spelling | Comparison key |
| --- | --- | --- |
| `क्षेत्र` | U+0915 U+094D U+0937 U+0947 U+0924 U+094D U+0930 | `क्षेत्र` |
| Conjunct with ZWJ | U+0915 U+094D U+200D U+0937 followed by the rest of `क्षेत्र` | `क्षेत्र` |
| Conjunct with ZWNJ | U+0915 U+094D U+200C U+0937 followed by the rest of `क्षेत्र` | `क्षेत्र` |

As a result, declaring two of these spellings in the same scope is a duplicate declaration. Changing a joiner cannot create a separate variable. Fonts may display the spellings differently, so diagnostics should include escaped code points when necessary.

An alternative for review is to retain the present rejection policy and add explanatory diagnostics and quick fixes. That is simpler, but users must remove shaping controls supplied by their input method. Allowing joiners as distinct symbol identities would permit visually confusing separate bindings and is outside this proposal.

## Lexer and parser integration

Use a shared identifier-token policy for ordinary files and notebook cells. Scan a complete candidate before classifying it as a keyword or identifier. Validate and canonicalize the candidate, then compare its key with a canonical keyword table. Preserve longest-match behavior so `यदिसंख्या` stays an identifier.

Generate the reserved-word table from one authoritative keyword definition or verify its equivalence with the grammar in CI. Canonically equivalent spellings of standard-library names receive the same resolution as user-defined names.

ANTLR token start and stop offsets and token text must continue to describe the original source. Attach a comparison key separately or compute it through the shared service. Do not replace token text with a shorter normalized spelling because downstream source slicing uses those offsets and spellings.

Malformed identifier candidates must produce a targeted lexical diagnostic before normal parsing produces secondary errors. Recovery should consume the offending candidate without silently turning one malformed identifier into multiple valid names. Whitespace and punctuation retain their token-boundary behavior.

## Names in files and packages

Logical module components and aliases use the identifier policy. Physical paths require separate handling because Linux, Windows, and macOS can store and compare filenames differently.

- Resolve logical names against an index of actual project source paths. Derive logical keys from the intended module components while keeping physical paths intact.
- If two source paths represent the same logical source identity, report both paths and require the user to disambiguate. Preserve valid multi-file packages according to existing project rules.
- A canonical spelling in an import may resolve to a file stored with an equivalent spelling when that match is unique.
- Keep package download URLs, Git revisions, lockfile hashes, archive entry names, and arbitrary data paths exact. Do not normalize them as identifiers.
- Review archive extraction and checkout behavior when the underlying filesystem collapses equivalent names. Reject ambiguous packages at a boundary that still has access to their original entry list where possible.

Changing public identifier keys can change package APIs. Verify generated names, symbol identities, debug metadata, and exports across all backends. Existing code containing joiners was rejected previously, which limits that part of the migration. Any removed character discovered in the repertoire audit needs a documented migration and a precise diagnostic.

## Diagnostics and editor behavior

Reserve stable diagnostic codes after checking the existing catalog. The names below describe categories and do not allocate numeric codes yet.

| Category | Example Hindi explanation | Editor action |
| --- | --- | --- |
| Invalid starting character | `नाम की शुरुआत इस चिह्न से नहीं हो सकती।` | Highlight the original character |
| Invalid joiner position | `U+200D इस स्थान पर नाम में स्वीकार्य नहीं है।` | Offer removal for the affected token |
| Unsupported character | `यह अक्षर भाष की वर्तमान पहचानकर्ता सूची में शामिल नहीं है।` | Show code point and Unicode name |
| Equivalent duplicate | `ये दोनों वर्तनियाँ एक ही नाम को दर्शाती हैं।` | Show both declarations and escaped spellings |
| Ambiguous module | `इन फ़ाइलों के नाम एक ही मॉड्यूल से मेल खाते हैं।` | List the conflicting paths |

Quick fixes must explain the resulting spelling and run duplicate checks before applying edits that could merge names. Ordinary canonical variants and permitted joiners do not need repeated warnings. Provide an explicit inspection or hover detail for users who want the code-point spelling.

VS Code completion, reference lookup, rename, signature help, and any implemented debugger Watch lookup must compare keys consistently. Rename must edit the complete original token spans and handle all equivalent references. Playground tokenization and completion must use the same generated profile data. Its compilation diagnostics come from the shared frontend. Full browser LSP hosting is a subsequent milestone.

Jupyter execution, completion, inspection, function replacement, and persistent scalar lookup must share the policy. Reuse the existing rules for redefinition and kernel restart. A different accepted spelling must not create another slot or bypass an immutable binding. Notebook input and displayed string output keep their original text.

Positions must be tested after combining marks, joiners, and supplementary characters. CLI locations continue to follow the compiler's documented convention. LSP positions use the negotiated encoding, currently UTF-16 in the implementation. Jupyter cursor offsets and browser editor offsets require their own existing coordinate conversions.

## Implementation stages after approval

1. Produce the repertoire delta and identifier-comparison audit. Finalize the exact profile tables and conformance statement.
2. Implement the shared validation, comparison-key, and keyword-classification policy. Integrate ordinary and notebook parsers and add precise diagnostics.
3. Apply the policy to resolution, source-path indexing, semantic tooling, and debugger lookups. Review generated symbol names and package behavior.
4. Update editor syntax data, quick fixes, Hindi reference examples, and Jupyter guidance. Run the validation matrix and review migration findings.
5. Release the compiler and relevant editor/kernel packages from the validated revision, then update the playground artifacts and deployment.

Each stage must preserve working supported programs and original source locations. Any newly discovered language-policy decision returns for review before implementation proceeds beyond it.

## Validation and acceptance

Tests must assert observable parsing, symbol resolution, output, diagnostics, and source ranges. Merely finding each keyword in a test corpus is insufficient for this milestone.

| Area | Required cases |
| --- | --- |
| Canonical equivalence | Declare and reference nukta variants in both directions, equivalent same-scope duplicates, canonical mark ordering, and distinct non-equivalent spellings |
| Repertoire | Every generated range boundary, profile exclusions, normalization closure, leading marks and digits, unsupported scripts, and supplementary code points |
| Joiners | Both controls in valid contexts, multiple valid conjuncts, invalid leading/trailing/repeated placements, and equivalent duplicate detection |
| Keywords | All reserved words, canonical alternatives where applicable, keyword prefixes, permitted shaping variants, and invalid embedded controls |
| Scope and types | Parameters, block shadowing, classes, inherited members, overloads, generics, interfaces, enums, records, and closures |
| Async and notebooks | Equivalent names across cells, immutable reassignment, completion and inspection, awaited initializers, and early-return persistence rules |
| Text preservation | Exact strings and joiners in output, JSON, SQL parameters, file contents, and byte buffers |
| Tooling | Diagnostic highlights, rename ranges, completion replacement ranges, CRLF, combining sequences, and emoji before an error |
| Modules | Equivalent imports, aliases, unique file matches, path collisions, packages, and a checkout on each supported OS |

Run execution fixtures on JVM, CLR, and native, with browser compilation and execution for supported fixtures. Reuse forced-GC tests where normalized names appear in closures, objects, async frames, and exception cleanup. Unicode processing itself introduces no additional runtime normalization requirement for generated applications.

Run full Jupyter smoke tests on Linux, Windows, and macOS. Keep native macOS validation on its approved weekly schedule, and run it manually for this frontend change before claiming the milestone validated on every target. Run pinned Unicode normalization conformance cases and the generated-profile checks in CI. Record the source revision, profile version, runtime/JDK versions, and any skipped validation.

Acceptance requires agreement between all entry points, a reviewed repertoire delta, defined and tested joiner behavior, stable original-source locations, exact preservation of runtime text, successful supported examples, and documented API or filename migration findings. A performance check on a large Devanagari source file should confirm that scanning and key construction remain bounded by input size.

## Review checklist

- Approve the Unicode 18.0 baseline and the Devanagari XID-derived repertoire, subject to the explicit delta report.
- Approve contextual ZWJ/ZWNJ acceptance with equivalent symbol identities, or choose continued rejection with better diagnostics.
- Approve canonical keyword recognition and preservation of original source spans.
- Approve logical module equivalence with explicit physical-path ambiguity errors.
- Approve the rollout across compiler, VS Code, playground, and Jupyter, including manual native macOS validation for this milestone.

Approval authorizes implementation of this design. The generated repertoire delta remains a review gate before changing the accepted character set.
