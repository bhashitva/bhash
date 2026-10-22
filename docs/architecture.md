# Compiler architecture

## Implemented JVM pipeline

1. Decode UTF-8, accept an optional leading BOM, then lex/parse with ANTLR.
2. Collect function signatures and reject duplicate or reserved names.
3. Resolve NFC-normalized names in lexical scopes; validate types, entry point,
   arguments, bindings, returns, loops and literal values.
4. Lower expressions and statements to Java, using synthetic Java identifiers.
5. Use the JDK `JavaCompiler` API with `--release 17`, annotation processing disabled,
   and an isolated classpath to produce bytecode.
6. Package generated classes and the small runtime into an executable JAR with a
   `BhashProgram` entry point. Stage the output before replacing its destination.

`ManagedEmitter` currently combines semantic checking and Java lowering over ANTLR
contexts for both managed targets. This is a working first backend, not the final target-independent AST/IR.
`JvmCompiler` handles the JDK invocation, packaging and temporary-file cleanup.
The compiler requires a full JDK 17+; generated applications require Java 17+ only.
They contain no ANTLR dependency and no generated Java source.

`run` compiles a temporary JAR, starts a separate Java process with inherited
console streams, forwards its exit status and removes the JAR afterwards.
Programs are ordinary applications with the current user's permissions, not sandboxed.

## Semantic and runtime behavior

All Bhash names become synthetic Java names, allowing nested variable shadowing
without Java naming restrictions. Runtime integer input accepts either digit script;
output uses Devanagari digits. Java's left-to-right evaluation, short-circuit logic,
64-bit wrapping arithmetic and binary64 operations implement the core contract.
Text equality uses value comparison. Integer division/remainder helpers give even
literal zero divisors clean runtime errors. Console input errors and arithmetic
failures produce concise UTF-8 diagnostics with exit status 1.

Semantic diagnostics use Bhash source locations. Unexpected Java toolchain failures
are reported as backend errors. Debug source maps, runtime Bhash stack traces,
resource limits and optimization are future work. Return analysis treats all loops
as potentially executing zero iterations. JVM method/class size and recursion-stack
limits still apply.

## Implemented CLR pipeline

`ManagedEmitter` shares signature collection, normalized name resolution, type
checking, scope and flow validation with JVM. Its CLR lowering emits C# using
synthetic names, ordinal text equality and explicit `unchecked` integer arithmetic.
Runtime helpers preserve signed MIN/-1 division/remainder behavior, avoiding CLR's
native overflow trap for that special case. Strings are constructed from validated
Unicode scalars; console streams use strict UTF-8.

`ClrCompiler` writes an isolated C# project, invokes the .NET 10 SDK's
`dotnet publish`, and installs a DLL, dependency manifest and runtime configuration.
The project has no NuGet dependencies and clears external package feeds; installed
SDK reference packs are required. Builds use a timeout and disable build servers.
Outputs are framework-dependent: .NET 10 runtime is needed to execute them; Java
and the SDK are not needed on the deployment machine. Use `BHASH_DOTNET` for a
custom host path. The `run --target clr` command forwards console I/O and exit status.

CLR outputs are staged and installed only after a successful build. A marker and
known-file check permit rebuilding compiler-owned output folders while rejecting
unrelated files, folders and symlinks. Existing compiler output is preserved on
build failure. Do not remove the marker if you want to rebuild into that directory.

## Objects on both managed targets

The shared emitter first registers class types, links single inheritance and rejects
cycles, then collects fields, constructor signatures and virtual method slots in
base-first order. Member names are normalized like existing local/function names.
Type checking handles related reference types, null, member access and visibility.
Overrides reuse the inherited slot name; Java virtual dispatch and C# virtual/override
methods implement the same Bhash behavior. Private members and hiding rules are
checked before host compilation.

Bhash classes lower to synthetic nested host classes; no user-provided identifier is
inserted as a host class or member name. JVM packaging includes every generated nested
class; CLR classes are included in the managed assembly. Constructors emit base
initialization first, then field initializers, then the body. Constructor escape and
current-instance method calls are restricted to avoid partially initialized virtual
objects. Runtime helpers provide reference identity and consistent null errors.

See [the object model](objects.md) for exact language rules and current limitations.

## Target status

| Target | Status | Route |
| --- | --- | --- |
| JVM | Implemented | Bhash → checked Java → JVM bytecode → executable JAR |
| .NET CLR | Implemented | Bhash → checked C# → .NET 10 managed DLL + deployment metadata |
| WASM | Planned | Proposed LLVM lowering with WASI runtime |
| x86-64 | Planned | Proposed LLVM assembly/object emission, initially Linux System V ABI |
| ARM64 / AArch64 | Planned | Proposed LLVM assembly/object emission, initially Linux AAPCS64 |

ANTLR generates the compiler's Java parser, not Bhash's application backends.
LLVM remains a proposal and is not a dependency. CLR uses the .NET SDK/C# compiler and its own runtime helpers. It does not depend
on LLVM.
32-bit architectures, browser WASM and other native OS/ABI combinations are separate
extensions. Native assembly still needs assembling/linking and a target runtime.

Next, extract a source-located typed AST and shared control-flow IR from the current
backend, then use cross-backend golden tests to preserve evaluation, arithmetic,
text and I/O semantics when adding another target.
