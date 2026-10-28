# Implementation roadmap

## Completed: syntax, JVM, CLR and browser WebAssembly backends

- ANTLR grammar, Java parser, CLI and syntax diagnostics.
- Function signature collection, normalized symbol resolution and lexical scopes.
- Multi-file executable projects, packages, file-local imports/aliases, public exports,
  qualified references and cross-file checking on all three implemented targets.
- Interfaces, explicit generic types/global functions, overload resolution and static members.
- Fixed and jagged arrays, indexing, generic growable lists, insertion-ordered maps,
  portable key equality and collection iteration with structural-mutation checks.
- Checked numeric conversions, 8-bit unsigned bytes, 32-bit integers, packed byte buffers,
  UTF-8/binary I/O, typed enums, immutable generic records and reified runtime type tests/casts.
- Bundled मानक package: text, math, seeded PRNG, JSON, UTC timestamps and capability-gated UTF-8/binary file I/O, with editor discovery.
- Typed exceptions, custom exception subclasses, ordered catch handlers, throw/rethrow
  and finally cleanup across all three managed targets.
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
- Completed initial diagnostic codes, Hindi guidance and bounded recovery across independent bodies/statements.
- Completed declaration-based member completion, signature help, automatic imports and import Quick Fixes.
- Extend recovery through declaration/type-graph failures; runtime source mapping remains pending.
- Expand standard input support beyond integers; extend numeric parsing and formatting.
- Maintain Apache 2.0 licensing, contribution guidance and third-party notices.

## Project and library follow-ups

- Completed versioned Bhash source packages with local paths, exact Git revisions,
  deterministic integrity lockfiles, explicit restore/verify commands and editor integration.
  See [source packages](source-packages.md) and the [Hindi tutorial](../tutorial/packages/README.md).
- Public package registry, authenticated private Git and version-range resolution.
- Incremental compilation, stable exported signatures and separately compiled libraries.
- Explicit target-specific Java JAR and .NET assembly/NuGet interoperability.

## Shared IR and portability

- Introduce typed basic-block IR and a verifier; refactor JVM emission to consume it.
- Extend the current arithmetic, text and I/O golden tests into a backend corpus.
- Specify target triples, runtime linkage and backend diagnostics.

## Native and standalone WASI

An experimental direct-LLVM implementation and approved automatic-memory-management
design are under development. See [native compilation](native-compilation-design.md)
for scope, initial successful Linux validation and the remaining regression gate.

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
immutable fields, record bodies/patterns, collection slices/copy constructors/custom iterators, arbitrary-precision arithmetic, broader standard
library coverage, debugger support, editor tooling, dependency package management and FFI. Changes require a spec update,
examples and tests. Generated code, JVM JARs, CLR deployment folders and browser bundles remain build products.
