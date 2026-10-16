# Compiler architecture

The compiler is implemented in Java. ANTLR generates a **Java lexer/parser**;
ANTLR's own language targets do not implement Bhash's output targets.

## Shared pipeline

1. Decode UTF-8 and handle an optional leading BOM (implemented in CLI).
2. Lex and parse with `Bhash.g4`, collecting diagnostics (implemented).
3. Build a source-located AST through `BhashBaseVisitor` (next).
4. Collect function signatures; resolve names using NFC-normalized keys.
5. Type-check, validate entry point, returns, loops and literal ranges.
6. Lower to a typed, target-independent IR with basic blocks, explicit branches,
   calls, typed values and source spans.
7. Emit backend code and link the target's Bhash runtime.

Only steps 1–2 exist today. Never generate runnable code from a parse tree that
contains diagnostics. Keep ANTLR classes within the frontend boundary when the
AST is introduced; later compiler phases should consume AST/IR rather than
parser contexts. A future `Backend` interface should take verified IR plus a
target triple and return artifacts/diagnostics. It is deliberately not stubbed
against an imaginary IR in this starter.

## Target strategy (proposed)

| Target | Emission route | Required supporting work |
| --- | --- | --- |
| JVM | Emit `.class` bytecode using a Java bytecode library such as ASM | JVM verification, entry wrapper, runtime JAR, stack-map frames |
| .NET CLR | Emit CIL and assemble to a managed assembly using a selected .NET toolchain | Assembly metadata, CLI entry point, runtime support library and SDK/tool distribution |
| WASM | Lower IR to LLVM IR, then use an LLVM WASM toolchain | WASI runtime first; UTF-8 I/O, memory model and toolchain compatibility |
| x86-64 | Lower IR to LLVM IR, emit assembly/object code | Initially Linux System V ABI, linking, runtime library |
| ARM64 / AArch64 | Lower IR to LLVM IR, emit assembly/object code | Initially Linux AAPCS64, cross-linking, runtime library |

LLVM and ASM are architecture proposals, not installed dependencies or implemented
emitters. The compiler remains written in Java if it invokes LLVM command-line
tools; those would be external build prerequisites. Managed JVM/CLR output needs
separate backend work and does not follow automatically from native LLVM support.
If learning instruction selection is a primary goal, replace the native LLVM
route with direct assembly emitters; expect more ABI/register-allocation work.

The user-requested x86 and ARM families are initially narrowed to **64-bit**.
32-bit x86/ARM, Windows/macOS native ABIs and browser WASM are separate extensions.
Native assembly alone is not a runnable executable; assembly and linking still
require a target toolchain and matching Bhash runtime.

## Runtime and portability

Specify arithmetic, evaluation order, text equality and console behavior once,
then test every backend against the same programs. Map language errors explicitly:
JVM/CLR exceptions, WASM traps and native signals are not automatically equivalent.
Managed strings may use UTF-16 internally while native/WASM strings use UTF-8;
the language's observable text behavior must agree. Start without user heap objects;
string lifetime and allocation still require a runtime strategy before adding
concatenation, dynamic input or collections.

Recommended order: frontend and semantics → JVM execution → verified shared IR
→ LLVM native/WASM and CLR implementations. A JVM-first slice makes the language
usable while the broader backend design develops.
