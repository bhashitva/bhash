# Live playground comparison — 1 November 2026

The prebuilt browser host is deployed. Changed Hello programs now reach visible
output in a median **4.070 seconds**, compared with **31.850 seconds** before:
**87.2% less waiting**, or about 7.8 times faster. Cached Hello reruns regress
from **1.007 to 1.557 seconds**. The next performance work should target the
shared host's download size and fresh runtime startup, while preserving execution
isolation. No further optimization was made during this measurement pass.

## Public Edge measurements

All times are Run to first visible output, including service and browser work.
“Miss” means a new source compilation, forced by a unique source comment; it
does not mean a cold server, SDK or operating-system cache.

| Workload | Samples per version | Before median | After median | Change |
| --- | ---: | ---: | ---: | ---: |
| Hello, compilation-cache miss | 3 | 31.850 s | 4.070 s | 87.2% lower |
| Hello, compilation-cache hit | 3 | 1.007 s | 1.557 s | 54.6% higher |
| Arithmetic loop, compilation-cache miss | 1 | 31.754 s | 4.060 s | 87.2% lower |
| Arithmetic loop, compilation-cache hit | 3 | 1.194 s | 1.766 s | 47.8% higher |

The first Hello run with the new runtime took **6.176 seconds**, versus
**46.030 seconds** in the baseline. Subsequent changed Hello sources took
**3.988 and 4.070 seconds**. The first observation includes a previously unused
versioned runtime download; the whole browser cache was not cleared.

Both passes used the same Windows machine and Edge 156 profile, no throttling,
browser caching enabled, and existing extensions including Norton enabled.
The baseline was collected on 31 October; the deployed follow-up on 1 November.
Each pass contains ten sequential runs in Statements mode. This is a small
before/after sample under ordinary live conditions, not a controlled load test
or a statistical latency guarantee. Full-program diagnostics/isolation were
checked separately. The arithmetic loop's independently calculated checksum
1007993832 matched the displayed Devanagari output in every follow-up run.
All ten follow-up runs exited successfully with no error output.

## Where the time goes

The following are consecutive client intervals for individual representative
runs. Downloads overlap frame/bootstrap and worker initialization; their
envelopes below must not be added as another phase.

| Follow-up run | Total | Run → service ready | Frame bootstrap | Worker/runtime initialization | Execution and output delivery |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hello, first runtime download | 6.176 s | 2.348 s | 0.407 s | 3.399 s | 0.023 s |
| Hello, second unique source | 3.988 s | 2.189 s | 0.386 s | 1.384 s | 0.030 s |
| Hello, third unique source | 4.070 s | 2.189 s | 0.303 s | 1.557 s | 0.020 s |
| Hello, second cached repeat | 1.557 s | 0.178 s | 0.183 s | 1.173 s | 0.023 s |
| Loop, unique source | 4.060 s | 2.161 s | 0.317 s | 1.323 s | 0.259 s |
| Loop, second cached repeat | 1.766 s | 0.054 s | 0.188 s | 1.265 s | 0.259 s |

Server timings on the four misses show **0.06–0.90 ms queueing** and
**1.511–1.669 seconds compilation/bundling**. The remaining **0.558–0.678
seconds** between Run and service-ready includes request transport, submission,
polling and detection overhead; these were not individually isolated. Cached
runs do no compilation: a cached job's reported build duration describes its
original build and must not be charged again to a rerun. Baseline queue/build
values are polling-derived bounds, whereas follow-up values are server timers.

Cached Hello worker/runtime initialization has risen from a median **0.388 to
1.173 seconds**. Cached loop initialization is **1.265 seconds** after deployment.
This is the clearest measured remaining cost. It includes cached asset loading,
assembly loading and runtime startup, rather than pure runtime initialization.
The execution interval includes result messaging/UI work and is not a CPU timer.
Output remains buffered until completion; “first output” does not mean streaming
the loop's initial print while it is still running.

## Download and caching tradeoff

| Property | Before | After |
| --- | ---: | ---: |
| First Hello assets, transferred including protocol overhead | 2.377 MB | 9.840 MB |
| Hello decoded asset content, per run | 5.096 MB | 24.286 MB |
| Asset requests per run, including cache reads | 12 | 184 |
| Shared runtime requests in follow-up | — | 178 |
| New source after initial runtime download | about 2.376 MB | 7.400–7.502 KB |
| Cached rerun asset transfer | 0 bytes | 0 bytes |

Units above are decimal. Follow-up's first shared runtime transfer was
9,832,240 bytes, plus 7,825 bytes of program artifacts. The subsequent unique
loop transferred 7,423 bytes. All 178 shared runtime requests reused cached
responses on subsequent runs, including different source programs. Every cached
rerun reused all 184 asset responses. Zero transfer here comes from HAR's
`_transferSize`, not from the absence of resource loading work.

