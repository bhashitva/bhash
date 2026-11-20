# Typed external-library interoperability: C bindings and compression

**Status: approved; implementation in progress.**

Date: 19 November 2026. Baseline: compiler/VS Code 0.27.0, Jupyter 0.1.13,
native runtime ABI 18. [हिन्दी संस्करण](typed-c-interop-design.md)

## 1. Purpose and scope

Allow Bhash applications to use existing C libraries through checked, typed APIs.
Application programmers use Bhash values, `मानक.परिणाम`, and `संसाधन`; binding
authors describe the native contract and supply a small C adapter when necessary.
Application code does not manipulate pointers or call allocation/free functions.

The first vertical slice is bounded, in-memory zlib/gzip compression on native,
JVM and desktop .NET. It must exercise the reusable binding mechanism, rather
than introduce another compiler special case for a particular library.

In scope: a versioned binding manifest, compiler/editor discovery, fixed C wire
types, copied buffers/text, opaque resource handles, cleanup, prebuilt artifacts,
and cross-target tests. Out of scope initially: arbitrary Java/.NET imports,
C++ ABI, Bhash callbacks from C, general struct-by-value layouts, variadic calls,
raw pointers, public streaming compression, parallel execution, and browser bindings.

## 2. Recommended decisions

| Decision | Recommendation and implication |
| --- | --- |
| Language syntax | Keep ordinary `आयात` and calls; add no `extern` keyword or unsafe block. |
| Binding authoring | Explicit manifest plus a reviewed C shim; no automatic trust in arbitrary header translation. |
| Wire boundary | Fixed-width scalars, length-delimited bytes/UTF-8, status codes, and opaque handles. |
| Memory | Copy across the boundary; never expose managed object addresses or depend on a nonmoving GC. |
| Resources | Deterministic `बंदनीय.बंदकरो`/`संसाधन`; deferred native-only fallback cleanup for unreachable wrappers. |
| Execution | Synchronous calls on the owning Bhash execution thread; no foreign reentry or Bhash callbacks. |
| Compression | One pinned zlib implementation behind the same contract on all three desktop targets. |
| Packaging | Static adapter/provider for native executables; private shared adapter/provider for JVM and CLR. |
| Browser/Android | Explicitly unsupported in milestone 1; retain design extension points without granting capabilities. |
| Release gate | Do not publish until real bindings, all desktop adapters, clean-host packaging and GC tests pass. |

## 3. Existing foundations and the new work

The [Android C/JNI interface](android-native-mobile-design.md) primarily lets a
host call into Bhash. This proposal adds calls in the opposite direction. Reuse
its fixed-width values, owned-buffer discipline and opaque-handle experience,
but use a separate versioned FFI ABI; do not silently change the embedding ABI.

Reuse [source packages](source-packages.md), strict null checking, typed Result,
[resource scopes](../tutorial/application-foundations/README.md), and the
[async transport separation](async-io.md). General binding discovery, generated
calls, external-resource fallback cleanup and artifact selection are **new work**.
Database-specific adapters do not already constitute a general FFI.

## 4. Two audiences and a reusable package contract

An application imports public declarations and obtains completion, signature help,
Hindi diagnostics and navigation to a generated, readable declaration document.
It does not need C headers or a C compiler when consuming a complete prebuilt binding.

A binding package contains Bhash facade sources where useful, a checked
`bhash.ffi.json`, the C shim source/header, platform artifact descriptors,
licenses and conformance fixtures. A proposed `ffi=bindings/bhash.ffi.json`
project property registers its declarations. This property and file format do
not exist yet. Ordinary package identity and dependency declarations remain intact.

The checker creates typed external declarations from the manifest, including
receiver, parameter, return and resource types; it must not parse fake bodyless
application functions or maintain backend-specific copies of the public API.
Names use the existing Unicode identifier profile. External packages cannot
claim the reserved `मानक` namespace; the bundled compression binding can.

The schema records: schema/contract/ABI versions, package identity, public names,
wire types, ownership, nullability, length/encoding rules, error mapping, execution
mode, resource release entry points, and supported host/runtime tuples. Each
artifact has an exact SHA-256 and provenance. Unknown fields that affect semantics,
duplicate symbols, unsupported conversions and incomplete ownership are errors.

One illustrative function entry, not a complete schema or working package:

