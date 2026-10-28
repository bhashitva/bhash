# Compiler architecture

## Implemented JVM pipeline

1. Discover project sources with `ProjectLoader` (or accept one file), decode UTF-8,
   accept an optional leading BOM in each file, then independently lex/parse with ANTLR.
2. `ProjectResolver` collects package-qualified declarations, validates file-local
   imports and package visibility, and resolves type/function references. A combined
   declaration tree retains original source tokens; files are not concatenated before parsing.
3. Specialize used generic types/functions across the resolved project with `GenericExpander`.
4. Collect function signatures and reject duplicate or reserved names.
5. Resolve NFC-normalized names in lexical scopes; validate types, entry point,
   arguments, bindings, returns, loops and literal values.
6. Lower expressions and statements to Java, using synthetic Java identifiers.
7. Use the JDK `JavaCompiler` API with `--release 17`, annotation processing disabled,
   and an isolated classpath to produce bytecode.
8. Package generated classes and the small runtime into an executable JAR with a
   `BhashProgram` entry point. Stage the output before replacing its destination.

`ManagedEmitter` currently combines semantic checking and Java lowering over ANTLR
contexts for both managed targets. This is a working first backend, not the final target-independent AST/IR.
`JvmCompiler` handles the JDK invocation, packaging and temporary-file cleanup.
The compiler requires a full JDK 17+; generated applications require Java 17+ only.
They contain no ANTLR dependency and no generated Java source.

`run` compiles a temporary JAR, starts a separate Java process with inherited
console streams, forwards its exit status and removes the JAR afterwards.
Programs are ordinary applications with the current user's permissions, not sandboxed.

## Project resolution and source locations

`SourceFile` represents one independently parsed unit. Backend compiler APIs accept
an ordered list of units as well as the existing single-source arguments. All three
backends call the same `ManagedEmitter` project entry point. The resolver gives each
package-qualified type/function an internal identity, rewrites only resolved symbolic
references, and combines lowered declarations into one program for the existing checker.
It does not treat imports as textual includes or execute imported files.

Class/member names and lexical bindings remain in their existing scopes. Type parameters
shadow imported type names; local bindings and inherited members shadow imported expression
names. Explicit imports resolve types or full function overload groups and validate visibility
before body checking. Overload groups must use uniform package visibility.

`LocatedToken` retains the originating token source and coordinates through both package
resolution and generic specialization. Diagnostic formatting recovers the actual defining
file rather than reporting a merged/generated file. Internal package names are translated
back to qualified Bhash names in semantic error messages. Host names are still synthetic;
this introduces no stable binary library ABI or incremental compilation format.

See [projects and packages](projects-packages.md) for the manifest and language contract.

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

## Implemented browser WebAssembly pipeline

`WasmCompiler` reuses the CLR emitter and conditional runtime I/O adapters. It
publishes a .NET 10 WebAssembly SDK project with a JavaScript-exported host and
packages all static assets. A module worker loads the runtime for each execution;
input is a pre-supplied string and output/errors are returned as strings when the
program finishes. Terminating the worker stops execution. AOT and native relinking
are disabled; this is a managed-runtime bundle rather than a WASI module.

Staging preserves previous output on build failure. A SHA-256 asset manifest rejects
replacement of output with added or modified files. See [browser target](wasm-browser.md).

## Interfaces, generics, overloads and static storage

`GenericExpander` specializes used generic classes, interfaces and global functions before
semantic checking, preserving original token locations for diagnostics. Repeated closed types
share their specialization; an expansion limit rejects recursively growing templates. Each
specialization has distinct static storage on all targets, including primitive arguments.

`ManagedEmitter` registers interfaces and class contracts, orders inheritance, and validates
complete public instance implementations. Stable slots keyed by source method name and concrete
parameter types align interface calls, inherited overloads and virtual overrides across targets.
Overload selection is performed in Bhash using compile-time types; constructors receive explicitly
cast arguments so host-language overload rules cannot change the selection. Static fields/methods
lower to host static members with Bhash access and receiver checks.

See [v0.3 rules](contracts-generics.md) for supported generic forms and remaining limits.

## Selection lowering

The checker validates literal labels and rejects duplicate decoded values. Selection lowers
to a once-evaluated temporary plus an if/else chain, supporting 64-bit integers without Java's
native switch restrictions. Java uses a labeled block and CLR uses an exit label for selection
breaks. A break-target stack distinguishes loops from selections; native continue statements
still target the enclosing loop. Each section has a separate scope and contributes to shared
return/reachability analysis. See [selection rules](switch.md).

## Bitwise lowering

Integer-only bitwise AND, OR, XOR and complement lower to host 64-bit integer
operations. Shift helpers mask the original 64-bit count with 63 before narrowing
it, explicitly implementing left shift, sign-preserving right shift and zero-fill
right shift. CLR zero-fill uses an unchecked `ulong` reinterpretation and converts
back to signed `long`, including at distance zero. Browser WASM uses the same CLR
runtime helpers. Helper calls preserve once-only left-to-right operand evaluation.

