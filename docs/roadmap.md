# Implementation roadmap

## Milestone 0 — repository and syntax (this starter)

Grammar, generated parser build, CLI syntax checking, sample programs, language
draft, target catalog and automated frontend tests. Code generation: not started.

## Milestone 1 — semantic frontend

- Source spans and AST visitor; preserve literal source text and original names.
- NFC symbol keys, scope tree, function signature pass and duplicate detection.
- Literal decoding, digit conversion, surrogate/range validation and typed AST.
- Type checking, entry-point rules, return-path analysis and loop-control rules.
- Diagnostic codes and negative semantic test corpus.

Done when well-typed samples pass and each semantic rule has a failing fixture.

## Milestone 2 — first executable JVM slice

- Emit hello world and integer expressions, then locals, calls, branches and loops.
- Implement console runtime and entry wrapper; execute factorial and verify output.
- Add bytecode verification and integer edge-case tests.

Done when a `.jar` built from Bhash source runs independently with a supported JRE.

## Milestone 3 — shared IR and runtime contract

- Introduce typed control-flow IR and verifier; refactor JVM emission to consume it.
- Freeze observable numeric/text/I/O behavior with golden tests.
- Specify target triples, runtime linkage and backend diagnostics.

## Milestone 4 — native and WebAssembly

- Prototype LLVM IR emission from Java; pin an external LLVM toolchain.
- x86-64 Linux assembly/object/executable pipeline first, then AArch64 cross-build.
- WASM/WASI runtime, memory and I/O support; execute using a chosen WASI engine.
- Compare output and errors against JVM golden tests, including overflow/traps.

## Milestone 5 — CLR

- Select CIL emitter/assembler toolchain and managed runtime integration.
- Implement assembly metadata, runtime helpers and executable packaging.
- Run the common conformance corpus under a supported .NET runtime.

## Later language growth

Modules, arrays/records, standard library, explicit conversions, debugger mapping,
editor tooling, package management and platform FFI. Choose a license and contribution
policy before accepting community work. Grammar changes must include examples,
spec updates and tests; generated code remains excluded from version control.