```json
{
  "name": "संपीड़ितकरो",
  "symbol": "bhffi_compression_v1_compress",
  "execution": "owner-sync",
  "parameters": [
    {"name": "मूल", "type": "बाइटबफ़र", "wire": "bytes", "ownership": "borrow-call", "marshal": "copy"},
    {"name": "प्रारूप", "type": "मानक.संपीड़नप्रारूप", "wire": "i32"},
    {"name": "स्तर", "type": "पूर्णांक", "wire": "i32", "range": [0, 9]}
  ],
  "return": {
    "type": "मानक.परिणाम<बाइटबफ़र, मानक.संपीड़नदोष>",
    "wire": "status+owned-bytes",
    "release": "bhffi_compression_v1_bytes_release"
  }
}
```

Contract fingerprints include normalized signatures and lifetime/error rules.
Changing ownership without changing a signature is still an ABI/contract change.

Implementation detail: the contract fingerprint excludes artifact descriptors, so
generated symbol names do not depend on the hash of the binary containing them.
A separate inventory fingerprint includes those descriptors and is pinned by
lockfile version 2. The bounded `length` parameter wire maps a checked Bhash
integer in 0..16 MiB to `uint64_t`; it does not expose unrestricted unsigned integers.

Lower calls through a typed foreign-call representation keyed by declaration
identity, not compression names. Treat foreign calls as effectful in v1: do not
fold, duplicate or reorder them. Resource factories, receiver methods and close
operations declare `status+owned-handle`, the resource kind and release entry point.
A test package outside `मानक` must exercise integer addition, UTF-8/byte echo and
an owned counter resource through this same path, without compiler name recognition.

## 5. C ABI and type conversions

The shim normalizes vendor-specific APIs; the compiler does not call arbitrary
vendor symbols with guessed signatures. A small header shared by all adapters:

```c
#include <stdint.h>
typedef struct bhffi_context bhffi_context;
typedef struct { const uint8_t *data; uint64_t length; } bhffi_view;
typedef struct { uint8_t *data; uint64_t length; } bhffi_owned;
int32_t bhffi_compression_v1_compress(
    bhffi_context *context, bhffi_view input, int32_t format,
    int32_t level, bhffi_owned *output);
int32_t bhffi_compression_v1_decompress(
    bhffi_context *context, bhffi_view input, int32_t format,
    uint64_t limit, bhffi_owned *output);
void bhffi_compression_v1_bytes_release(bhffi_context *context, bhffi_owned *output);
```

These POD records are the controlled wire ABI, not permission for arbitrary C
struct layouts. Check field offsets, sizes, alignment and calling convention
with compiler assertions and executable probes on each platform.
Every call carries a hidden context for one Bhash runtime: budgets, handle kinds
and ownership never cross unrelated runtime contexts. Native context leases outlive
all buffers/control blocks referring to them, including deferred cleanup after
shutdown. Stopping a runtime rejects new calls; it must not free context storage
still referenced by a managed cleanup record. Modules remain loaded for v1's
process lifetime; hot unloading is deferred. The context is never a Bhash pointer.

| Bhash value | Initial wire rule |
| --- | --- |
| `पूर्णांक` | Signed `int64_t`; narrower C parameters require checked range conversion. |
| `लघुपूर्णांक` / `बाइट` | `int32_t` / `uint8_t`. |
| `तार्किक` | `uint8_t`, exactly 0 or 1; never assume host BOOL layout. |
| `दशमलव` | Explicit binary64 `double`; the binding specifies any finite-value restriction. |
| Enum | Validated numeric discriminator of a declared width. |
| `बाइटबफ़र` | Pointer plus byte count to a detached copy. |
| `पाठ` | Strict, length-delimited UTF-8 copy; embedded NUL is data. |
| Resource | Runtime-owned typed token; no public numeric/pointer conversion. |

Unrestricted `uint64_t` values, C `long`, `size_t`, pointers, strings with implicit
termination, arbitrary Bhash objects/records/unions and function pointers are not
public wire types in v1. Internal lengths are bounded unsigned counts with checked
conversion to the host allocator. A vendor NUL-terminated API needs an explicit
shim policy, including rejection of embedded NUL where required.

## 6. Ownership and resource lifetime

