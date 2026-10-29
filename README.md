# Bhash / भाष

[License: Apache 2.0](LICENSE) · [Support Bhash development](https://www.zeffy.com/en-US/donation-form/knowledge-without-language-barriers)


A Devanagari-first programming language with a compiler written in Java.
Bhash source (`.bhash`) compiles to a **JVM executable JAR**, a **.NET CLR managed assembly**, or a **browser WebAssembly application**.
All three targets support the same factorial source.

## Learn and teach Bhash

The [12-lesson classroom course](lessons/README.md) includes Hindi lesson plans,
Bhash-only examples, challenge starters and reference solutions. Teachers can use
the [Bhash-to-English concept map](lessons/concept-map.md) to connect `यदि`/`लौटाओ`
to `if`/`return` and prepare learners for Java/C# concepts.

The separate [tutorial references](tutorial/README.md) explain individual topics in Hindi,
with familiar English names, runnable examples, expected output and API limits.
Start with the [complete standard-library tutorials](tutorial/standard-library/README.md).

## Try the interactive factorial program

Install **JDK 17+** and **Maven 3.9+**, then run these commands from a UTF-8
terminal (PowerShell, Windows Terminal, macOS Terminal or a Linux terminal):

```bash
git clone https://github.com/bhashitva/bhash.git
cd bhash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-console.bhash
```

To compile every example for JVM, .NET and native where supported, run
`python scripts/build_examples.py` after packaging the compiler. Outputs are stored
in `target/examples/jvm`, `target/examples/dotnet` and `target/examples/native`.
See [building all examples](docs/building-examples.md) for prerequisites and the build report.

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
interpreter. CLR and browser WebAssembly output are also implemented (see below); the experimental Linux x86-64 native target supports the subset described in the
[native guide](docs/native-compilation-design.md).

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

## Multi-file projects and packages

Create `bhash.project` in your project directory:

```properties
name=my-application
sources=src,shared
```

Use `संकुल` to declare a package, `आयात` to import a type or function, and optional
`उपनाम` aliases to distinguish names. Mark top-level declarations `सार्वजनिक` when
another package needs them. Files in the same package resolve each other's declarations
automatically. Classes, interfaces, inheritance, generics, overloads and static members
are checked across the whole project on **JVM, CLR and browser WebAssembly**.

The [seven-file factorial example](examples/multi-file/) is ready to run:

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/multi-file
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/multi-file --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/multi-file --target wasm-browser -o target/multi-file-web
```

Serve browser output over HTTP as described in [the browser guide](docs/wasm-browser.md).
See [project commands and package rules](docs/projects-packages.md) for details.
Versioned source libraries now support local dependencies, pinned Git commits and
verified lockfiles. See [dependency management](docs/source-packages.md), the
[three-package example](examples/packages/README.md), and the
[Hindi tutorial](tutorial/packages/README.md).
Existing single-file commands continue to work. External library distribution and
Java/.NET library interoperability remain planned.

## Bitwise operations and chess bitboards

Bhash v0.6 supports `&`, `|`, `^`, `~`, `<<`, `>>` and `>>>` on 64-bit
`पूर्णांक` values across JVM, CLR and browser WebAssembly. Right shift `>>`
preserves the sign; `>>>` fills with zeroes. Shift counts use their low six bits.

After pulling the changes, rebuild the compiler to update the JAR:

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/bitboards.bhash
```

See [operator rules and examples](docs/bitwise.md) for precedence, shift counts,
generic parsing and CLR/browser commands.

## Arrays, lists, maps and iteration

Bhash v0.7 adds zero-based arrays (`पूर्णांक[]`), generic lists (`सूची<त>`),
insertion-ordered maps (`मानचित्र<क,व>`) and collection iteration:

```text
स्थिर अंक: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,२,३};
अंक.जोड़ो(४);
प्रत्येक (अ: पूर्णांक में अंक) { लिखो(अ); }
```

The same [multi-file collection example](examples/collections/) works on all three targets:

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/collections
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/collections --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/collections --target wasm-browser -o target/collections-web
```

See [collection syntax, APIs and mutation rules](docs/collections.md).

The experimental Linux x86-64 native target now supports arrays, lists, maps and
iteration for integers, booleans, text, object/interface references and `सर्ववस्तु`.
See [native collections and collector rules](docs/native-collections.md).

## Exception handling

Bhash v0.8 supports `प्रयास` (try), `पकड़ो` (catch), `अंततः` (finally),
`फेंको` (throw/rethrow), and custom classes extending `अपवाद`. Existing runtime
failures are catchable through the same type; read their message with `.संदेश`.

```text
प्रयास { लिखो(१ / ०); }
पकड़ो (गलती: अपवाद) { लिखो(गलती.संदेश); }
अंततः { लिखो("कार्य समाप्त।"); }
```

After rebuilding, run the [exception-handling factorial](examples/exceptions.bhash):

```bash
git pull --ff-only
mvn clean package -DskipTests
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/exceptions.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/exceptions.bhash --target wasm-browser -o target/exceptions-web
```

See [exception rules and cleanup behavior](docs/exceptions.md).

The experimental Linux native target also supports try/catch/finally, throw/rethrow,
custom exceptions and safe GC-root unwinding. See [native exceptions](docs/native-exceptions.md).

Native LLVM now supports `दशमलव` arithmetic, comparisons, checked conversions, formatting, and boxing/unboxing; see the [native decimal guide](docs/native-decimals.md) and [runnable example](examples/native/decimals.bhash).

The native target also supports every `मानक.गणित` and `मानक.यादृच्छिक` function, including matching seeded streams; see the [native math/random guide](docs/native-math-random.md).

Native text split/join and strict JSON parsing/serialization are also available, with typed nested collections and safe partial-failure cleanup; see [native structured text](docs/native-json-text.md).
All six UTC time-library functions are available on native Linux; see [native time](docs/native-time.md).
Native UTF-8 console input (`पाठपढ़ो()` and `पढ़ो()`) is described in [native console input](docs/native-console.md).
Native bitwise operators, shifts, and `चयन` statements are described in [native control flow](docs/native-control-flow.md).
Native signed 32-bit `लघुपूर्णांक`, conversions, boxing, and collections are described in [native int32](docs/native-int32.md).
Native static fields, methods, lazy class initialization, and persistent GC roots are described in [native static members](docs/native-static-members.md).
Native packed byte buffers, UTF-8 encoding/decoding and binary file I/O are also available; see [native bytes](docs/native-bytes.md).
Native UTF-8 text file I/O is available through `मानक.फ़ाइल`; see [native files](docs/native-files.md).

## Standard library

The compiler bundles the `मानक` package on JVM, .NET CLR and browser WASM:

```text
आयात मानक.पाठ्य;
आयात मानक.जेसन;
कार्य मुख्य() -> शून्य {
    स्थिर शब्द: सूची<पाठ> = पाठ्य.बाँटो("भाष,भारत,ज्ञान", ",");
    लिखो(पाठ्य.जोड़ो(शब्द, " · "));
    लिखो(जेसन.पाठबनाओ(शब्द));
}
```

Text search/replace/split/join, fixed numeric formatting, math, seeded randomness,
JSON parsing/serialization, UTC dates and bounded UTF-8/binary file I/O share typed
APIs. Text and binary files work on JVM/CLR and native Linux; check `मानक.फ़ाइल.समर्थित()` in portable programs.
No registry, Maven or NuGet dependency is needed by a compiled application.
See [all APIs and portability rules](docs/standard-library.md) and
[the runnable example](examples/standard-library/portable.bhash).

## Types, conversions and binary data

Bhash v0.9 adds checked numeric conversions, unsigned `बाइट`, signed 32-bit
`लघुपूर्णांक`, packed `बाइटबफ़र`, enums (`परिगणना`), immutable generic records
(`अभिलेख`), general values (`सर्ववस्तु`) and runtime type tests/casts.

```text
परिगणना स्थिति { लंबित, तैयार = ५ }
अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
कार्य मुख्य() -> शून्य {
    स्थिर छोटा: बाइट = परिवर्तित<बाइट>(२५५);
    स्थिर मूल: सर्ववस्तु = नया बिंदु(२,३);
    लिखो(छोटा, प्रकारहै<बिंदु>(मूल), परिवर्तित<बिंदु>(मूल).अ);
}
```

`परिवर्तित<T>(value)` performs checked conversion/casting; `प्रकारहै<T>(value)`
tests runtime type, and `सुरक्षितरूप<T>(value)` returns null on a failed reference
cast. Generic collection tests distinguish element types on every target.

After rebuilding, run `examples/types` on JVM or CLR, or compile it with
`--target wasm-browser`. See [type rules and buffer APIs](docs/types-conversions.md)
and the [multi-file example](examples/types/).

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

## Objects, classes and inheritance

The v0.2 core supports classes (`वर्ग`), constructors (`निर्माता`), object creation
(`नया`), fields, instance methods (`कार्य`), single inheritance (`विस्तार`), explicit
method overriding (`अधिलेखन`), `यह`/`आधार`, access modifiers and null references
(`रिक्त`). These features compile on **JVM, .NET CLR and browser WebAssembly**.

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/objects.bhash --target clr
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-objects.bhash --target clr
```

The account example prints `मनोज १५० १५ १६५` on all three targets. The object-oriented
factorial example accepts console input and dispatches through a base-class reference.
See the [object model and keyword guide](docs/objects.md) for syntax, constructor
rules, visibility, reference types and current scope.

## Commands

| Command | Behavior |
| --- | --- |
| `check file.bhash` | Syntax validation only |
| `parse file.bhash` | Print the ANTLR parse tree |
| `compile file.bhash [-o output] [--target jvm|clr|wasm-browser]` | Validate semantics and emit a JVM JAR, CLR folder or browser bundle |
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
- Signed 64-bit integers, binary64 reals, booleans, immutable Unicode text and class references.
- Typed functions, forward calls, recursion, mutable/immutable locals and lexical scope.
- Conditions, loops, break/continue, returns, short-circuit operators and text equality.
- Entry-point, name, type, return-path, literal-range and control-flow checks.
- UTF-8 console input/output, a JVM backend and a .NET 10 CLR backend.

The compiler reports coded diagnostics with Hindi guidance, English detail and Bhash
source locations. Independent body errors are collected in one pass (up to 50);
invalid declarations/imports may still stop semantic analysis early. See
[diagnostics and editor assistance](docs/developer-experience.md).
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
| `examples/objects.bhash` / `examples/factorial-objects.bhash` | Classes, constructors, inheritance and polymorphism |
| `src/test/java/dev/bhash/` | Frontend and end-to-end tests |
| `scripts/build_examples.py` | Build all example applications into JVM, .NET and native output trees |
| `scripts/smoke_test.py` | Packaged compiler checks for CI |
| `docs/language-spec.md` | Language contract |
| `docs/architecture.md` | Current pipeline and future targets |
| `docs/roadmap.md` | Next milestones |

GitHub Actions installs .NET 10 and runs Maven tests plus JVM/CLR application
checks on Windows and Linux. CLR integration tests are skipped locally if .NET 10
is absent; set `BHASH_REQUIRE_CLR=true` to make missing SDK support a test failure.
CI always sets this requirement. Generated sources, classes, JARs and CLR deployment folders remain build products
in `target/`. See [CLR details](docs/clr-target.md) for runtime and output behavior.
### Browser WebAssembly

Build a browser application using the .NET 10 WebAssembly runtime:

```sh
dotnet workload install wasm-tools
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-objects.bhash --target wasm-browser -o target/factorial-web
python -m http.server 8000 --directory target/factorial-web
```

Open <http://localhost:8000>, enter `५`, and click **Run / चलाएँ** to see `गुणनफल: १२०`. Classes and inheritance are supported. Input is supplied before execution; runs can be stopped and repeated. See [browser target documentation](docs/wasm-browser.md) for prerequisites, deployment and testing.

### Interfaces, generics, overloads and static members

The v0.3 language adds `अनुबंध` (interface), `पालन` (implementation) and `साझा` (static).
Generic classes/interfaces and global functions use explicit type arguments, such as
`पात्र<पूर्णांक>` and `पहचान<पाठ>("नमस्ते")`. Functions, methods and constructors can overload
by parameter types. The same features work on JVM, CLR and browser WebAssembly.

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/contracts-generics.bhash --target clr
```

See the [complete example](examples/contracts-generics.bhash) and [v0.3 rules and limitations](docs/contracts-generics.md).

### Selection statements

Use `चयन`, `विकल्प` and `शेष` to select by integer, text or boolean value. Sections do not
fall through; consecutive labels can share a body. `विराम` exits the nearest selection or loop.

```text
चयन (संख्या) {
    विकल्प १: लिखो("एक");
    विकल्प २: लिखो("दो");
    शेष: लिखो("अन्य");
}
```

See [the runnable example](examples/switch-console.bhash) and [v0.4 selection rules](docs/switch.md).

## License

Bhash is licensed under the **[Apache License, Version 2.0](LICENSE)**. This includes
its compiler, grammar, runtime helpers and examples. You choose the license for
applications you write in Bhash; included Bhash runtime/support code retains its
Apache license and notices. See [licensing details](LICENSING.md), [project attribution](NOTICE),
[third-party notices](THIRD_PARTY_NOTICES.md) and [contribution guidelines](CONTRIBUTING.md).

## Support Bhash

Bhash is a project of **[Nij Bhasha Sanstha, Inc.](https://bhashitva.org/)**, which works
to make knowledge and tools accessible in Indic languages. Help support Bhash's
open-source compiler development through the **Knowledge Without Language Barriers** campaign:

**[Donate through Zeffy](https://www.zeffy.com/en-US/donation-form/knowledge-without-language-barriers)**

Donations are voluntary; Bhash remains available under Apache 2.0 regardless of whether you donate.

## Self-hosted browser playground

A mobile-friendly CodeMirror editor and queued compilation API are included.
Visitors can write a statement, click **चलाएँ**, and run the compiled .NET
WebAssembly program without installing Java or .NET. Local drafts, snippets,
examples, file exchange and offline editing are supported. First runs need a
runtime download, and new compilation needs a network connection.

The Docker Compose pilot is configured for an unused Ubuntu 24.04 VPS with
2 vCPUs and 8 GB RAM. See [VPS deployment and operating limits](deploy/playground/README.md).
This is a self-hosted deployment package; it is not automatically installed on
Hostinger when the repository is updated.

## Visual Studio Code

The [Bhash VS Code extension](editors/vscode/README.md) provides syntax highlighting,
Devanagari/English snippets, compiler-backed multi-file diagnostics, completion,
outline, typed member completion, signature help, automatic imports, declaration
navigation, and JVM/CLR/browser build tasks. Install from the
[Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=bhashitva.bhash)
or use the VSIX from a successful **Bhash VS Code extension** Actions build. Java 17+ is required;
the packaged extension includes its compiler and does not require Maven.
