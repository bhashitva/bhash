# Starter validation

Validated on 2026-10-16 UTC:

- ANTLR 4.13.2 generated the Java lexer, parser and visitor without errors.
- Java 17 source compiled locally with Eclipse ECJ 3.37.0.
- All 15 JUnit frontend and CLI tests passed locally.
- GitHub Actions successfully ran the full Maven `verify` lifecycle, including
  parser generation, Java compilation, tests and shaded executable JAR packaging.
- The packaged JAR successfully checked both `hello.bhash` and `factorial.bhash`.

Verified CI run: https://github.com/bhashitva/bhash/actions/runs/38835530967

No semantic checker, runtime execution or code generation has been implemented.
These checks establish frontend syntax behavior and executable CLI packaging only.
