# Playground performance baseline — 31 October 2026

## Finding and recommended next step

**The build pipeline is the main delay for changed source.** Three uncached
Hello runs took **31.75–46.03 seconds** from Run to visible output, with a median
of **31.85 seconds**. Three repeats that reused the server compilation cache took
**0.65–1.10 seconds**, median **1.01 seconds**. In the two subsequent uncached
Hello samples, the browser received a ready compilation after about 30.3 seconds.

Conservative polling-derived bounds place compilation itself at **at least 88%**
of the uncached Hello latency. About **95%** of the total elapsed before the
browser received the ready response. These are different measurements: the latter
also includes queueing, polling and HTTP overhead.

**First profile the build subprocess phases and prototype a reusable browser host
that compiles only the changing user program.** The measurements justify reducing
the work performed for each edit. They do not identify how much of the build time
belongs to Java startup, Bhash checking, SDK startup, C# compilation, packaging or
artifact copying; those need server-side timings before an implementation choice.

No deployed code, compiler behavior, server settings or cache policy was changed.
Only temporary client-side observation was used. The probe was removed, the page
was reloaded and the original Hello draft was restored after collection.

## What was measured

- Live service: `https://play.bhashitva.org/`, executing artifacts from
  `https://run.bhashitva.org/`.
- Client: Windows x64, Microsoft Edge 156.0.0.0; DevTools Network recording;
  no artificial network throttling; Disable cache remained unchecked.
- Report date uses America/New_York; raw exported wall-clock timestamps use UTC.
- Existing browser extensions, including Norton, remained enabled. Extension
  errors were visible in DevTools. This is a measurement of that real client
  environment, not an extension-free browser benchmark.
- Ten successful runs: three unique Hello sources, three cached Hello repeats,
  one unique arithmetic-loop source, and three cached loop repeats.
- Unique source comments forced distinct server compilation cache keys. Uncached
  means a **compilation-cache miss**, not a proven cold VPS, JVM or SDK process.
  Distinct artifact URLs also caused fresh client transfers, verified in HAR.
- The first run used the physical Run button. Later runs used the same button's
  click handler through DevTools or the existing Ctrl+Enter shortcut. The probe
  timestamped invocation inside the page, excluding automation/tool waiting time.
- The loop prints `आरंभ`, performs 2,000,000 integer iterations, then prints its
  checksum. The expected checksum **1007993832** was calculated independently and
  matched the displayed Devanagari output.
- All runs used Statements/scratch mode. Full-program mode, mobile devices,
  slower networks and concurrent load were not separately sampled.

The deployed commit and live VPS utilization were not inspected. Repository
source supports the architectural explanations below, while the timings come
from the live browser protocol and HAR evidence.

## End-to-end results

All values below are seconds. Service ready includes queue/build, API and polling.
Frame bootstrap and worker/runtime startup are separate consecutive intervals;
both can contain asset loading. Execution/output includes message/UI overhead.

| Run | Server cache | Run → output | Run → service ready | Frame bootstrap | Worker/runtime load | Execution/output |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Hello, first unique source | Miss | 46.030 | 44.161 | 0.822 | 1.014 | 0.034 |
| Hello, repeat 1 | Hit | 1.007 | 0.111 | 0.296 | 0.559 | 0.042 |
| Hello, repeat 2 | Hit | 1.103 | 0.525 | 0.163 | 0.388 | 0.028 |
| Hello, repeat 3 | Hit | 0.646 | 0.067 | 0.183 | 0.370 | 0.026 |
| Hello, second unique source | Miss | 31.850 | 30.341 | 0.386 | 1.098 | 0.024 |
| Hello, third unique source | Miss | 31.754 | 30.222 | 0.382 | 1.120 | 0.029 |
| Loop, unique source | Miss | 31.754 | 30.332 | 0.353 | 0.750 | 0.320 |
| Loop, repeat 1 | Hit | 1.194 | 0.081 | 0.276 | 0.463 | 0.375 |
| Loop, repeat 2 | Hit | 0.886 | 0.068 | 0.172 | 0.372 | 0.274 |
| Loop, repeat 3 | Hit | 1.282 | 0.450 | 0.180 | 0.379 | 0.273 |

