# Performance baseline

This suite measures the **same generated Bhash sources** on JVM, .NET and native,
before changing compiler or runtime performance. Measurement currently runs on
Linux/WSL. This keeps process accounting consistent across the three targets;
it does not establish Windows or macOS performance. Python 3.10+, Java 17+, .NET
10 SDK, LLVM/Clang 18, GNU `/usr/bin/time` and the usual Linux C development tools must already exist.
No Python packages are required. Tool installation/downloads are outside timing.

```sh
mvn --batch-mode --no-transfer-progress -DskipTests package
python3 -m unittest discover -s benchmarks -p 'test_*.py'
python3 benchmarks/run.py --output target/baseline-before-optimization
python3 benchmarks/compare.py target/baseline-before-optimization/results.json --output target/baseline-before-optimization/comparison.md
```

For a quick conformance run use `--trials 1 --compile-trials 1 --sizes small
--warmup-ms 100 --batch-ms 50 --batches 1`. These settings are **not** a performance
baseline. The default is 7 independent process trials, 3 independent compilations,
1500 ms warm-up within each application process, then five 300 ms measured batches.
Warm-up is a minimum observation window, not proof the JIT has reached equilibrium.
Review batch rates and increase warm-up for workloads that have not stabilized.
Startup and generated multi-file projects are run without warm-up.

The `Bhash cross-target performance baseline` workflow runs the defaults on manual
dispatch or benchmark-code pushes to `perf/**` branches, and uploads
sources, raw observations, environment, sizes and comparison report. The benchmark
has no speed thresholds: shared hosted-runner variability makes those premature.
Use a dedicated, otherwise idle machine for optimization acceptance. Record power
mode, virtualization, antivirus activity and background load separately when relevant.
The runner never changes CPU governors, security software, ASLR or host settings.
Pull requests run the quick conformance profile for all three targets, including
Linux accounting tests. Its output is labelled by the recorded configuration and
must not be used as the release performance baseline. Documentation/report-only
pushes do not automatically repeat the full measurement campaign.
`compare.py` can regenerate Markdown and CSV from saved data without executing any
programs; it labels shortened conformance profiles explicitly and includes
per-workload CPU/task, memory, target ratios and variability flags.

| Measurement | Definition |
|---|---|
| Fresh-process startup | Minimal checksum program's launch-to-exit elapsed time, including runtime initialization/shutdown |
| Application elapsed time | Launch-to-exit monotonic host time; compiler invocation is excluded |
| CPU time | `wait4` user + system seconds; all runtime threads and waited-for child processes |
| Sustained throughput | Completed `काम` calls divided by host monotonic measured-phase elapsed time |
| Measured CPU/task | `/proc/PID/stat` CPU differences at batch boundaries divided by calls; OS clock-tick resolution applies |
| Peak process RSS | GNU time's Linux `wait4.ru_maxrss` for the application; includes heap, runtime, JIT, stacks and resident mapped pages |
| Measured-phase memory | Median of 10 ms `/proc` RSS observations within each measured batch, then median across batches/trials |
| Compile cost | Fresh compiler JVM, semantic analysis, lowering, backend tools, link/package, normal validation; dependencies already installed |
| Compiler peak RSS | Largest process high-water RSS including waited-for children; **not simultaneous summed tree memory** |
| Application/deployment size | Exact bytes of generated deployment files including notices/metadata; shared JVM/.NET installation is excluded |
| Native GC diagnostics | Separate untimed execution with `BHASH_GC_STATS=1`; allocation/collection counts and peak charged heap bytes |

Small/medium/large inputs cover integer math, exact binary64 arithmetic, array
insertion sort, lists/maps, ASCII/Hindi/emoji split/join, nested JSON parse/write,
linked object graph allocation/traversal, and UTF-8 file write/read. Compile scaling
uses generated projects with 11/51/151 source files. Sources/checksums are deterministic;
project and minimal outputs have independent expected values. Timed work alternates
between `n` and `n+1`; every call's output is checked and included in a printed
checksum that the host independently validates. All targets receive identical source
bytes. Iteration counts adapt to each target's speed, so compare calls/s, not raw
checksum totals or overall elapsed time for the duration-based tests.

Release defaults are unchanged: JVM Java 17-compatible bytecode with default runtime
JIT, .NET framework-dependent Release output with default tiering, native LLVM `-O2`
with the bundled collector. No debug builds, forced GC, profilers or sanitizers run
inside timed trials. Recognized injected Java/GC flags are removed and their names
recorded; exact effective environment and tool versions are retained.

Builds use new destination directories, randomized target/workload order, warm package
caches, no explicit persistent MSBuild server. This is a clean-output build, **not**
a cold filesystem/package-cache benchmark. Runtime trials are independently shuffled.
File tests deliberately measure buffered, warm-cache I/O; they do not measure disk
durability or physical-device bandwidth. Process RSS is not heap occupancy, exclusive
memory or installed runtime size; avoid comparing it to native collector charged bytes.

Raw logs preserve exit status, stdout/checksums, stderr, elapsed/CPU time and timestamped
RSS samples. `results.json` includes medians, min/max, sample counts and coefficient of
variation. The report does not present p95/p99 from seven observations. CPU utilization
percent is not used as an efficiency score. Missing sampled data is `null`, never zero.
Kernel-reported zero peak RSS for a very short process is also `null`; it does not
mean the application used no memory. Measured CPU/task uses batches with both CPU
snapshots available and records their coverage; a process may exit before the last
snapshot can be read.
The host's monotonic clock measures elapsed phases; Bhash's wall clock only selects
the batch stopping point. A clock discontinuity, unexpected output, failed checksum,
nonzero exit, missing target/toolchain or timeout fails the run, so no success report
can silently omit a target. Existing output directories are refused to preserve data.

Each invocation uses a lightweight GNU time wrapper for peak RSS accounting, avoiding
the Python observer's pre-exec RSS becoming a false floor for small applications.
The sampler observes the actual application's PID, not the wrapper. Elapsed and CPU
time include the wrapper's small overhead; seven `/bin/true` launches record the
observer floor in `environment.json`. Do not subtract that noisy floor from results
or interpret sub-millisecond differences in short startup measurements.

Initial scope omits comparable allocation bytes/GC pause distributions, energy,
hardware counters, PSS/USS, frontend-only stage timings, self-contained managed runtime
packaging and incremental builds. Native diagnostic counters cannot fairly substitute
for JVM/.NET allocation measurements. Add equivalent, untimed diagnostic experiments
before drawing collector conclusions. Long-lived service/retained-heap workloads and
idiomatic Java/C#/C reference implementations are future extensions.

Interpret a baseline by workload and input size. Inspect per-batch warm-up trends,
repeat noisy observations on dedicated hardware, and preserve the compiler hash,
source hashes, environment and raw data with any claimed improvement. Performance
does not replace cross-target correctness or forced-GC safety tests.
