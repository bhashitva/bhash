# JVM compiler validation

The JVM implementation is tested by compiling Bhash source to standalone JARs
and executing them in separate Java processes with bytecode verification enabled.
There is no compiler/ANTLR classpath when running the generated programs.

Current local checks: all 28 JUnit tests pass (15 frontend tests and 13 JVM/CLI
test groups), plus `scripts/smoke_test.py` against the packaged compiler.

Coverage includes:

- Interactive factorial for ASCII/Devanagari input, 0!, 1!, 5!, 6!, 10! and 20!.
- Negative/out-of-domain input messages; malformed, mixed-script, overflowing
  and missing console input with nonzero runtime exit status.
- Multiple reads, recursive/forward calls, scopes, loops, break/continue and returns.
- Short-circuit logic and left-to-right argument evaluation.
- Type/name/signature errors, immutable bindings, entry points and unreachable code.
- Signed integer boundaries, wrapping overflow, division/remainder edge cases,
  operator precedence, real arithmetic, Unicode text and value equality.
- CLI output/source protection, executable JAR contents and failed-compile preservation.

GitHub Actions runs `mvn verify` and packaged compiler/application smoke tests
on both Windows and Linux, and uploads compiler and factorial JAR artifacts.
See the repository's Actions page for current CI results.

The CLR, WASM and native targets are not implemented or tested.
