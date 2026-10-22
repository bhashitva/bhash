# .NET CLR target

## Build and run

Install a .NET 10 SDK, JDK 17+ and Maven 3.9+. From the repository root:

```bash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/factorial-console.bhash --target clr -o target/factorial-clr
dotnet target/factorial-clr/BhashProgram.dll
```

Or compile/run in one step:

```bash
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar run examples/factorial-console.bhash --target clr
```

The Bhash source is unchanged from the JVM example. Enter `5` or `५` to receive
`गुणनफल: १२०`. The factorial example guards its 0–20 input domain; the language's
integer type is signed 64-bit. Malformed input and EOF produce a concise UTF-8
error with exit code 1. Both backends use one integer per line, either digit script,
and Devanagari integer output.

## Output and prerequisites

The compiler remains written in Java. It lowers type-checked Bhash into C# and
invokes `dotnet publish` to emit CIL in `BhashProgram.dll`. Copy the complete output
folder, including `.deps.json` and `.runtimeconfig.json`, when deploying it.
The result runs on the .NET 10 runtime without Java, Maven or the .NET SDK.
This is modern cross-platform .NET, not Windows-only .NET Framework or native AOT.

Use `dotnet --list-sdks` to check that a 10.x SDK is installed. If dotnet is outside
PATH, set `BHASH_DOTNET` to the full executable path. For example in PowerShell:

```powershell
$env:BHASH_DOTNET = 'C:\Program Files\dotnet\dotnet.exe'
```

The default target remains JVM. CLR's `-o` names an output **directory**, not a DLL.
Without `-o`, the directory is `<source-stem>-clr` beside the input file.
Rebuilding is supported for directories made by Bhash; a `.bhash-clr-output` marker
tracks ownership. Unmanaged directories, unrelated contents and symlink outputs
are rejected. Failed builds preserve earlier output. Newly generated C# and build
logs are temporary and are not included in the deployed output.

The generated project has no external packages and clears NuGet feeds. It uses
reference packs from the installed .NET 10 SDK. Compilation times out after 120
seconds. SDK failures include the build diagnostics; missing dotnet explains how
to install/configure the prerequisite.

## Semantic portability

The common checker enforces the same entry point, types, immutable bindings,
name resolution and control-flow rules. CLR lowering uses unchecked integer
arithmetic and runtime helpers for division/remainder so wrapping, zero errors
and MIN/-1 behavior match JVM. Text equality is ordinal; console input validates
strict UTF-8 and matches Java's whitespace stripping.

Binary64 arithmetic works on both targets. Real-number display uses each host's
round-trip digits, normalized to the same punctuation/exponent style; some values
can differ in their last displayed digits from Java 17. This does not affect the
integer factorial program. Arbitrary-precision factorials, exact real formatting
parity, additional .NET versions and native/self-contained deployment are future work.

The same backend test corpus and packaged factorial checks run under .NET and
Java on Windows and Linux in GitHub Actions. CLR tests skip locally when SDK 10
is absent; `BHASH_REQUIRE_CLR=true` makes missing SDK support fail as it does in CI.
