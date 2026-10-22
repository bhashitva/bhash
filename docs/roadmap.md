# Implementation roadmap

## Completed: syntax and first executable JVM backend

- ANTLR grammar, Java parser, CLI and syntax diagnostics.
- Function signature collection, normalized symbol resolution and lexical scopes.
- Type checking, entry-point and argument validation, immutable bindings,
  return/control-flow checks, literal decoding and range validation.
- Java lowering and executable JVM JAR generation, including its console runtime.
- `पढ़ो()` integer console input, UTF-8 output, interactive factorial example.
- Frontend tests plus independent-process JVM execution and error-case tests.

## Next: frontend structure and developer experience

- Extract a source-located typed AST from combined semantic checking/Java lowering.
- Improve diagnostic codes, multiple-error recovery and runtime source mapping.
- Expand standard input support beyond integers; design explicit numeric conversions.
- Choose an open-source license and contribution policy.

## Shared IR and portability

- Introduce typed basic-block IR and a verifier; refactor JVM emission to consume it.
- Extend the current arithmetic, text and I/O golden tests into a backend corpus.
- Specify target triples, runtime linkage and backend diagnostics.

## Native and WebAssembly

- Prototype LLVM IR emission from Java and pin an external LLVM toolchain.
- x86-64 Linux assembly/object/executable pipeline, then AArch64 cross-build.
- WASM/WASI runtime, memory and I/O support; execute using a chosen WASI engine.
- Match JVM behavior on overflow, division, Unicode, errors and evaluation order.

## CLR

- Select CIL emitter/assembler toolchain and managed runtime integration.
- Implement metadata, entry wrapper, runtime helpers and executable packaging.
- Run the common conformance corpus under a supported .NET runtime.

## Later language growth

Modules, arrays/records, arbitrary-precision arithmetic, standard library, debugger
support, editor tooling, package management and FFI. Changes require a spec update,
examples and tests. Generated code and compiled JARs remain build products.
