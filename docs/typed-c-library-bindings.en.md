# Typed C-library bindings

[हिन्दी संस्करण](typed-c-library-bindings.md)

This guide is for binding authors. The [approved Hindi design](typed-c-interop-design.md)
and [English design](typed-c-interop-design.en.md) describe the language and runtime
contract. Application users should start with the [Hindi compression tutorial](../tutorial/compression/README.md).

## Contract and project

The `bhash.ffi.json` manifest uses schema 1 and ABI 1 to record the namespace,
version, functions, fixed wire types, ownership, errors and artifact hashes for
each host. Register it with `ffi=bhash.ffi.json` in the project's `bhash.project`.
The tested example consists of a [counter contract](../src/test/ffi/counter/bhash.ffi.json),
[C implementation](../src/test/ffi/counter/fixture.c) and
[Bhash caller](../src/test/ffi/counter/program.bhash). This library is independent
of compression; the compiler does not special-case its name.

```sh
java -jar bhash-compiler.jar deps lock path/to/project
java -jar bhash-compiler.jar deps restore path/to/project
java -jar bhash-compiler.jar deps verify path/to/project --offline
java -jar bhash-compiler.jar ffi prepare path/to/bhash.ffi.json -o generated-binding
```

Lockfile format 2 identifies both the contract and the artifact inventory. The
older format 1 remains supported for source-only packages. Intentionally run
`deps lock` when source, signatures or artifacts change. Editor lookup and
compilation do not download dependencies. An explicit restore retrieves a
declared HTTPS `url` and accepts the artifact into the cache only after its
SHA-256 matches. Offline restore and verification do not use the network.
`ffi verify manifest` checks the static and shared host artifacts.

Each artifact records `target`, `kind` (`static` or `shared`), `path`, `sha256`,
`sourceSha256`, `license` and an optional HTTPS `url`. An existing local file must
match its declared hash; an invalid file is not silently replaced. Changing a URL
or accepting a new checksum is a dependency change that requires review. The
compiler constructs typed Bhash declarations from manifest information; it does
not parse empty application functions and pretend they are external APIs.

## Ownership and limits

Parameters use signed fixed-width integers, byte, boolean, 64-bit double, UTF-8,
bytes or opaque owned resources. Copy-borrow buffers are valid only for the
duration of a call. UTF-8 supports embedded NUL through explicit lengths. No
Bhash heap pointer, raw pointer, callback, varargs, C++ ABI or arbitrary struct is
exposed. Enum values are checked against their declarations; a boolean must be
exactly 0 or 1. The bounded `length` wire type ranges from 0 to 16 MiB, rather than
being a general unsigned 64-bit value.

The `bhffi_v1_call_begin/end` gateway manages detached native buffers and limits.
C implementations must use the context allocator. Owned output is freed through
its declared release function, including when conversion fails. A resource
constructor reserves a cleanup record before allocating the vendor resource.
The generated gateway transfers ownership exactly once; the shim must not call
`resource_transfer` itself. Returning an already transferred handle or a borrowed
input buffer as owned output is a contract error. The negative-test fixture's
`दुबारा` method deliberately violates this rule; it is not a normal binding example.
Resource results use native tokens rather than exposing raw pointers. Explicit close is idempotent;
subsequent use through aliases, boxing or collections fails for that same resource.

Garbage collection keeps managed arguments alive until the result is fully
constructed. A finalizer only queues a previously reserved native cleanup record;
the owner thread runs the vendor destructor. Cross-runtime/thread use, reentry
and use of a busy resource are rejected. Native modules stay loaded for the
process lifetime. A shared context must match both the ABI and the runtime
implementation hash. Status 7 and unknown statuses indicate contract failures,
not business errors.

## Provider build

For the compression provider, first build the compiler JAR, then prepare the
generated sources. The build script verifies the downloaded zlib 1.4 archive
against its pinned SHA-256 before extraction. Provider authors need a C17
compiler, a deterministic archiver and JDK headers. End users of the packaged
JVM/.NET application do not need these build tools.

```sh
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar ffi prepare --builtin compression -o target/compression-binding
python scripts/build_ffi.py --archive zlib-1.4.tar.gz --bindings target/compression-binding --java-home /path/to/jdk --shared --test --catalog target/ffi-catalog
mvn -DskipTests package
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar ffi doctor compression
```

On Windows, pass `--cc` and `--ar` for the llvm-mingw compiler and archiver. The
Linux provider baseline is glibc 2.35, so build it in an Ubuntu 22.04-compatible
environment. Build the macOS provider on Apple Silicon. Assemble a universal
compiler distribution only after combining all three host catalogs. Keep
`provider.json`, `provenance.json`, source and binary hashes, and the zlib license
with the provider. Do not publish builds with `BHFFI_TESTING` or `BHFFI_GC_STRESS`
enabled.

Prefix the shim's exported C symbols and the vendor's internal symbols with
your package/version namespace. Hidden visibility alone is insufficient for
static archives. The compression provider gives linked zlib symbols a versioned
prefix to prevent accidental linking to a different system zlib. If two bindings
declare colliding shim symbols, the native compiler rejects them before linking.

In a distribution, the catalog and private artifacts are JAR resources. A native
executable links the static provider; JVM/CLR applications carry a private shared
provider. A same-named DLL is not selected from the system PATH. Do not commit
native binaries to Git; supply reviewed permanent release assets and checksums.
Module unloading and hot reload are not supported. Restart the runtime after
installing a new provider. Android and browser targets are currently unsupported.

## Validation

`ForeignManifestTest`, `ForeignArtifactsTest`, `ForeignCommandsTest`,
`ForeignCGeneratorTest`, `ForeignJvmTest`, `ForeignNativeTest` and
`ForeignResourceTest` cover declarations, checksums, cross-target Results and
opaque resources. C harnesses check memory limits, allocation failure at every
allocation point, corrupt streams, exact output caps and cleanup queues.
`scripts/ffi_wire_test.py` checks compressed bytes with an independent codec.
Host-specific native validation is required in addition to the full Jupyter and
browser tests; a local Windows result is not evidence of Linux/macOS success.
