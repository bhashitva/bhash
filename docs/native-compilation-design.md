# Native LLVM compilation and automatic memory management

Status: **Approved architecture; experimental implementation with initial Linux/LLVM validation.**
Date: 2026-10-27.

## Decisions and implications

The owner selected direct LLVM generation, Linux first, an integer/literal-text
subset first, and automatic memory management designed up front. The approved first
collector is **precise, stop-the-world, nonmoving mark-and-sweep**, with compiler/runtime
contracts that permit future generations and relocation.

Application programmers do not allocate/free storage manually or annotate ownership.
Allocation is automatic when language operations need storage. Reachable objects
remain valid; unreachable graphs, including cycles, can be reclaimed. Collection
is nondeterministic. This is the automatic-memory-management contract associated
with Java/.NET, implemented by Bhash's own runtime rather than either managed VM.

This first stage makes applications independent of JVM/.NET. The compiler itself
remains Java-based; making it independently installable is separate work. Native
executables still depend on their documented OS libraries and loader. The initial
Linux x86-64 baseline is Ubuntu 24.04, not every Linux distribution or architecture.
Windows, ARM64, macOS, WASI and cross-compilation are follow-ups.

## Pipeline and toolchain

```text
Bhash sources / verified source packages
  -> parsing and package resolution
  -> existing semantic checking + source-located typed core
  -> direct LLVM IR
  -> LLVM verification and native object generation
  -> link Bhash native runtime
  -> Linux x86-64 executable
```

`CoreProgram` stores resolved binding/function identities, types, constants, source
locations and structured control flow without host-language expressions. Analysis
hooks in `ManagedEmitter` capture the existing checker and overload decisions. The
managed source it constructs is currently discarded on the native path: this is
migration scaffolding, not Java compilation or parsing of generated Java. The
existing managed lowering remains intact. A full SSA/control-flow IR and optimizer
are not required for this milestone.