First-download requests spanned **3.671 seconds**. For the second changed Hello,
the wire-transfer envelope was **0.639 seconds**, while the envelope including
cached requests was **1.579 seconds**. Cached requests and initialization still
cost time despite transferring no fresh runtime bytes. Decoded content size is
not process memory usage or JavaScript/Wasm heap residency.

## Publishing profile and implementation

The earlier isolated VPS publishing profile found a median CLI build reduction
from **29.061 to 1.548 seconds**. A representative classic publish attributed
16.939 seconds to Brotli compression, 4.520 seconds to linking and 2.663 seconds
to C# compilation. SDK task timings are nested/overlapping accounting categories,
so they cannot be summed into an independent phase total. That profile predates
final hardening and is supporting evidence, not the final live comparison.
See [the retained VPS profile](../vps-publish-profile.json) and
[browser host design and operation](../../../docs/browser-host.md).

The deployed host is prepared once in the image. Submissions compile/bundle
only the changing program, and versioned immutable runtime URLs can be reused
across different programs. Each execution still receives a new worker/runtime.
The larger reusable host removes repeated linking/compression work but retains
more assemblies and incurs more loading work than the old program-specific
trimmed bundle.

## Deployment and correctness verification

Deployment uses merged PR #47 revision
`5a8fb5ec6f0a6dbee1de64eb00e5a8c327165205`, image
`bhash-playground:prebuilt-5a8fb5e`. The effective running image, revision label
and prepared-host environment were checked before collecting these results.

An initial activation restarted an older image because a legacy Compose
override pinned that image and bind-mounted an old frontend. Health alone did
not catch this. A guarded retry rolled back, then the effective base/override
configuration and actual mounted gateway configuration were corrected. The
final deployment retained the gateway's analytics configuration and existing
data, Chess and Discord links. It also retained the non-root, read-only service
and internal compiler network. Deployment receipts and previous configuration
backups remain on the VPS under the release's `effective-deployment` directory.

Verification after final activation:

- Public health succeeded; the actual running image matched the intended release.
- Shared runtime URLs returned 200 with immutable cache headers on the execution
  origin and 404 on the editor origin.
- A deliberate undefined name produced the Hindi BH2001 diagnostic at the
  correct Statements source location.
- Stop terminated a running infinite loop and returned the UI to Stopped.
- A full program incrementing a static field printed १ on both its first and
  cached second execution, demonstrating fresh static state.
- All 31 checks for merged PR #47 passed, covering broader isolation,
  cancellation and browser/compiler cases. The live smoke tests supplement
  those checks; they are not a complete repeat of every CI case.

The temporary timing probe was removed. The user's original Edge source, mode
and input were restored exactly, DevTools closed, and the page refreshed.
The separate verification tab's original draft and mode were also restored.

## Recommended next work

1. Profile the 178 shared runtime requests and assembly loading. Investigate a
   host trimmed once with explicit roots that preserve supported Bhash features,
   reflection/JSON behavior and diagnostics. Compare first visit, changed source
   and cached reruns; the cached regression is an explicit acceptance criterion.
2. Measure readiness polling separately and consider a long-poll or notification
   path if it can reduce the observed roughly 0.6-second miss overhead while
   retaining cancellation and isolation.
3. Repeat on a clean profile, constrained network and concurrent clients before
   setting user-facing latency budgets. The larger first download may matter
   more on mobile connections than on this client.

Do not reuse a live runtime between users or executions merely to reduce
startup: preserving isolation and fresh program state remains a requirement.
No native-backend change is justified by these measurements: this milestone
removed the dominant publishing work without changing browser execution target.

## Evidence and reproduction

The original [baseline report](../report.md), probe and evidence are unchanged.
This folder contains follow-up `timings.json`, sanitized `network.har`, combined
`analysis.json`, and the follow-up probe. The HAR retains relevant public
playground/runtime requests and timing/size/cache fields; it excludes analytics,
cookies, response bodies, initiator stacks and unrelated browsing data.
Only public benchmark programs were submitted during timing collection.

From the repository root, the retained sanitized inputs reproduce the analysis:

```sh
python benchmarks/playground-profiling/compare_prebuilt.py --after-probe benchmarks/playground-profiling/after-prebuilt/timings.json --after-har benchmarks/playground-profiling/after-prebuilt/network.har --output target/playground-comparison
```

`SHA256SUMS` covers the follow-up evidence, report, probe and comparison script.
The original baseline checksum file remains separate and unchanged.
