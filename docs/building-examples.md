# Building every example

From the repository root, build the compiler and then the examples:

```sh
mvn clean package -DskipTests
python scripts/build_examples.py
```

The script discovers every `.bhash` file under `examples/`. A directory containing
`bhash.project` is one compilation unit: its source files are built together, rather
than attempting to compile helper classes individually. Library packages such as
`packages/core` and `packages/greetings` are application dependencies; they have no
standalone executable. `packages/app` builds with both dependencies and its committed lockfile.
Dependency caches and generated build directories are excluded from discovery.

Applications are compiled, **not executed**. Interactive examples will not wait for
console input, and file-writing examples will not create their demonstration files.
Lessons and tutorial transcripts keep their separate verification scripts.

## Output directories

```text
target/examples/
  jvm/
    hello.jar
    native/hello.jar
    multi-file.jar
    packages/app.jar
  dotnet/
    hello/BhashProgram.dll
    native/hello/BhashProgram.dll
    multi-file/BhashProgram.dll
    packages/app/BhashProgram.dll
  native/
    hello
    native/hello
    packages/app
  logs/                 # compiler diagnostics, separated by target and example
  build-report.json     # every selected target/unit and its result
```

Relative source paths are preserved, so `examples/hello.bhash` and
`examples/native/hello.bhash` have separate outputs. .NET folders contain the DLL,
runtime configuration and licensing files required to run the application.
The report's `artifact` paths are relative to `output_root`; its `sources` entries
show the files grouped into each project. A successful compiler exit must also
produce the expected JAR, DLL or executable.

```sh
java -jar target/examples/jvm/native/hello.jar
dotnet target/examples/dotnet/native/hello/BhashProgram.dll
./target/examples/native/native/hello
```

## Target availability and failures

JVM builds require a JDK (17+). .NET builds require the .NET 10 SDK; a missing SDK
or compilation error fails the build. `JAVA_HOME` and `BHASH_DOTNET` are respected.

Native builds currently require Linux x86-64 and LLVM 18 (`clang-18`, `opt-18`, or
`BHASH_CLANG` / `BHASH_LLVM_OPT`). The default command records native as unavailable
on other hosts or when these tools are missing, while still building JVM/.NET.
Use `--require-native` to make native toolchain availability mandatory:

```sh
python3 scripts/build_examples.py --require-native
```

On a supported native host, the script attempts every application. Only an explicit
`BH9001` native-profile diagnostic can mark a general example unsupported. Syntax,
semantic, dependency-lock and tool/linker failures are errors. Every application
under `examples/native/` must compile; even `BH9001` there fails the build.

Temporary compiler projects are created inside `output_root/.tmp` and cleaned up
by the compiler, keeping build intermediates in the workspace.

The report records `built`, `unsupported`, `unavailable`, `library` and `failed`
results. Compiler logs explain individual failures. Existing outputs are preserved
on failed or skipped builds; consult the latest report rather than treating an old
artifact as newly built. `mvn clean` removes the default output tree for a fresh build.

## Selecting targets and CI

```sh
python scripts/build_examples.py --target jvm --target clr
python3 scripts/build_examples.py --target native --require-native
python scripts/build_examples.py --output-root target/example-build
```

The CLI target name `clr` maps to the `dotnet` output directory. `--compiler` and
`--examples` can select another packaged compiler or example tree. Output trees
must be separate from example sources; conflicting output names are rejected.

Managed CI builds every application for JVM and .NET through `smoke_test.py`, then
runs its existing transcript/licensing checks using those artifacts. Native CI builds
every applicable application, inspects all executable dependencies, and retains the
existing clean-container output/GC checks. Both jobs upload the complete output tree
and report as artifacts. Native binaries remain available from the Linux native job.

For local smoke checks after a completed example build:

```sh
python scripts/smoke_test.py --skip-example-build
```