`LlvmEmitter` emits LLVM IR directly. The runtime is C17 compiled separately; Bhash
programs are not translated to C. LLVM provides code generation and GC integration
mechanisms, not a ready-made collector. See [LLVM GC integration](https://llvm.org/docs/GarbageCollection.html)
and [object generation](https://llvm.org/docs/tutorial/MyFirstLanguageFrontend/LangImpl08.html).

The experimental backend requires LLVM 18 (`clang-18`, `opt-18`). Explicit executable
paths can be supplied using `BHASH_CLANG` and `BHASH_LLVM_OPT`; these are paths, not
shell commands or lists of flags. Tool versions are checked. Tools are not downloaded
automatically. Build-time JDK/LLVM requirements do not carry over to the executable.

## Initial supported language

- `पूर्णांक`, `तार्किक`, `पाठ`, and `शून्य` returns.
- Initialized mutable/immutable locals, assignment, scopes and shadowing.
- Typed global functions, overloads, forward calls and recursion.
- Integer `+ - * / %`, unary signs, comparisons, equality and boolean logic.
- `यदि`/`अन्यथा`, `जबतक`, `विराम`, `जारी`, `लौटाओ`.
- Literal and heap-backed text passed, returned, assigned and compared by value.
- Concatenation and the scalar text-library subset in [native text](native-text.md).
- Ordinary heap-backed classes, constructors, instance methods and reference fields
  in [native objects](native-objects.md), including single class inheritance and
  [virtual dispatch](native-inheritance.md).
- Interface dispatch, nominal runtime tests and checked/safe casts in
  [native interfaces and casts](native-interfaces.md).
- `सर्ववस्तु` boxing/unboxing of integers, booleans, text and object/interface references
  in [native universal values](native-boxing.md).
- Arrays, growable lists, insertion-ordered maps and iteration in [native collections](native-collections.md).
- Typed exceptions, catch/finally, throw/rethrow and safe root unwinding in [native exceptions](native-exceptions.md).
- Binary64 `दशमलव` arithmetic, comparisons, conversions, formatting and boxing in [native decimals](native-decimals.md).
- All six UTC time-library functions in [native time](native-time.md), including strict timestamps and full year 0001–9999 calendar ranges.
- All existing math and random library signatures in [native math/random](native-math-random.md), including exact seeded sequences.
- Unsigned bytes, packed byte buffers, strict UTF-8 encoding/decoding and binary file I/O in [native bytes](native-bytes.md).
- UTF-8 whole-file text I/O in [native files](native-files.md), with the existing 16 MiB limit.
- Text split/join and strict JSON parsing/serialization in [native structured text](native-json-text.md).
- 64-bit bitwise operators, masked shifts, and integer/byte/text/boolean selection in
  [native bitwise operations and selection](native-control-flow.md).
- `लिखो` with multiple arguments and blank lines, plus UTF-8 console input using
  `पाठपढ़ो()` and `पढ़ो()` in [native console input](native-console.md).
- Existing multi-file/package resolution and lockfile verification, provided all
  loaded user/dependency declarations fit this profile.

Unsupported features produce source-located Hindi diagnostics, including unused
unsupported declarations. Unsupported calls to standard-library intrinsics are rejected;
injected compiler metadata alone does not require library execution.

Other integer widths except unsigned byte, unsupported conversions, unsupported
standard-library calls, static members, user-defined generics,
interop and concurrency are not yet available. The decimal-area calculation is supported;
math and seeded random library calls are supported. Other unsupported library calls receive diagnostics. Defaults and behavior of managed targets are unchanged.

## Runtime semantics

Integer LLVM operations omit overflow assumptions: 64-bit arithmetic wraps. Division
helpers check zero and minimum integer divided by minus one. Ordinary operations and
call arguments evaluate once, left to right; short-circuit operators use control-flow
branches. All output arguments finish evaluating before printing begins.

Text points to a payload containing a 64-bit byte length and UTF-8 bytes. Static
literal payloads and future managed text use compatible layouts. Equality compares
length/content, without normalization or pointer identity. Embedded NUL, supplementary
characters and combining marks are preserved. Integer output uses Devanagari digits;
booleans use `सत्य`/`असत्य`. Successful execution exits 0; arithmetic failures raise catchable `अपवाद` values.
Unhandled failures report Hindi diagnostics with Bhash locations and exit 1. Stack exhaustion and OS faults are
not covered by that clean-error guarantee.

## Heap and collector contract

Every managed allocation has an internal header recording its descriptor, payload
size, mark state and collector links. Descriptors provide minimum size and exact
reference-field offsets. The initial fixed-offset descriptor is sufficient for the
runtime graph tests; variable-length reference storage now uses the repeated-region descriptor
extension described in [native collections](native-collections.md). This is not a stable
public binary ABI. All packages are still compiled from source together.

Objects are zero-initialized before being returned. All managed references are base
payload pointers, not raw interior pointers. Descriptors must remain valid for the
object's lifetime, normally in static read-only storage.

Compiler-generated root frames identify reference slots precisely. Frames are pushed
on function entry and popped on every normal return. Parameters, text bindings and
text temporaries have initialized slots. Consumed temporary slots are released after their parent result is rooted. Block-local
slots are cleared when control leaves their scope, including loop jumps; function
frames are popped on return. Exception transfer restores the saved chain head and
clears abandoned region roots as described in [native exceptions](native-exceptions.md).
Bindings that remain in scope may outlive their last
use. [Heap-backed text](native-text.md) exercises these rules through real programs.

Allocation is the initial collection safepoint. Before allocation, callers must root
all live managed references, including intermediate arguments. Calls reload reference
arguments from their root slots after other arguments finish evaluation. The compiled
IR exposes no permanent-address guarantee. Runtime helpers must follow the same
rooting rules. There is no need to stop at loop backedges yet: execution is single
threaded and collection happens synchronously on allocation.

The collector marks roots and follows only descriptor-declared reference fields.
An intrusive worklist avoids recursive graph traversal and allocation during marking.
Sweeping frees unreachable objects and cycles. Allocation pressure counts headers
as well as payloads. An allocation failure triggers collection/retry; continued
failure emits a constant diagnostic through OS writes and terminates without managed
allocation or finalization callbacks.

Allocation lookup currently scans the heap list. This makes worst-case graph tracing
quadratic. That is acceptable for the initial correctness experiment, not a claim of
production-scale GC performance. An indexed allocation lookup, allocator improvements
and fragmentation measurements should precede substantial heap-heavy applications.

## Contracts for future collectors

- Root slots are updateable; object identity must not expose a permanent address.
- Heap reference writes pass through `bh_reference_store`, the boundary for a future
  generational write barrier. It is an ordinary checked store today.
- Descriptors, object headers and source types remain separate.
- References must not remain as stale unregistered SSA/interior pointers across
  collection-capable operations.
- A moving collector would need relocation-aware tracing and updates for heap fields,
  roots and runtime-held references. Generations would need remembered-set barriers.
- Future optimized root tracking can use LLVM statepoints/stack maps, but is not a
  drop-in switch and is not part of this implementation.
- Foreign code may not retain unregistered managed pointers. Handles, pinning,
  callbacks and thread coordination need separate designs before interoperability
  or concurrency support is introduced.

The first collector has no generations, compaction, background thread, weak references
or user finalizers. Pauses and fragmentation are possible. No low-latency guarantees
are made. Files/sockets still require deterministic resource cleanup separately from
memory reclamation. Out-of-memory failure is initially fatal, not catchable.

## Commands and distribution

Experimental usage on the supported Linux host:

```console
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar compile examples/native/hello.bhash --target native -o build/hello
./build/hello
./build/hello --bhash-license
```

Output is an executable file. Applicable Bhash license notices are embedded and
accessible using the reserved `--bhash-license` argument. JVM remains the default.
Builds use private temporary files, bounded tool output, timeouts, verification and
staging; a failed build must preserve existing output. Source/dependency protection
remains enforced. Native binary byte-for-byte reproducibility is not promised yet.

Native execution has normal user OS permissions. This is not a sandbox and is not
connected to the public playground. No extension or playground deployment is included.

## Validation and completion gate

Local checks can test typed-core extraction and compile the C runtime on Windows as
an implementation aid. That does not establish a supported Windows native backend.
Bhash text and object programs exercise allocation, rooted references, shared graphs
and cycles under forced collection; internal C tests also validate the collector independently.

The milestone is complete only after:

1. LLVM verifies generated modules and Linux executables pass the supported corpus.
2. Native output/error/exit behavior matches managed semantics for supported cases.
3. Live roots, recursion and nested calls survive forced collection; unreachable
   cycles and repeated transient allocations are reclaimed.
4. Graph traversal is nonrecursive and integer/Unicode/error boundary tests pass.
5. Sanitizers and optimized builds find no undefined behavior or rooting errors.
6. Dependency/source diagnostics and output preservation are exercised.
7. Existing managed regression checks pass after shared-analysis changes.
8. Executable inspection and a clean Ubuntu runtime container confirm no JVM/.NET
   or build-tool dependency. Runtime container execution has networking disabled.

`scripts/test_native_runtime.py` runs runtime tests at debug and optimized settings;
`--sanitize` enables address/undefined-behavior sanitizers with Clang. `--cc cl` is
available for runtime-only Windows development checks. `NativeBackendTest` runs core
checks everywhere and native execution tests on supported hosts with LLVM 18.
`BHASH_REQUIRE_NATIVE=true` makes missing native prerequisites fail CI rather than
skip. `.github/workflows/native.yml` provisions those tools and the clean-runtime test.

### Recorded validation

The first native CI run at `173221f` passed on October 27, 2026:
[Linux native validation run](https://github.com/bhashitva/bhash/actions/runs/40622557805).
It verified and executed the LLVM backend (8 tests, no skips), passed debug/optimized
collector checks with address/undefined-behavior sanitizers, built the factorial
executable and ran it in a clean Ubuntu container without Java/.NET/development tools.
The artifact includes the executable, dependency inspection and test reports.

Runtime-only Windows checks also passed in debug and optimized MSVC builds. This is
not supported Windows Bhash output. The local development environment lacks Linux
execution/LLVM, so native execution evidence comes from CI. Existing managed/backend
regression checks and the expanded cross-backend corpus must also pass on the final
PR revision before this experimental milestone is considered ready to merge.
