# Bhash / भाष

A Devanagari-first programming language with a compiler written in Java.
The project name is **Bhash**, the language name is **भाष**, and source files use `.bhash`.

```text
कार्य मुख्य() -> शून्य {
    लिखो("नमस्ते, विश्व!");
}
```

## What works today

- ANTLR 4 grammar and generated Java lexer/parser/visitor.
- Syntax checking with file, line and column diagnostics; parse-tree inspection.
- Devanagari keywords and identifiers, ASCII or Devanagari numeric literals.
- Functions, typed bindings, calls, conditionals, loops and expressions.
- Automated tests and GitHub Actions build configuration.

This is a **compiler frontend starter**, not yet an executable language implementation.
`check` checks syntax only. It does not check names, types, entry points, returns,
loop control placement or numeric ranges. It does not execute programs or emit code.
The five output targets are planned; no backend is implemented.

## Build and try

Install a **JDK 17 or later** and **Maven 3.9 or later**. Use a UTF-8 editor/terminal
and a font with Devanagari shaping support. Run from the repository root:

```bash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar check examples/hello.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar parse examples/factorial.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar targets
```

Exit codes: `0` success, `1` syntax error, `2` usage or file error.
Generated ANTLR sources are build products in `target/`, not checked in.
Maven uses matching ANTLR tool and runtime versions, pinned to 4.13.2.

## Project map

| Path | Purpose |
| --- | --- |
| `src/main/antlr4/Bhash.g4` | Authoritative syntax grammar |
| `src/main/java/dev/bhash/frontend/` | Parsing, diagnostics, identifier normalization helper |
| `src/main/java/dev/bhash/cli/` | Command-line entry point |
| `src/main/java/dev/bhash/backend/Target.java` | Planned target catalog |
| `src/test/java/dev/bhash/FrontendTest.java` | Frontend and CLI tests |
| `examples/` | Sample source programs |
| `docs/language-spec.md` | Proposed v0.1 syntax and semantic contract |
| `docs/architecture.md` | Compiler pipeline and target strategy |
| `docs/roadmap.md` | Ordered implementation milestones |

Start language design in the specification, change the grammar, add a positive
and negative test, and add/update an example. The vocabulary is a draft for your review.

## Design decisions to revisit

The starter proposes static typing, explicit type annotations, Devanagari-only
names, semicolons and braces, x86-64 and ARM64 as the initial native architectures,
and a JVM-first implementation. Classes, arrays, modules, FFI, garbage collection
and exception syntax remain future design work. No open-source license is selected;
choose one before inviting external reuse or contributions.

References: [ANTLR downloads](https://www.antlr.org/download.html),
[ANTLR Maven plugin](https://www.antlr.org/api/maven-plugin/latest/).
