# Managed compiler validation

The JVM implementation is tested by compiling Bhash source to standalone JVM JARs and CLR assemblies
and executing them in separate Java/.NET processes (with bytecode verification enabled on the JVM).
There is no compiler/ANTLR classpath when running the generated programs.

The v0.7 suite contains 168 JUnit tests, including project source discovery, package/import
resolution, frontend checks and a shared JVM/CLR execution corpus. Packaged compiler/application
smoke checks separately exercise the public CLI. CI runs the suite on Linux and Windows.

The common execution/semantic/CLI test corpus runs for each managed backend,
with extra CLR tests for output preservation and directory ownership. JVM tests
require a JDK; CLR tests require the .NET 10 SDK. CI requires both backends.

Coverage includes:

- Interactive factorial for ASCII/Devanagari input, 0!, 1!, 5!, 6!, 10! and 20!.
- Negative/out-of-domain input messages; malformed, mixed-script, overflowing
  and missing console input with nonzero runtime exit status.
- Multiple reads, recursive/forward calls, scopes, loops, break/continue and returns.
- Short-circuit logic and left-to-right argument evaluation.
- Type/name/signature errors, immutable bindings, entry points and unreachable code.
- Constructors, fields, composition, chained access, object identity, null failures,
  multi-level dynamic dispatch, base calls, visibility, subtype arguments/returns,
  invalid overrides/cycles, and portable initialization order.
- Signed integer boundaries, wrapping overflow, division/remainder edge cases,
  operator precedence, real arithmetic, Unicode text and value equality.
- CLI output/source protection, executable JAR contents and failed-compile preservation.
- Typed array defaults, rectangular/jagged allocation, indexing/evaluation order,
  collection invariance, list operations, ordered map snapshots and structural-mutation checks.
- Primitive/text/NaN/zero/identity map-key and membership behavior, null values,
  generic collection interfaces/functions and constructor overloads across generic erasure.
- Multi-file collection examples plus checked bounds, missing/null keys, null receivers,
  immutable iteration bindings, source scopes and nested loop/selection control flow.
- Integer-only bitwise operators, precedence, eager once-only evaluation, all 64 bits,
  masked negative/wide shift counts, generic bodies and nested generic closers.
  JVM/CLR execute a 66-case shift corpus; browser checks use a separate BigInt oracle.
- Multi-folder projects, source roots, deterministic discovery, UTF-8/BOM handling,
  manifest validation and protection of every input file.
- File-local imports/aliases, qualified references, package visibility, duplicate names,
  NFC package names, lexical shadowing and defining-file diagnostic locations.
- Cross-file inheritance, generic interfaces/functions/containers, split overload groups,
  invariant type identities and shared static storage across aliases and source files.

GitHub Actions runs `mvn verify` and packaged compiler/application smoke tests
on both Windows and Linux, and uploads the compiler JAR, factorial JVM JAR and
factorial CLR deployment folder, plus multi-file JVM/CLR applications and their sources,
as downloadable artifacts.
See the repository's Actions page for current CI results.

The browser WebAssembly workflow installs .NET 10 `wasm-tools` and Chromium,
builds thirteen application bundles, and executes `scripts/browser_smoke_test.cjs`.
It checks procedural/object factorial (including exact 20!), input validation,
objects and virtual dispatch, null/division errors, 64-bit boundaries/overflow,
text rendering, reruns, Stop and output replacement safeguards. It uploads the
compiler, browser bundles and screenshots. See [browser target](wasm-browser.md).
The shared JVM/CLR suite also covers interface inheritance/implementation, generic classes,
interfaces and global functions, nested specializations, invariant type arguments, generic
inheritance, constructor/function/method overloads, ambiguity, static storage/access and
invalid contracts and static initializer errors. The packaged and Chromium checks execute the combined v0.3 example.
Selection tests cover grouped labels, no fall-through, a default in any position, full-width
integers, ordinal Unicode text, booleans, once-only selector evaluation, independent scopes,
nested break/continue behavior, generic bodies, duplicate/invalid labels and return-path
analysis. Packaged and browser checks run the console example and browser control-flow fixture.
The browser corpus also executes the seven-file factorial project, covering package imports,
inheritance, generic interface/container identity, function overloads split between files and
qualified static access. See [project commands](projects-packages.md).
Browser checks also execute the bitboard example and bitwise boundary/evaluation fixture,
and reject invalid operand types before publishing.
The browser collection corpus runs the multi-file example, generic/array/ordered-map
semantics and twelve runtime-error scenarios, including mutation during iteration.
Standalone WASI and native targets remain unimplemented.
