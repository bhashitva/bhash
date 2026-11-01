# Prebuilt browser host for the playground

The service prepares `BhashBrowserHost` once when building its container image.
New submissions still pass through the Bhash frontend in a private Java child
process, but Roslyn compiles only the generated `Program.cs`. There is no
per-submission project evaluation, package restore, runtime compilation,
trimming, WebCIL conversion or complete browser publishing.

The administrator-only preparation command is:

```
java -cp target/bhash-compiler-0.1.0-SNAPSHOT.jar dev.bhash.backend.wasm.PrebuiltBrowserHost /absolute/browser-host
```

Set `BHASH_BROWSER_HOST` to that directory for the playground service. The
container does this automatically. A standalone `wasm-browser` compilation
without this setting retains the original publishing path. With the setting,
standalone output copies the prepared assets and remains self-contained.
`BHASH_BROWSER_SHARED=true` is an internal service setting: it emits only the
program and bootstrap files, pointing at the execution origin's
`/browser-host/<content-hash>/` route. Do not use shared mode for a standalone
folder without that service route.

## Compatibility and integrity

Preparation records the host project and runtime/standard-library source fingerprint, exact
reference assemblies, SDK compiler location and compiler digest. A manifest
hashes every prepared file. The service validates this bundle at startup, and
compiler children validate it before use. Missing, changed or incompatible
bundles fail explicitly; requests never rebuild or repair a shared host.
Host and program output directories have distinct markers; program output may
not replace or overlap the prepared host, including through directory aliases.
Rebuild the bundle with the compiler and SDK image when either changes.
This manifest detects accidental modifications; the immutable deployment image
is the trust boundary, not a signature supplied by an untrusted client.

Only `web/` is exposed by HTTP, and only on the execution origin. Reference
assemblies, SDK paths and metadata remain private. Versioned runtime assets
receive immutable cache headers; generated program URLs retain the existing
bounded job lifetime. The compiler/runtime identity participates in job keys.

## Isolation, diagnostics and cancellation

Every submission has a private build directory and bounded child process. The
only compilation input is generated, checked C# with fixed compiler arguments;
there is no submitted SDK project, package reference, assembly upload or server
execution of the generated program. Existing origin checks, content limits,
queue limits, diagnostics and output protections apply.

Each Run creates a new cross-origin iframe and worker, hence a new .NET runtime
and static state. The host accepts one program invocation and refuses reuse.
It loads the generated managed DLL in the browser and calls its checked entry
point. `InternalsVisibleTo("BhashProgram")` keeps the existing runtime API
internal while allowing that generated assembly to use it.

Stop terminates the browser worker, including while initialization is pending.
It does not cancel a server build shared with another request; an abandoned
build may finish and enter the existing source cache. Compiler deadlines and
service shutdown still terminate the compiler process tree. Input/output limits,
execution deadlines and browser restrictions remain in effect. Runtime failure
messages still use the existing Bhash runtime; frontend diagnostics are produced
before invoking Roslyn, including statement-mode source-line adjustment.

## Trimming and download size

The host uses the .NET browser interpreter, not AOT, and disables managed-code
trimming. A host published before a program exists cannot safely infer all of
that program's library and generic uses. Full browser semantic tests cover the
same examples through both compilation paths. Keeping the needed library code
can increase the first runtime download; shared versioned URLs allow later
programs to reuse it. Measure startup and transfer size alongside compile time
before deciding whether a narrower, explicitly rooted library is worthwhile.

## Profiling

`python3 scripts/profile_browser_publish.py` records three original publishes,
one host preparation and three program-only builds on the same machine. SDK
performance summaries and phase timings are saved under
`target/browser-publish-profile/`. Target/task summary durations overlap; do
not add nested MSBuild timings as independent wall-clock phases.

`node scripts/playground_performance.cjs` repeats the ten original Hello/loop
cases for both paths through the real editor/API/iframe/worker pipeline. It
checks output, records Run-to-output and ready/running events, captures server
queue/build timings and saves browser-reported resource timings and response
sizes. CI uses headless Chromium
and loopback HTTP with no compression or throttling, so its absolute numbers
are not interchangeable with the public Edge/WAN baseline. Raw results are
uploaded with a generated comparison report as CI artifacts. Run
`python3 scripts/analyze_playground_performance.py` to reproduce that report.
Production measurements require a tested preview or
a separately approved deployment.
