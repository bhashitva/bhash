# Native performance gap investigation — 31 October 2026

## Recommendation

Optimize **typed primitive array reads and writes first**, then **object construction and allocation**. Preserve the precise, stop-the-world, nonmoving mark-and-sweep collector and its future relocation contracts. The evidence does not justify a collector redesign as the first step.

This investigation makes **no compiler or runtime optimization changes**. Diagnostic sources and a workflow are isolated on `profile/native-hotspots`; main is unchanged by this investigation. Percentages below locate costs; they are not promised speedups.

## Baseline context

The merged [PR #44 baseline](https://github.com/bhashitva/bhash/pull/44) measured identical Bhash workloads across targets. Medium insertion-sort throughput was 11,909 calls/s on JVM, 26,723 on .NET, and 1,458 on native: native took about 8.2× and 18.3× as long per call respectively. Medium object-graph throughput was 663,913, 223,357 and 37,693 calls/s: approximately 17.6× and 5.9× per-call gaps. These are particular microbenchmarks, not general language rankings.

Native retained advantages: the medium object workload measured about 3.1 MiB RSS versus JVM 381.2 MiB and .NET 48.9 MiB. Those process-level figures include runtime overhead and different heap policies; they are not per-object sizes.

[Baseline run](https://github.com/bhashitva/bhash/actions/runs/41244292174). Its host was AMD EPYC 9V74. The profiling host was AMD EPYC 7763, so profiling durations and throughput must not be compared directly with baseline timings.

## Evidence and reproducibility

Primary successful [profiling run #3](https://github.com/bhashitva/bhash/actions/runs/41253980225), commit `9e57a00e55162845d64a7ddcacc1bf4a39f2f263`, Ubuntu 24.04 x86-64, glibc 2.39, LLVM/Clang 18.1.3, Valgrind 3.22, GDB 15.1. The compiler JAR SHA256 is **`bfcda6940c8c8dac0d336e199f0ee8fca8b589346aa7f5231941dba0cd4d10a0`**, exactly matching the baseline. Captured runtime source SHA256 is `b3fa72af00cb01584f4a7175ee58f1d9218a04687bc0d4acf1fff4e5c2d3a8dd`.

The compiler used its original release settings (`-O2`, generic x86-64), unchanged generated LLVM and unchanged C runtime. A forwarding Clang wrapper copied the existing inputs and recorded arguments without altering them. No debug build, LTO, changed collection threshold, or optimizations were introduced.

Sixteen fixed-work profiles passed independent checksum verification in normal runs, separate GC-stat runs, and Callgrind runs. All GC-stat runs reported `live=0` at shutdown. Three mixed-workload sampling runs passed the full output/checksum protocol and exited normally, yielding **924 stack snapshots**. An earlier successful [run #2](https://github.com/bhashitva/bhash/actions/runs/41253583341) supplies an independent repeat of the original eleven controls and two sampled workloads. Array instruction totals were identical between those two runs.

Methods:

- [Callgrind](https://valgrind.org/docs/manual/cl-manual.html) counts executed machine instructions (`Ir`). Its self counts and inclusive call-edge costs distinguish dispatch, allocation and collection. Self counts were audited to sum exactly to total instructions. These percentages are **not CPU-time or cycle percentages**. Fixed-work totals include startup and shutdown; selected drivers perform roughly a million operations to limit that contribution.
- Jittered GDB interrupt/continue snapshots sampled the unmodified optimized executable, using asynchronous MI, with no inferior function calls or writes. Workloads had 1.5 seconds of warmup and five 1-second measured batches. These are wall-clock stack-stop shares in single-thread CPU-bound programs, **not hardware CPU-cycle measurements**. Debugger overhead, optimized frames, unwind limitations, scheduling and sampling bias limit precision. [GDB MI execution documentation](https://sourceware.org/gdb/current/onlinedocs/gdb.html/GDB_002fMI-Program-Execution.html).
- `perf` CPU-clock recording was unavailable because the hosted machine denied performance events (`perf_event_paranoid=4`). Security settings were left unchanged. The raw `sampling[].method = "none"` describes that failed perf attempt; valid independent GDB snapshots are in `stack-samples.json`.
- GC statistics measure runtime accounting and shutdown cleanup, not pause distributions, hardware cache behavior, or sustained live-heap size. No allocation flame graph or pause-latency claim is made.

## 1. Arrays: shared dispatch dominates even without allocation

| Workload | Dispatcher self instruction share | Allocations | Collections including shutdown |
| --- | ---: | ---: | ---: |
| Sort 64 | 83.2% | 488 | 1 |
| Sort 256 | 83.4% | 30 | 1 |
| Sort 1024 | 83.5% | 4 | 1 |
| Read-only array loop | 82.7% | 2 upfront | 1 |
| Scalar array-write loop | 83.6% | 2 upfront | 1 |

The duration-based medium sort sampled `bh_collection_call` at **266/309 stops (86.1%)**; the repeat run measured **281/309 (90.9%)**. Separate read/write controls reproduce the instruction concentration while allocating their array only once. Their single collection is shutdown cleanup. This establishes that GC tuning cannot address the principal array bottleneck.

Source and captured assembly explain the cost. `LlvmEmitter` routes indexing through the general `bh_collection_call` helper. It handles several collection kinds and operation codes, establishes four root slots, checks types/indexing and locates storage. A caller `bh_instance` null check precedes a second null check in the dispatcher. The medium fixed sort makes **1,493,381 dispatcher calls and 1,493,381 caller null-helper calls**. The compiler and C runtime are compiled separately, limiting cross-unit specialization/inlining.

**First implementation proposal:** introduce narrow typed paths for primitive array length/read/write, starting with `पूर्णांक[]`. Keep the generic path for collections and operations outside the proven case. Thin typed helpers or LLVM intrinsics can avoid repeated kind/opcode dispatch and generic root setup without exposing the entire private object layout to generated code.

Correctness constraints: preserve null and bounds exceptions, receiver/index/value evaluation order, source locations, exception unwinding and rooting. Evaluate potentially allocating expressions with rooted receivers and reload afterward. Do not retain an interior pointer across a safepoint. An error path may allocate an exception even if the successful primitive access does not allocate. Reference array writes still need their appropriate GC/reference contract. Do not globally label the generic dispatcher as a no-GC leaf or remove validation indiscriminately.

## 2. Objects: allocation and checked reference stores, with a material collector cost

For the fixed medium object graph:

| Cost | Instruction share | Stack snapshot share in duration workload |
| --- | ---: | ---: |
| Allocation including its callees and collection | 53.1% inclusive | 57.2% inclusive (175/306) |
| Collection including tracing/sweep/free | 18.5% inclusive | 25.8% inclusive (79/306) |
| Checked reference store | 16.3% self | 9.5% top frame (29/306) |

**Allocation includes collection; those rows must not be added.** A repeat sample found 60.5% allocation-inclusive and 26.5% collection-inclusive, supporting the same ordering without implying exact percentages. Collector self instructions alone are 4.9%, which would understate its cost by excluding its tracing/free callees.

The fixed medium driver allocated **1,000,912 nodes**, called `bh_reference_store` **2,001,824 times**, and called `bh_instance` **6,005,472 times**. It naturally collected 113 times and ended with zero live allocations. The allocation-only control still spent 63.9% of instructions under allocation including collection (22.1% under collection). The reference-store-only control spent 78.5% of its instructions in `bh_reference_store`, despite allocating only two objects. A traverse-only control spent 33.5% in caller null checks and did no workload-time collection.

Source/LLVM/disassembly findings:

- Every node uses a separate zeroing `calloc`; sweep eventually uses individual `free` calls. Allocation validates immutable layout metadata, initializes headers and maintains the object membership hash index.
- Membership lookup is already **hash-based**, not a linear scan. Replacing a supposed linear search is not an actionable recommendation.
- The generated default constructor writes null into a reference field through `bh_reference_store`, despite the fresh allocation being zeroed. The graph then writes the real link: two checked reference stores per node. The allocation-only case still performs one checked null store per node.
- The checked store validates ownership, bounds and declared reference offsets before writing. Generated field reads/writes also invoke null helpers, including constructor accesses to a freshly allocated object.
- On this Linux ABI, disassembly shows a 96-byte allocation header and the sample node has 24 bytes of payload: **120 bytes requested/accounted per node before libc rounding/bookkeeping**. A dummy alignment member contributes to the header footprint. This is a Linux layout finding, not a portable physical-memory claim. Any header reduction requires ABI, alignment and debugger verification on every platform.

**Second implementation proposal, staged:**

1. Remove only provably redundant compiler-generated default null/zero stores on freshly zeroed, unpublished objects, and redundant null checks where compiler proof is reliable. Preserve explicit user initializers, inheritance order and observable exceptions.
2. Validate immutable type layouts once and provide a trusted, narrowly scoped construction/store path for compiler-proven offsets, retaining checked generic entry points. Keep roots and future write-barrier/relocation contracts explicit.
3. Reprofile. If allocation remains dominant, evaluate nonmoving size-class/slab or free-list allocation, including zeroing, membership indexing, reclamation and fragmentation. It can retain the agreed mark-and-sweep model. Treat header compaction as a separate measured change.

## 3. Cross-checks against other CPU workloads

The fixed integer workload spent 62.9% of instructions in the integer remainder helper, and decimal spent 51.4%. However, the decimal duration sample found only **63/309 stops (20.4%)** there; 244/309 were in generated workload code. This is a concrete reason not to interpret instruction counts as time shares. The decimal benchmark contains integer remainder work; the data does not establish that a decimal arithmetic helper is its main bottleneck.

Numeric helper specialization/inlining is a plausible later, bounded experiment, preserving division-by-zero and minimum-integer overflow semantics. It ranks behind the stronger array result and the requested object investigation.

The list/map case placed 46.8% of instructions in generic collection dispatch, 13.5% in clearing memory, 10.5% in reserve and 9.3% in hashing. It suggests later collection specialization/capacity work, but does not justify changing map algorithms now. Text and JSON distribute costs over split/join/parsing, allocation and checked stores, so the object work may benefit them too. File I/O is excluded from this ranking because its baseline measurements were noisy.

## Proposed optimization sequence and acceptance checks

| Order | Scope | Why now | Required validation |
| --- | --- | --- | --- |
| 1 | Typed primitive array access | Dominates both instruction and stack evidence; isolated controls exclude GC as cause | Null/bounds/error parity, side-effect evaluation order, exception unwinding, nested allocating index/value expressions, forced-GC roots, Linux/Windows/macOS; rerun baseline and profiles |
| 2 | Compiler-proven constructor/default-store simplification | Directly observed redundant work; narrower than allocator redesign | Defaults, inherited fields, explicit initializers, constructor exceptions, forced GC, debugging and reference safety; unchanged checksums |
| 3 | Trusted layout/allocation path, then size-class allocation if warranted | Allocation-inclusive cost remains large; can preserve nonmoving collector | Heap accounting, stress/forced GC, OOM/errors, membership validity, fragmentation, distribution/debugger compatibility, peak RSS and CPU measurements |
| 4 | Numeric helper and broader collection specialization | Secondary evidence; smaller or less directly isolated gains | Cross-target edge semantics and full benchmark reruns |

For each change, retain the original benchmark sources and workload sizes, compare on the same host/toolchain with repeated randomized runs, and report throughput, CPU/call, startup, RSS, allocation counts, GC behavior, artifact size and compile time. Use unprofiled measurements to establish gains; profiler runs only explain them. Add larger live-heap and allocation-churn cases before choosing a collector redesign. Require correctness/forced-GC/debugger tests before accepting a speed gain. No percentage improvement threshold or projected speedup is asserted from the present evidence.

## Complete fixed-work instruction inventory

Instruction shares below are self costs. Collection counts include the shutdown collection; peak bytes are runtime accounting, not process RSS.

| Driver | Instructions | Top three self costs | GC accounting |
| --- | ---: | --- | --- |
| array-sort-64 | 192,094,003 | `bh_collection_call` 83.2%; `bhash_entry` 13.2%; `bh_instance` 3.3% | allocations=488 collections=1 live=0 peak_bytes=245952 |
| array-sort-256 | 181,529,124 | `bh_collection_call` 83.4%; `bhash_entry` 13.2%; `bh_instance` 3.3% | allocations=30 collections=1 live=0 peak_bytes=48656 |
| array-sort-1024 | 383,206,366 | `bh_collection_call` 83.5%; `bhash_entry` 13.2%; `bh_instance` 3.3% | allocations=4 collections=1 live=0 peak_bytes=25056 |
| objects-64 | 793,356,098 | `bh_reference_store` 16.3%; `bhash_entry` 15.6%; `bh_alloc` 13.1% | allocations=1007812 collections=115 live=0 peak_bytes=1060560 |
| objects-512 | 787,085,474 | `bh_reference_store` 16.3%; `bhash_entry` 15.5%; `bh_alloc` 13.1% | allocations=1000912 collections=113 live=0 peak_bytes=1142880 |
| objects-4096 | 803,561,977 | `bh_reference_store` 15.9%; `bhash_entry` 15.2%; `bh_alloc` 12.9% | allocations=999546 collections=92 live=0 peak_bytes=2027520 |
| integer-medium | 22,262,849 | `bh_remainder` 62.9%; `bhash_entry` 36.0%; `__GI___tunables_init` 0.4% | allocations=0 collections=1 live=0 peak_bytes=0 |
| decimal-medium | 27,264,709 | `bh_remainder` 51.4%; `bhash_entry` 47.7%; `__GI___tunables_init` 0.3% | allocations=0 collections=1 live=0 peak_bytes=0 |
| text-medium | 128,216,155 | `bh_data_split` 14.8%; `bh_data_join` 9.8%; `bh_reference_store` 9.7% | allocations=113750 collections=16 live=0 peak_bytes=1056357 |
| json-medium | 87,606,654 | `_int_malloc` 12.3%; `bh_alloc` 9.1%; `calloc` 6.6% | allocations=80850 collections=12 live=0 peak_bytes=1056898 |
| list-map-medium | 141,013,745 | `bh_collection_call` 46.8%; `__memset_avx2_unaligned_erms` 13.5%; `collection_reserve` 10.5% | allocations=21500 collections=23 live=0 peak_bytes=1065664 |
| array-read | 122,886,356 | `bh_collection_call` 82.7%; `bhash_entry` 13.5%; `bh_instance` 3.3% | allocations=2 collections=1 live=0 peak_bytes=4336 |
| array-write | 130,064,077 | `bh_collection_call` 83.6%; `bhash_entry` 12.8%; `bh_instance` 3.2% | allocations=2 collections=1 live=0 peak_bytes=4336 |
| object-alloc | 652,377,728 | `bh_alloc` 15.8%; `bhash_entry` 13.7%; `_int_malloc` 11.9% | allocations=1000912 collections=115 live=0 peak_bytes=1048560 |
| object-traverse | 24,487,776 | `bhash_entry` 63.4%; `bh_instance` 33.5%; `bh_remainder` 0.3% | allocations=513 collections=1 live=0 peak_bytes=61560 |
| object-reference-store | 83,612,703 | `bh_reference_store` 78.5%; `bhash_entry` 16.1%; `bh_instance` 4.9% | allocations=2 collections=1 live=0 peak_bytes=240 |

## Raw evidence

- [Run #3 artifact](https://github.com/bhashitva/bhash/actions/runs/41253980225/artifacts/13061883280), SHA256 `4b459950cfdb5e10f2bfee715e765a8ebfac39038c5b7851fcd9b4093c02cc5f` (CI retention: 30 days).
- [Run #2 artifact](https://github.com/bhashitva/bhash/actions/runs/41253583341/artifacts/13061699898), SHA256 `d12aa2de0d242847c69c295249a4df5b83ea105ab1752c5bea4ac3c822455783`.
- Raw results contain generated Bhash programs, LLVM, exact Clang arguments, copied runtime inputs, symbol tables, disassembly, Callgrind data/annotations, GC output, sampling snapshots and GDB logs. A data-only local archive omits executables and unusable perf recordings.
- An initial diagnostic attempt collected the fixed profiles but failed output capture in the sampling harness. The harness was corrected; both successful runs above validate output and normal exit. No compiler change was involved.

The measurements are Linux x86-64 only. Source-level causes are relevant to other native targets, but their performance and ABI implications remain to be measured. Recommendations stop at the investigation stage; no optimizations have been applied.
