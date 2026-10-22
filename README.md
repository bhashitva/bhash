# Bhash / भाष

A Devanagari-first programming language with a compiler written in Java.
Bhash source (`.bhash`) compiles to a **JVM executable JAR** or a **.NET CLR managed assembly**.
Both targets support the same console factorial source.

## Try the interactive factorial program

Install **JDK 17+** and **Maven 3.9+**, then run these commands from a UTF-8
terminal (PowerShell, Windows Terminal, macOS Terminal or a Linux terminal):

```bash
git clone https://github.com/bhashitva/bhash.git
cd bhash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-console.bhash
```

At the prompt, enter `5` or `५`, then press Enter:

```text
० से २० तक एक पूर्णांक लिखें:
5
गुणनफल: १२०
```

Or compile once and run the generated application separately:

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-console.bhash -o target/factorial.jar
java -jar target/factorial.jar
```

The generated JAR requires **Java 17+ only**. It includes its small console runtime
and does not need Maven, the Bhash compiler or ANTLR to run. Building Bhash programs
requires a full JDK because this backend lowers checked Bhash to Java source and
uses the JDK compiler to produce JVM bytecode. It is a compiler, not a parse-tree
interpreter. CLR output is also implemented (see below); WASM and native output remain planned.

On Windows, if Devanagari input is garbled, run `chcp 65001` before starting Java
and use a font with Devanagari support.

Prebuilt compiler JARs and factorial applications for both targets are available in the artifacts of a
successful [GitHub Actions run](https://github.com/bhashitva/bhash/actions).
Extract the artifact ZIP before running the applications.

## Run the same program under .NET

Install the **.NET 10 SDK** (in addition to Java/Maven for building the compiler).
From your updated repository checkout:

```bash
git pull
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-console.bhash --target clr
```

Enter `5` or `५`; the result is `गुणनफल: १२०`. To produce a reusable CLR application:

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-console.bhash --target clr -o target/factorial-clr
dotnet target/factorial-clr/BhashProgram.dll
```

The output folder contains `BhashProgram.dll`, `BhashProgram.deps.json` and
`BhashProgram.runtimeconfig.json`. Keep all three together when copying the
application. The compiled CLR application needs only the **.NET 10 runtime**;
it does not need Java, Maven, the .NET SDK, or Bhash to run. It is framework-dependent,
not a native executable or a Windows .NET Framework assembly.

The Java-written compiler lowers checked Bhash to C# and invokes `dotnet publish`.
There are no NuGet package dependencies. The .NET 10 SDK/reference packs must be
installed locally. Use `BHASH_DOTNET` to specify a dotnet executable outside PATH.
By default the CLI continues to target JVM; select CLR with `--target clr`.

## The program

```text
कार्य गुणनफल(संख्या: पूर्णांक) -> पूर्णांक {
    यदि (संख्या <= १) { लौटाओ १; }
    लौटाओ संख्या * गुणनफल(संख्या - १);
}

कार्य मुख्य() -> शून्य {
    लिखो("० से २० तक एक पूर्णांक लिखें:");
    स्थिर संख्या: पूर्णांक = पढ़ो();
    यदि (संख्या < ० || संख्या > २०) {
        लिखो("कृपया ० से २० तक की संख्या दें।");
        लौटाओ;
    }
    लिखो("गुणनफल:", गुणनफल(संख्या));
}
```

`पढ़ो()` reads one signed integer per line, using ASCII or Devanagari digits.
Invalid input, EOF and values outside signed 64-bit range produce a clean error
and exit status 1. The example accepts 0–20 because `20!` is the largest factorial
that fits in `पूर्णांक`. Negative values and values over 20 produce the example's
range message and return normally. Integer output uses Devanagari digits.

## Commands

| Command | Behavior |
| --- | --- |
| `check file.bhash` | Syntax validation only |
| `parse file.bhash` | Print the ANTLR parse tree |
| `compile file.bhash [-o output] [--target jvm|clr]` | Validate semantics and emit a JVM JAR or CLR output folder |
| `run file.bhash [--target jvm|clr]` | Compile temporary output, run with console I/O, then clean up |
| `targets` | List implemented and planned targets |

Without `-o`, JVM compilation writes `<source-stem>.jar` beside the source; CLR
compilation writes `<source-stem>-clr/`. JVM builds replace an existing output file.
CLR builds can replace only a directory owned by a previous Bhash CLR build, marked
with `.bhash-clr-output`; unrelated files/directories are preserved and rejected as
output destinations. Source files are protected from accidental overwrite. Failed
compilation preserves previous output. Exit codes: `0` success, `1` source/runtime error, `2` usage/file/tool
I/O error. `run` forwards the generated process's exit status.

## Implemented language core

- Devanagari keywords and NFC-normalized identifiers; ASCII/Devanagari numbers.
- Signed 64-bit integers, binary64 reals, booleans and immutable Unicode text.
- Typed functions, forward calls, recursion, mutable/immutable locals and lexical scope.
- Conditions, loops, break/continue, returns, short-circuit operators and text equality.
- Entry-point, name, type, return-path, literal-range and control-flow checks.
- UTF-8 console input/output, a JVM backend and a .NET 10 CLR backend.

The compiler reports the first semantic error with the Bhash source location.
Loop return analysis is conservative: a value-returning function needs a return
path outside a potentially zero-iteration loop. Large programs may reach normal
Java method/class limits; optimization and debug source maps remain future work.

## Project map

| Path | Purpose |
| --- | --- |
| `src/main/antlr4/Bhash.g4` | ANTLR grammar |
| `src/main/java/dev/bhash/frontend/` | Parser and identifier handling |
| `src/main/java/dev/bhash/backend/ManagedEmitter.java` | Shared semantic checking and managed source lowering |
| `src/main/java/dev/bhash/backend/jvm/` | JVM JAR generation |
| `src/main/java/dev/bhash/backend/clr/` | CLR build and deployment-folder generation |
| `src/main/resources/dev/bhash/backend/` | Java and C# runtime sources embedded in generated applications |
| `examples/factorial-console.bhash` | Interactive factorial application |
| `src/test/java/dev/bhash/` | Frontend and end-to-end tests |
| `scripts/smoke_test.py` | Packaged compiler checks for CI |
| `docs/language-spec.md` | Language contract |
| `docs/architecture.md` | Current pipeline and future targets |
| `docs/roadmap.md` | Next milestones |

GitHub Actions installs .NET 10 and runs Maven tests plus JVM/CLR application
checks on Windows and Linux. CLR integration tests are skipped locally if .NET 10
is absent; set `BHASH_REQUIRE_CLR=true` to make missing SDK support a test failure.
CI always sets this requirement. Generated sources, classes, JARs and CLR deployment folders remain build products
in `target/`. See [CLR details](docs/clr-target.md) for runtime and output behavior.
No open-source license has been selected yet.
