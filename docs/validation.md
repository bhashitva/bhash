# Starter validation

Validated on 2026-10-16 UTC:

- ANTLR 4.13.2 generated the Java lexer, parser and visitor without errors.
- Java 17 source compiled using Eclipse ECJ 3.37.0 against the ANTLR runtime.
- JUnit Platform 1.11.4 / Jupiter 5.11.4 ran all 15 tests successfully.
- Both example files parsed; tests also exercised invalid syntax and CLI exit codes.

The complete Maven lifecycle and shaded executable JAR were not verified in
the preparation environment because Maven dependency downloads were blocked by
its network/proxy configuration. The provided GitHub Actions workflow runs
`mvn verify` and checks both examples through the packaged JAR. That workflow
has not run yet; there is no claimed successful GitHub Actions build.

No semantic checker, runtime execution or code generation has been tested or
implemented. The tests establish frontend syntax behavior only.