The first uncached sample was slower than the later three misses. Its cause is
unknown; server load and process/package-cache state were not observed. It is
retained rather than discarded. These small samples are a baseline, not a
population p50/p95 claim.

## Requested stage breakdown

Representative sample: **third uncached Hello**, 31.754 seconds total.

| Stage | Measurement | Interpretation |
| --- | ---: | --- |
| Queueing | **0–1.125 s** | Conservative interval inferred between queued and compiling observations; includes uncertainty about HTTP arrival. Not proof of a 1.125-second actual queue. |
| Compilation/build pipeline | **27.974–30.203 s** | Conservative interval from compiling/ready transitions; includes service build and artifact preparation, not just Bhash frontend work. |
| Final status polling/readiness detection | **0–1.104 s** | Uncertainty between the last compiling observation and the ready response, including HTTP timing. |
| Download and asset-loading envelope | **1.305 s**, **2.376 MB transferred** | First artifact request to last asset response. Includes dependency sequencing and initialization gaps; initialization overlaps this window. |
| Runtime initialization after last asset response | **0.193 s** | Remaining startup before the running message. Earlier initialization is already inside the asset-loading window. |
| Execution through visible output | **0.029 s** | Running notification to output DOM update; includes message delivery and UI work. |

**Do not add all rows in this table.** Queue/build/poll rows are uncertainty
intervals inside the measured 30.222-second service-ready stage. Initialization
partly overlaps asset loading. The consecutive stage table above provides the
additive view. In this representative run, artifact requests began approximately
4 ms after the ready response.

### Downloads and caching

HAR records show about **2.376 MB transferred per uncached program**, including
headers, for about **5.10 MB of decoded assets**. `Content-Encoding: zstd` was
already present; compression is working and is not an unimplemented quick win.

Fresh asset-loading envelopes ranged from **0.991 to 1.652 seconds** across the
four misses. All six cached repeats recorded **zero transferred bytes for their
artifact/runtime assets**. Their cache-loading envelopes still took approximately
**0.395–0.641 seconds** because loading cached files and starting a worker/runtime
is not free. API traffic is separate and still occurred.

The same content-hashed runtime file names were requested beneath different
job-specific artifact directories for each uncached source. This prevents normal
URL-based HTTP cache sharing across those job directories. For example, the native
runtime WebAssembly file accounted for about **1.24 MB**, CoreLib **0.446 MB** and
the ICU data file **0.551 MB** on the first uncached run. The generated Hello
program asset was about **5.3 KB transferred**.

### First output versus program completion

The page displays output only when it receives the final result. The repository
worker calls `Run`, then retrieves `Output` and `Errors` and posts the result.
The loop's initial `आरंभ` marker therefore did not become visible before the
loop completed. Its running-to-output interval was **0.273–0.375 seconds** on
cached repeats. This measures completion-based responsiveness; it does not
timestamp the first Bhash write inside the runtime.

## Recommendations, in priority order

1. **Reduce per-edit build work.** Add server phase timings first, then prototype
   prebuilding the browser host/runtime/standard-library portion and compiling
   only the changing program. Current `WasmCompiler` source creates a temporary
   project and invokes a separate `dotnet publish` with build servers/shared
   compilation disabled for each uncached program. Preserve isolation, diagnostics,
   output compatibility and cancellation. The amount saved is unmeasured until
   that prototype is benchmarked.
2. **Pin precompiled starter examples outside the small general-purpose cache.**
   Existing cache hits already demonstrate roughly one-second Hello runs. Warm
   or prebuild examples for each compiler release and retain them independently
   of ordinary edited-source cache entries. Measure actual eviction/hit rates
   before changing general cache capacity. This benefits examples and repeats;
   arbitrary edits still need the first recommendation.
3. **Serve versioned shared runtime assets across jobs.** Reuse stable URLs for
   the same runtime/ICU/framework bytes, while keeping user program artifacts
   isolated. This targets the repeated ~2.38 MB transfers and roughly one-second
   asset-loading windows, with greater potential on slower connections. Keep
   compiler/runtime identity and cache invalidation explicit.