ANTLR retains individual `<` and `>` tokens so nested generic closing brackets do
not turn into shift tokens. A parser rule checks that the characters of expression
shifts are contiguous. Both package lowering and generic expansion render a shift
operator as one joined spelling before reparsing. Source-token remapping continues
to preserve the originating filename and coordinates. See [bitwise rules](bitwise.md).

## Arrays and native generic collections

`CollectionTypes` identifies predeclared array/list/map names. `GenericExpander`
preserves these constructors while specializing user templates, substituting array
suffixes and nested collection arguments. The checker interns collection types and
enforces invariant type identity; host generic arguments use boxed Java primitives
and native CLR value types. `सरणी<T>` and `T[]` resolve to one array type.

Index locations carry a setter expression so receivers, keys and assigned values
are evaluated once in source order. Array factories evaluate all dimensions once,
validate lengths before allocation, and create independent nested rows. Default
primitive/text values are filled without allocating a separate boxed default per cell.
Synthetic constructor tag types distinguish Bhash constructor overloads whose
collection parameters erase to identical JVM signatures.

Both runtime templates implement fixed arrays, growable lists and ordered hash maps.
Map keys and membership share explicit primitive/text/identity equality with normalized
floating-point zero and NaN behavior. Java uses a linked hash map; CLR combines a
hash dictionary and linked insertion order. Iterators check structural versions and
report portable Bhash errors. `प्रत्येक` lowers to host foreach with a once-evaluated
source, typed immutable bindings, and the existing break/continue/return analysis.
See [collections](collections.md) for the public language contract.

## Target status

| Target | Status | Route |
| --- | --- | --- |
| JVM | Implemented | Bhash → checked Java → JVM bytecode → executable JAR |
| .NET CLR | Implemented | Bhash → checked C# → .NET 10 managed DLL + deployment metadata |
| Browser WASM | Implemented | Bhash → checked C# → .NET 10 WebAssembly runtime bundle |
| Native Linux x86-64 | Experimental, initial Linux CI passed | Bhash → typed core → LLVM 18 IR → object code + Bhash C runtime → executable |
| WASI | Planned | Standalone command-line WebAssembly runtime/backend |
| x86-64 | Planned | Proposed LLVM assembly/object emission, initially Linux System V ABI |
| ARM64 / AArch64 | Planned | Proposed LLVM assembly/object emission, initially Linux AAPCS64 |

ANTLR generates the compiler's Java parser, not Bhash's application backends.
LLVM 18 is required only for the experimental native executable backend; it is not a dependency of managed builds. See [native design and validation gate](native-compilation-design.md). CLR uses the .NET SDK/C# compiler and its own runtime helpers. It does not depend
on LLVM.
32-bit architectures, WASI and other native OS/ABI combinations are separate
extensions. Native assembly still needs assembling/linking and a target runtime.

The experimental native path captures a source-located typed core for its supported
subset during existing checking. It currently discards managed source generation;
see the native design for this transitional boundary.

Next, complete extraction of a source-located typed AST and shared control-flow IR from the current
backend, then use cross-backend golden tests to preserve evaluation, arithmetic,
text and I/O semantics when adding another target.

## Exception lowering

The shared emitter validates exception subtyping, handler order/scopes and abrupt
completion before producing host try/catch/finally and throw statements. A built-in
`अपवाद` class maps to the runtime's unchecked `BException`; existing `Failure`
errors extend it. User exception subclasses use ordinary class lowering, including
constructor overload tags. Catch boundaries unwrap static initialization wrappers
only for known Bhash exceptions. Finally may throw but cannot return or jump to an
outer loop, keeping source semantics valid on both JVM and CLR (including browser
.NET WebAssembly). Runtime stack traces and exception causes are not yet exposed.

## Portable types and data

The managed type graph includes byte/int32 primitives, general boxed values,
enum definitions, sealed record definitions and packed buffers. Enum members
lower to typed singleton instances; records lower to sealed classes with readonly
fields and shared structural equality/hashing. Record constructors retain the
existing constructor tags, including generic specializations.

Collection wrappers carry invariant type descriptors on the object itself, so
JVM erasure cannot let an incompatible list/map/array pass a runtime test or cast.
Every collection allocation, nested array row and returned snapshot receives its
canonical descriptor. CLR also validates these descriptors alongside native type
checks. The approach needs no global object registry. Scalar boxing keeps byte,
int32, int64, real, boolean and text distinct. Conversion and cast helpers raise
Bhash failures with the same checked boundaries across runtimes.

Packed buffers use native byte arrays with unsigned Bhash reads. Binary accesses
explicitly assemble/disassemble bits; endianness never depends on the host. UTF-8
decoders reject malformed bytes. The browser target reuses the CLR implementation.