| Value | Owner and lifetime |
| --- | --- |
| Input view | Caller owns a detached copy until the native call returns; callee cannot retain it. |
| Output buffer | Shim owns it until the adapter copies it into Bhash and calls that shim's release function. |
| Vendor allocation | Released by its declared vendor/shim allocator pair, never an assumed host `free`. |
| Resource handle | Shared control block owns the foreign resource; wrappers are aliases, not independent owners. |
| Borrowed resource | Permitted only as a call argument; returning/storing a borrowed handle is deferred. |

Output records start empty. Failure leaves no published partial output. Release
accepts empty output and clears the record. Allocate/copy failure, an exception,
or an early exit releases detached memory before raising a Bhash error.

Resources use OPEN → IN_USE → OPEN or CLOSED; close is idempotent and invalidates
all aliases. A second simultaneous use, use after close, wrong resource kind or
wrong execution thread fails before calling C. Public methods cannot expose raw
tokens. Reserve a control block before accepting ownership; if wrapping fails,
release the foreign resource immediately. Failed construction must not leak.
There is one managed wrapper identity per ownership transfer; ordinary assignments
alias that wrapper. Fallback release occurs only after its last reachable alias is
gone. Do not finalize one of several independently created wrappers while another
still represents the same live ownership; duplicate ownership registration is rejected.

Deterministic close remains the normal rule for scarce resources. Fallback cleanup
is new infrastructure: native GC, JVM Cleaner and CLR SafeHandle paths enqueue a
native-only cleanup record; they do not execute Bhash code. Release work drains on
the owning execution thread, outside collection. The queue reserves capacity when
a resource is created, so discovery of garbage does not require allocation.
Cleanup must neither resurrect the wrapper nor keep it strongly reachable.
Once-only release is arbitrated by the control block across explicit close, GC
and shutdown. Shutdown drains the queue and closes remaining registrations.
Fallback failures become bounded diagnostics; explicit-close errors follow the
existing resource-cleanup contract. Tests must verify that contract, not invent
new suppression rules in this milestone.

## 7. GC safety and boundary unwinding

1. Evaluate receiver and arguments once, left to right, with existing roots.
2. Validate types, null policy, lengths and limits before native execution.
3. Reserve external-memory capacity and create detached input copies. Keep
   managed roots alive through result construction; never keep interior addresses.
4. Enter the C shim. It can allocate native memory but cannot call Bhash, unwind
   a C++ exception into Bhash, or trigger a Bhash exception through active C frames.
5. Return status and native data. Root each managed result before further managed
   allocation. Copy output, construct Result/error values, and release native data
   on every path through a generated boundary-cleanup guard.
6. Leave the boundary with balanced roots, resource leases and memory reservations.

Input/output aliasing across the boundary is forbidden. Rooted managed values may
move in a future collector because C holds only detached copies and control blocks.
No JNI critical-array region spans compression or allocation. No JVM local reference
or .NET movable address is retained by C. Host-specific keepalive mechanisms must
protect wrappers until their boundary lease ends.

C defects can still corrupt or terminate the hosting process. These contracts
prevent mistakes in generated marshaling; they are **not a sandbox or a proof that
a third-party library is memory-safe**. No promise to recover from native faults.

## 8. Execution, async and cancellation

All initial calls use `owner-sync`: synchronous on the owning Bhash execution
thread, with no foreign reentry. Stateful resources are thread-affine. Internal
C-to-C allocator callbacks are allowed; foreign-to-Bhash callbacks are not.

Compression is synchronous CPU work. Calling it from an async function does not
make it parallel or allow other cooperative tasks to run during the call. Milestone
1 adds no cancellation parameter, worker dispatch or interruptible C call. It must
not advertise hard execution deadlines based on buffer limits.

A later detached-worker profile may reuse [async I/O](async-io.md): copied arguments,
native-only worker state, owner-thread result construction, bounded queues and
cancellation that discards/drains results without freeing in-flight buffers. It
requires separate approval; arbitrary blocking libraries cannot be declared safe
for cooperative execution merely by a manifest flag.

## 9. Proposed compression API

These are proposed signatures, **not currently available APIs**:

| Public member | Result / contract |
| --- | --- |
| `संपीड़न.संपीड़ितकरो(मूल: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, स्तर: पूर्णांक)` | `परिणाम<बाइटबफ़र, संपीड़नदोष>`; level 0–9, no implicit default. |
| `संपीड़न.विसंपीड़ितकरो(मूल: बाइटबफ़र, प्रारूप: संपीड़नप्रारूप, अधिकतम: पूर्णांक)` | Same Result type; caller must supply the maximum output bytes. |
| `संपीड़नप्रारूप.जेडलिब` / `.गज़िप` | Explicit format; no auto-detection. |
| `अभिलेख संपीड़नदोष(कोड: संपीड़नदोषकोड, संदेश: पाठ)` | Stable enum category and Hindi explanation; callers branch on code, not message. |