4. **Shorten ready-result delivery.** Evaluate server-sent events or another
   readiness notification while retaining polling fallback. Existing one-second
   polling produces up to about 1.1 seconds of final-boundary uncertainty here.
   The actual polling delay and average savings are not known from this trace.
5. **Stream bounded output for longer programs.** Forward periodic output batches
   from the worker so early writes appear before completion. Preserve output
   limits and Stop behavior. This improves perceived responsiveness without
   reducing compilation time.

Raising compilation concurrency is not supported as the first remedy by these
samples: queueing was only bounded within about 1.1–1.5 seconds, while the build
pipeline consumed tens of seconds. Concurrent-load behavior remains unmeasured.

Direct LLVM-to-WebAssembly remains a separate architectural option. These
measurements do not compare it with .NET publishing or estimate its development
cost. They support addressing per-edit build work before optimizing program
execution, which took only tens of milliseconds for Hello.

## Method, evidence and reproducibility

Files in this directory:

- `browser-probe.js`: temporary DevTools probe, including cleanup and export.
- `timings.json`: all ten runs; playground-host resource metadata only.
- `network.har`: sanitized, playground-host-only HAR evidence.
- `analysis.json`: computed per-run stages, bounds and transfer metrics.
- `analyze.py`: deterministic offline analysis; does not contact a server.
- `loop.bhash`: arithmetic workload for Statements mode; the timed version also
  included a unique cache-busting comment.
- `../../target/playground-profiling/measurement-session.png`: local browser-session
  proof outside the report directory, not intended for public publication because
  browser chrome includes personal profile/bookmark UI. The original browser HAR
  export and intermediate files are also retained under that ignored target folder.

Reproduce the analysis from the repository root:

```sh
python benchmarks/playground-profiling/analyze.py \
  benchmarks/playground-profiling/timings.json \
  --har benchmarks/playground-profiling/network.har \
  --output benchmarks/playground-profiling/analysis.json
```

The probe records `performance.now()` at Run invocation, compile/job responses,
runner messages and the first nonempty output DOM mutation. A next-animation-frame
timestamp is also retained (0.7–5.8 ms later in these samples); DOM update time is
not an exact screen-paint metric.
Fetch observations use a diagnostic clone of each JSON response and return the
original fetch promise without awaiting the clone. Diagnostic overhead was not
independently calibrated.

To collect a fresh sample set, review and run `browser-probe.js` in the playground's
DevTools Console, keep Network recording enabled with normal caching/no throttling,
and set `__bhashPerf.label` before each Run. Use unique comments to force source
cache misses and unchanged source for repeats. After all runs finish, call
`__bhashPerf.export()` and use DevTools' sanitized HAR export. Retain only playground
hosts and remove sensitive headers/cookies before sharing evidence. Run
`__bhashPerf.cleanup()` and restore the original draft when finished. The browser
may require the user to approve DevTools pasting manually.

The API exposes status without server start/end timestamps. Bounds use the request
start and response-observation times of adjacent status samples. Polling and
network timing prevent exact queue/compile separation. No existing long queue was
observed, and no concurrent synthetic load was introduced.

The first sample's initial probe missed the final result message because the page
removed the iframe during its own listener. Its output DOM timestamp and running
message were captured, and exit 0 was observed in the UI. The probe was corrected
to retain the previously verified runner source; all subsequent nine runs captured
exit 0 directly. The first raw record remains unchanged; its execution/output
measurement uses the output DOM timestamp.

HAR transfer bytes include headers; some encoded body sizes are unavailable and
are represented as unknown rather than zero in `analysis.json`. Worker cache
records may have status 0 even when successful execution is observed; these are
not treated as program failures. Asset-loading envelopes include parallel work
and request-sequencing gaps. Browser startup and download cannot be fully isolated
into independent CPU-only/network-only durations with this trace.

Checks performed: probe JavaScript syntax; ten complete output timings; all nine
captured exit codes equal 0; monotonic stage boundaries; zero artifact transfer
bytes on cache hits; independently calculated loop checksum matched the UI.
No optimization was implemented or deployed.

Reference for HAR export, cache indicators and transferred versus decoded sizes:
[Chrome DevTools Network reference](https://developer.chrome.com/docs/devtools/network/reference).
