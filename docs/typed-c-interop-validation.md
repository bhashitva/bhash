# Typed C interoperability: implementation and validation record

The approved design is preserved in [English](typed-c-interop-design.en.md) and
[Hindi](typed-c-interop-design.md). This record distinguishes local evidence from
the release gates. No new public provider/compiler release is claimed here.

## Implemented contracts

- Strict `bhash.ffi.json`, typed declarations, generic core `ForeignCall`, checked
  gateways and opaque resources. The unrelated counter fixture verifies this is
  not a compression-only compiler intrinsic.
- Copied buffers/text, integer/range checks, Result mapping, bounded allocation,
  owner-thread enforcement, alias invalidation and deferred native-only cleanup.
  Every generated gateway enforces call budgets and rejects nested entry; codecs
  can open an inner operation without resetting the outer call budget.
- Owned handles are adopted once, with context/kind/control-record validation.
  Duplicate ownership returns on success/error paths are rejected without closing
  the original wrapper. Borrowed-input/owned-output buffer aliasing is rejected
  before conversion or cleanup; borrowed memory is never vendor-released as output.
- Contract fingerprints exclude artifact descriptors to avoid hash-derived
  symbols containing their own binary hash. Lock version 2 independently pins
  the complete artifact inventory. Source-only projects retain version 1.
- Bundled compression uses zlib 1.4, SHA-256
  `02dd159783677a749c04adbdd02b55b8a2bcc7e054f51b76b549199a916ad584`.
  zlib/gzip have one stream/member, exact consumption, deterministic gzip headers,
  strict errors and bounded output. Independent codecs validate the wire data.
- Vendor symbols have a versioned private namespace even in static archives.
  A decoy incompatible `deflate`/`inflate` implementation in the C harness cannot
  replace the pinned codec. Native link plans reject conflicting declared shims.
- Java 17 JNI and explicit .NET interop use the same generated contract/gateway
  and codec. JVM adapters have their own class loader; the notebook parent shares
  only the standalone native-module runtime, preserving cell isolation.
- Provider catalogs bind the contract and exact runtime source identity. Shared
  contexts additionally compare a compiled-layout fingerprint (sizes, alignments
  and offsets), so equal sources compiled with incompatible packing cannot share
  state. Allocation and context frees use the originating module's callbacks.
- Test switches do not change the private context layout. Production libraries
  are built separately from allocation-failure/GC-stress probes.
- CLI preparation is offline and emits source/provenance inputs without loading
  code. Explicit restore verifies pinned HTTPS bytes; compilation and analysis
  never fetch replacements. Editor definitions use read-only Bhash declarations
  with actual coordinates, and external APIs cannot be renamed as application code.

## Native ABI audit

The existing Bhash public object descriptor, generated object layout and exported
library ABI remain version 18. FFI adds a private allocation-header field only in
desktop executables compiled with `BHASH_FFI`; no prebuilt runtime object is mixed
with that header. Android/embedded FFI is rejected. The runtime content identity
now includes `bhash_ffi.inc` and the FFI core/header. The separate external ABI is
version 1 with checked wire layouts and the private-layout handshake described
above. Moving/generational collectors remain possible because C never retains a
Bhash heap address.

## Local Windows evidence

The local host uses Windows x64, LLVM-MinGW 18, JDK 26 compiling Java-17-compatible
classes, and .NET 10. A JDK-26 run is not a substitute for the Java-17 CI gate.

| Area | Evidence |
| --- | --- |
| Native codec/core | C harnesses pass roundtrips, levels, caps, corruption, dictionaries, allocation failures, owner threads, resource quotas, queues and shutdown leases |
| JNI probe | Copied buffers, independent Java decoding and forced GC pass |
| Real Bhash | JVM/.NET/native compression Result programs and unrelated resource package pass under forced GC, including byte/boolean/double conversions, boxed/list aliases, duplicate ownership rejection and exception unwinding; legacy null format returns the same invalid-argument Result |
| Metadata/editor | Manifest, artifact, CLI, capability and editor tests pass; browser calls fail before runtime publication |
| Reproducibility | Static/shared Windows provider hashes match across two separate build directories |
| Documentation | Hindi reference generation and book-link checks pass |
| Notebook bridge | The actual current Python worker bridge passes compression under forced GC across repeated cell loaders; every cell in the new Hindi compression notebook passes |
| Hindi examples | Roundtrip, typed errors and real bounded-file gzip output pass on JVM, .NET and native |

The earlier Jupyter attempt used an old global installation and was stopped;
that attempt is not counted as validation. A fresh isolated current-source run
identified a local host restriction: Python blocks in `socket.socketpair()` while
Jupyter creates its Windows event loop, before kernel startup. The pipe-based
worker tests above pass, but do not replace the full installed Jupyter client gate
in GitHub Actions. All remote operating-system gates remain required.

## Automated gates and distribution

`typed-c-interop.yml` runs Linux glibc-2.35 and Windows builds, independent wire
checks, genuine JVM/.NET/native Bhash programs, forced-GC resource tests and the
full real Jupyter client test. Linux consumers additionally run in JRE/.NET
runtime-only containers without C development headers/tools. Absolute-runtime
consumer launches with an empty PATH are also checked on each host; this alone
does not claim a clean Windows installation.

Apple Silicon remains in `native-macos.yml`'s weekly/manual workflow. It is not
added as a per-PR job. The manual `ffi-provider-release.yml` runs all three hosts,
requires matching contract/runtime catalogs, verifies all six artifacts and
their provenance, and creates a reproducible provider ZIP with a checksum. Its
publication option defaults to false. Normal public packages must use that full
catalog; a developer build without assets reports an installation diagnostic.

Pending release gates: successful remote Linux/Windows/Apple Silicon results,
installed Jupyter validation, complete provider bundle assembly/publication, and
verification of the final compiler/VSIX/kernel packages using that bundle.