Proposed usage, intentionally not a runnable fixture until the API is implemented:

```bhash
// bhash:null-safety=strict
आयात मानक.पाठ्य;
आयात मानक.संपीड़न;
आयात मानक.संपीड़नप्रारूप;

फलन मुख्य() -> शून्य {
    स्थिर मूल = पाठ्य.यूटीएफ८("नमस्ते, भारत! 😀");
    मिलान (संपीड़न.संपीड़ितकरो(मूल, संपीड़नप्रारूप.गज़िप, ६)) {
        विकल्प मानक.परिणाम.सफल(स्थिर दबा): {
            मिलान (संपीड़न.विसंपीड़ितकरो(दबा, संपीड़नप्रारूप.गज़िप, १०४८५७६)) {
                विकल्प मानक.परिणाम.सफल(स्थिर खुला): {
                    लिखो(पाठ्य.यूटीएफ८पाठ(खुला));
                }
                विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
            }
        }
        विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.संदेश); }
    }
}
```

Initially bundle zlib, with **1.4 as the candidate source version**, verified on
the official site on this document's date. Freeze its archive hash, license,
build recipe and advisories before implementation/release; never resolve `latest`
during an end-user build. This is a proposed dependency, not a claim that it has
already been audited or bundled. [Official release information](https://zlib.net/)

Use only the required in-memory codec sources; exclude minizip, filesystem `gz*`
entry points and unused contribution code. The adapter initializes and ends codec
state internally. This first API needs no user-visible compression session handle.
A separate small resource-binding fixture proves generic handle and close behavior.

## 10. Compression format and failure rules

The zlib and gzip formats have different wrappers around DEFLATE; use an explicit
format selection. [zlib manual](https://zlib.net/manual.html)

- Accept exactly one complete zlib stream or one gzip member. Reject trailing data,
  including another valid member. RFC 1952 permits multiple members; v1 deliberately
  exposes a **single-member profile**, not a complete gzip-file API.
- Validate framing and checksums; reject truncated/corrupt data. Preset dictionaries
  are unsupported and produce a distinct failure. Never return partial decoded data.
- Emit gzip without filename/comment, with modification time 0 and OS identifier
  255. Cap optional input-header processing by the input limit. An advertised size
  or gzip ISIZE is not permission to allocate; count actual produced output.
- Empty source bytes can be compressed. Empty compressed bytes are malformed input.
  Binary data is not automatically interpreted as text; callers use explicit UTF-8 APIs.
- Freeze window/memory/strategy settings and test reproducibility for the pinned
  profile. Portable wire compatibility and matching decoded bytes are the public
  contract; identical compressed bytes across future library versions are not.

See [RFC 1950](https://www.rfc-editor.org/rfc/rfc1950),
[RFC 1951](https://www.rfc-editor.org/rfc/rfc1951) and
[RFC 1952](https://www.rfc-editor.org/rfc/rfc1952).

Proposed format discriminators are `जेडलिब = ०` and `गज़िप = १`. Operational
failures return `असफल` with the following explicit public codes:

| `संपीड़नदोषकोड` | Wire code | Meaning |
| --- | --- | --- |
| `अवैधतर्क` | 1 | Invalid argument, enum, level, output cap or legacy null. |
| `आगतसीमा` | 2 | Input exceeds the supported bound. |
| `निर्गतसीमा` | 3 | Actual produced output exceeds the selected limit. |
| `दूषितडेटा` | 4 | Malformed, truncated, wrong-format, checksum-failing or trailing data. |
| `शब्दकोशअसमर्थित` | 5 | A preset dictionary is required. |
| `संसाधनसीमा` | 6 | Native allocation or external-memory reservation fails. |

Wire status 0 denotes success; unknown/internal statuses become a structured
interop exception, not a misleading malformed-data result. Freeze this mapping in
the contract fingerprint and test every category on all three targets.
Library load/ABI/platform failures are setup diagnostics or a structured Bhash
interop exception before the call. Managed-heap exhaustion retains the existing
host/runtime behavior; do not promise that every out-of-memory event becomes Result.
Capture vendor status/errno on the calling thread before any other native call.

## 11. Proposed limits and abuse resistance

| Limit | Initial recommendation |
| --- | --- |
| Compression/decompression input | 8 MiB per invocation. |
| Compression output | At most 16 MiB. |
| Decompression output | Explicit caller limit, 1 byte–16 MiB; valid empty output still succeeds. |
| Foreign working memory | 32 MiB per invocation, 64 MiB aggregate per Bhash runtime. |
| Open foreign resources | 256 per runtime, with reserved cleanup records. |

Use checked arithmetic before allocation, bounded growth and a budget-aware zlib
allocator. Release partial codec state even when allocator injection fails midway.
The external budget includes bridge buffers and codec workspace; managed copies,
host runtimes, static code and unrelated libraries consume additional memory.
It is not an RSS guarantee. Return an error rather than silently raising limits.
ZIP extraction, archive paths, raw DEFLATE, HTTP automatic content decoding and
public streaming are separate milestones; no archive traversal surface is added.

## 12. Cross-target contract and availability

| Host/target | Milestone 1 |
| --- | --- |
| Native Linux x64, glibc 2.35+ | Static, private adapter and provider linked into the executable. |
| Native Windows x64, UCRT | Same contract, static artifacts compatible with the managed LLVM toolchain. |
| Native macOS 14+ ARM64 | Same contract with Apple platform linkage rules. |
| JVM on those three host tuples | Generated Java declarations plus a prebuilt JNI shared bridge. Keep Java 17 support; do not require Java's newer FFM API. |
| Desktop .NET on those three host tuples | Generated explicit P/Invoke marshaling into a prebuilt shared bridge; no default bool/string conversion assumptions. |
| Jupyter | JVM bridge from the installed wheel, preserving kernelspec driver and compiler options. |
| Browser WebAssembly / Playground | Reachable calls fail capability validation before publication/execution. No native downloads or host loading. |
| Android / other desktop architectures | Clear unsupported-binding diagnostic; future NDK artifacts, not accidental host-library linkage. |

Use the same pinned codec and error mapping in JVM/CLR/native. Do not substitute
host `java.util.zip` or .NET compression classes in this slice. Even managed outputs
that use this binding now have an OS/architecture dependency; retain ordinary
portable behavior for outputs that do not reference foreign bindings.

JNI thread-local state must not cross threads ([JNI design](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/design.html)).
CLR adapters use explicit marshaling and safe handle infrastructure
([.NET native interop guidance](https://learn.microsoft.com/en-us/dotnet/standard/native-interop/best-practices)).

## 13. Distribution, reproducibility and trust

Publish binding bundles as permanent GitHub Release assets, not binary blobs in
Git. Record the binding contract hash, FFI ABI, codec source/hash, compiler/runtime
compatibility, target tuple, toolchain recipe, transitive dependencies and notices.
Pin artifacts in `bhash.lock`; ordinary compile/run verifies local assets and
does not resolve versions or silently download replacements. Explicit dependency
restore is the network operation. Bundled compression artifacts ship with public
compiler/editor/kernel packages or their explicitly managed verified asset set.
Propose `lockVersion: 2` when a graph contains FFI artifacts; retain version 1 for
existing source-only graphs. An explicit lock update performs migration atomically.
Older compilers must reject the new lock format rather than ignore binary entries.
Keep the previous lock on failure, and include artifact identity/digests in the
root graph's integrity checks instead of trusting a dependency's private lockfile.

Binding producers compile C adapters; consumers of complete bundles do not install
zlib headers/development packages. Native builds still need their existing native
toolchain. A custom library with no prebuilt tuple needs an explicit producer build,
not execution of package scripts during editor analysis or restore.

Native distribution records the statically linked provider and notices. JVM/CLR
packages include the correct private shared bridge; zlib is linked privately into
it. Use private symbol namespaces, hide vendor exports, and reject incompatible
duplicate provider identities in one dependency graph.
This feature introduces no JVM/.NET dependency into native applications; their
other existing operating-system and provider dependencies remain unchanged.

JVM extraction uses a private content-addressed cache, hashes before loading,
atomic installation and locking. CLR resolves an absolute package-local library.
Never search the working directory, PATH or a bare user-controlled library name.
Detect wrong architecture, ABI/contract mismatch and tampering before entry.
Do not replace a loaded library in-place; upgrades use a new identity/process.

Checksums ensure integrity relative to the trusted lock/catalog; they do not make
an untrusted publisher safe. Keep explicit workspace trust and existing CI release
permissions. A native binding can exercise all process privileges regardless of
its declared capability tags. Never enable third-party bindings in the public
Playground as a consequence of installing this desktop feature.

Retain the [zlib license](https://zlib.net/zlib_license.html), identify any source
changes, and ship notices/source provenance. Do not infer a general redistribution
right for other libraries; their producer supplies the appropriate license record.

## 14. Tooling and documentation

Propose binding verification/doctor and producer-pack commands; finalize command
spelling in the implementation plan. Doctor reports the exact target, binding and
missing artifact without executing application functions. Runtime handshake and
build-time inspection validate the ABI separately. Diagnostics identify the Bhash
call site and binding identity; new diagnostic numbers must be allocated centrally.

Provide Hindi end-user documentation, binding-author guidance, an API reference,
compression round-trip and bounded file-compression examples, and a Jupyter example.
Update the book only when implemented behavior exists. Clearly distinguish building
a binding from consuming one, and a gzip stream from a ZIP archive.

## 15. Implementation sequence and release gates

| Phase | Deliverable and evidence |
| --- | --- |
| A. Contract | Schema/ABI validator, shared typed declarations, unsupported-target diagnostics; invalid manifests rejected consistently. |
| B. Native foundation | Linux first, then Windows/macOS; generic scalar/UTF-8/buffer/resource fixture and generated cleanup. No compression-only bypass. |
| C. Compression | Pinned codec, Result API, caps and malformed-input handling; forced-GC and allocation-failure tests. |
| D. Managed adapters | JNI and CLR use the same contract/codec; real JVM/CLR/native comparisons on supported tuples. |
| E. Distribution | Verified release assets, CLI/VS Code/Jupyter consumers, clean-host checks and Hindi reference/tutorials. |

Phase B can be reviewed independently. Public milestone publication waits for A–E;
an internal native prototype is not advertised as portable library interoperability.
No existing compiler ABI is assumed to remain unchanged: audit additions and bump
the native runtime ABI if runtime layouts/hooks require it, with rebuild guidance.

## 16. Required validation

- Manifest rejection: conflicting names, aliases/Unicode equivalence, invalid
  ownership, type widths, encodings, release functions and unsupported targets.
- Cross-target results: empty/binary/Hindi/emoji/NUL inputs; all levels and formats;
  cross-decode produced files with independent host tools and externally generated
  fixtures. Compare decoded bytes, categories and limits, not localized host messages.
- Corruption: invalid header, wrong format, truncation, checksum failure, dictionary,
  multiple members, trailing garbage, decompression bombs and exact limit boundaries.
- Lifetime: repeated calls, alias close/double close, stale/wrong-kind tokens,
  exception during marshaling, partially initialized codec and failure after C returns.
- Forced GC: collect before argument copying, between managed allocations, before
  result construction, with resource wrapper as sole reference, and after close.
  Assert balanced roots, once-only release and external-byte counts returning to baseline.
- Inject failure at each native allocation and managed result-copy seam; verify
  recovery where the host supports recoverable failure, without changing fatal OOM rules.
- Stress fallback cleanup, shutdown and wrong-thread/reentry rejection. Native
  sanitizer runs cover buffers/control blocks; tests cannot prove arbitrary C safe.
- Distribution: no zlib development install on clean Windows/Linux; Apple Silicon
  in the existing weekly workflow plus a manual run before the first release.
  Standalone JAR, VSIX and installed Jupyter wheel use the same verified artifacts.
- Browser/Android denied-capability tests preserve isolation; editor analysis never
  loads native code or fetches artifacts. Documentation-only CI stays scoped to docs.

## 17. Decisions requested before implementation

Approve or revise: (1) manifest/shim authoring without grammar changes; (2) copied
boundary values and owned handles; (3) owner-thread synchronous calls and deferred
callbacks/async; (4) common pinned zlib on three desktop targets; (5) single-member
gzip and explicit output caps; (6) proposed memory/resource limits; (7) native static
versus managed private-shared packaging; (8) browser/Android deferral; and (9) staged
delivery with a complete desktop release gate.

**Review boundary:** stop here. No compiler/runtime changes, native dependency
installation, library builds, deployment or package publication accompany this proposal.
