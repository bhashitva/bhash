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

`JavaEmitter` currently combines semantic checking and Java lowering over ANTLR
contexts. This is a working first backend, not the final target-independent AST/IR.
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

## Target status

| Target | Status | Route |
| --- | --- | --- |
| JVM | Implemented | Bhash → checked Java → JVM bytecode → executable JAR |
| .NET CLR | Planned | CIL emission and selected .NET assembly toolchain |
| WASM | Planned | Proposed LLVM lowering with WASI runtime |
| x86-64 | Planned | Proposed LLVM assembly/object emission, initially Linux System V ABI |
| ARM64 / AArch64 | Planned | Proposed LLVM assembly/object emission, initially Linux AAPCS64 |

ANTLR generates the compiler's Java parser, not Bhash's application backends.
LLVM remains a proposal and is not a dependency. Managed CLR output requires its
own emitter/runtime; it does not follow automatically from native LLVM support.
32-bit architectures, browser WASM and other native OS/ABI combinations are separate
extensions. Native assembly still needs assembling/linking and a target runtime.

Next, extract a source-located typed AST and shared control-flow IR from the current
backend, then use cross-backend golden tests to preserve evaluation, arithmetic,
text and I/O semantics when adding another target.
