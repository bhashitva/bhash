# Managed compiler validation

The JVM implementation is tested by compiling Bhash source to standalone JVM JARs and CLR assemblies
and executing them in separate Java/.NET processes (with bytecode verification enabled on the JVM).
There is no compiler/ANTLR classpath when running the generated programs.

Local validation: all 61 JUnit tests passed with no skips, plus targeted private-member
access checks. The suite includes frontend tests and a shared JVM/CLR execution corpus, including
objects and inheritance, plus the packaged compiler/application smoke checks.

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

GitHub Actions runs `mvn verify` and packaged compiler/application smoke tests
on both Windows and Linux, and uploads the compiler JAR, factorial JVM JAR and
factorial CLR deployment folder as downloadable artifacts.
See the repository's Actions page for current CI results.

WASM and native targets are not implemented or tested.
