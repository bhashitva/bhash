# Native primitive array access

Successful `BH_GET` and `BH_SET` operations on primitive arrays now use compiler-selected `bh_primitive_array_get` / `bh_primitive_array_set` entry points, bypassing general collection dispatch and temporary root frame setup. This applies to integer, boolean, decimal, byte, int32 and enum carriers, both bracket indexing and equivalent collection methods. Values retain their original 64-bit carrier representation, including decimal signed zero and NaN payload bits.

The checked core records whether the static receiver type is an array with non-reference elements. Only those accesses select the new entry points; the runtime success path requires an unsigned index strictly below the array size. Negative signed indices are represented as large unsigned values and fail that comparison. Invalid accesses continue into the existing rooted `checked_index` exception path, retaining its message, source location and safe unwinding. Null checks stay at the existing entry point.

The successful path makes no calls that allocate or trigger collection, does not expose an interior pointer and does not change roots or array storage layout. The compiler still roots and reloads the receiver across evaluation of allocating index/value expressions. Reference arrays, including text, objects, boxed values and nested arrays, retain the generic checked storage path. Other operation codes and collection kinds retain the generic dispatcher. The added compiler/runtime entry points do not change existing signatures or heap layouts. Ordinary list/map/reference-array dispatch is unchanged.

Validation covers raw scalar carrier bits and GC precision under standalone forced collection and sanitizers, plus Bhash programs exercising all scalar kinds, reference fallbacks, allocating receivers/indices/values, null/bounds failures, source locations and repeated catch/finally unwinding. Native program output is checked against JVM and CLR and shutdown live allocations must be zero.

## Reproduce the performance experiment

Build the reference compiler from commit `af7753b3ecd8108c1ae3cb9992164741f6d72357` and the candidate compiler with Java 17 and the same release toolchain. On Linux with .NET 10, LLVM 18 and GNU time:

```sh
python3 benchmarks/array_access.py --before /absolute/reference.jar --after /absolute/candidate.jar
```

The experiment uses the original benchmark workloads and checksum observer: medium cases for every workload, small/large array sorting, startup and project compilation. It shuffles before/after variants and JVM/CLR/native order on the same host with seed 1729, performs three fresh builds and seven process launches per combination, and warms each sustained run for 1.5 seconds before five 300 ms batches. GC diagnostics run separately from timing.

The output retains raw trial records, compiler/source hashes, tool versions, CPU, RSS, startup, application bytes, build metrics and native GC diagnostics. `comparison.md` reports median throughput/CPU/RSS. Review trial variability and unchanged managed-target controls before attributing changes to this optimization. Hosted-runner results do not establish Windows or macOS speedups. No fixed performance gate is imposed on CI.
