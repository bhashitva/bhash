# Implementation roadmap

## Completed: syntax, JVM, CLR and browser WebAssembly backends

- ANTLR grammar, Java parser, CLI and syntax diagnostics.
- Function signature collection, normalized symbol resolution and lexical scopes.
- Multi-file executable projects, packages, file-local imports/aliases, public exports,
  qualified references and cross-file checking on all three implemented targets.
- Interfaces, explicit generic types/global functions, overload resolution and static members.
- Selection statements with typed literal cases, grouped labels, default, scopes and nested loop control.
- Integer bitwise AND/OR/XOR/complement and three portable 64-bit shifts, including
  generic syntax compatibility and a chess bitboard example.
- Classes, constructors, typed fields, member methods, single inheritance, access
  control, explicit overrides, virtual dispatch, object references and null checks.
- Type checking, entry-point and argument validation, immutable bindings,
  return/control-flow checks, literal decoding and range validation.
- Shared managed semantic checker, Java lowering and executable JVM JAR generation.
- C# lowering, .NET 10 CLR DLL/deployment metadata generation and console runtime.
- Browser WebAssembly runtime bundles, pre-supplied input, output/error display,
  worker isolation, Stop/restart and Chromium execution checks.
- `पढ़ो()` integer console input, UTF-8 output, interactive factorial example.
- Frontend tests plus independent-process JVM/CLR execution and error-case tests.

## Next: frontend structure and developer experience

- Extract a source-located typed AST from combined semantic checking/Java lowering.
- Improve diagnostic codes, multiple-error recovery and runtime source mapping.
- Expand standard input support beyond integers; design explicit numeric conversions.
- Maintain Apache 2.0 licensing, contribution guidance and third-party notices.

## Project and library follow-ups

- Versioned portable Bhash source dependencies and reproducible dependency lockfiles.
- Incremental compilation, stable exported signatures and separately compiled libraries.
- Explicit target-specific Java JAR and .NET assembly/NuGet interoperability.

## Shared IR and portability

- Introduce typed basic-block IR and a verifier; refactor JVM emission to consume it.
- Extend the current arithmetic, text and I/O golden tests into a backend corpus.
- Specify target triples, runtime linkage and backend diagnostics.

## Native and standalone WASI

- Prototype LLVM IR emission from Java and pin an external LLVM toolchain.
- x86-64 Linux assembly/object/executable pipeline, then AArch64 cross-build.
- WASM/WASI runtime, memory and I/O support; execute using a chosen WASI engine.
- Match JVM behavior on overflow, division, Unicode, errors and evaluation order.

## CLR follow-ups

- Additional target-framework and deployment options, including self-contained publishing.
- Debug/source mapping and runtime Bhash stack traces.
- Exact cross-target binary64 decimal rendering; current JVM and .NET runtimes can
  choose different shortest last-digit spellings for some double values.

## Later language growth

Abstract/final members, generic member methods, constraints/inference, constructor-assigned
immutable fields, arrays/records, arbitrary-precision arithmetic, standard
library, debugger support, editor tooling, dependency package management and FFI. Changes require a spec update,
examples and tests. Generated code, JVM JARs, CLR deployment folders and browser bundles remain build products.
