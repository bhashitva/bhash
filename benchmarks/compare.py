#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Produce a durable comparison from recorded measurements; never reruns programs."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def median(entry, key):
    value = entry["summary"].get(key)
    return value["median"] if value else None


def fmt(value, digits=3):
    return "—" if value is None else f"{value:,.{digits}f}"


def render(data, run_url=None):
    config, env = data["configuration"], data["environment"]
    full = config["trials"] >= 7 and config["compile_trials"] >= 3 and config["warmup_ms"] >= 1500 and config["batch_ms"] >= 300 and config["batches"] >= 5
    result = ["# Bhash JVM / .NET / native comparison", "",
              "**Initial performance baseline; no compiler/runtime optimizations.**" if full else "**Conformance/short run only. Do not use these numbers as a performance baseline.**", "",
              f"Compiler SHA-256: `{data['compiler_sha256']}`", "",
              f"Host: {env['platform']} · CPU: {env['cpu']} · logical CPUs: {env['logical_cpus']}", "",
              f"Trials: {config['trials']} process launches per case/target; {config['compile_trials']} fresh-output compilations. "
              f"Each sustained trial warms up for {config['warmup_ms']} ms in the same process, then measures {config['batches']} × {config['batch_ms']} ms batches.", "",
              f"Validated cases: {len(data['results'])}; runtime trials: {sum(len(e['runtime']) for e in data['results'])}; "
              f"compile trials: {sum(len(e['compile']) for e in data['results'])}. Every result passed an independent deterministic checksum.", ""]
    if run_url: result += [f"[Measurement run and downloadable raw artifacts]({run_url})", ""]
    by_key = {(e['name'], e['size'], e['target']): e for e in data['results']}
    targets = ('jvm', 'clr', 'native')
    names = sorted({e['name'] for e in data['results']} - {'startup', 'project'})
    result += ["## Fresh-process startup and deployment", "",
               "Minimal program, measured from launch to exit. Lower is better. Generated payload excludes installed shared runtimes/SDKs.", "",
               "| Target | Startup ms | CPU ms | Peak RSS MiB | Application KiB | Compile s |", "|---|---:|---:|---:|---:|---:|"]
    for target in targets:
        e = by_key['startup', 'minimal', target]
        rss = median(e, 'peak_rss_bytes')
        result.append(f"| {target} | {fmt(median(e, 'wall_s')*1000)} | {fmt(median(e,'cpu_s')*1000)} | {fmt(rss/1048576 if rss else None)} | {fmt(e['application_bytes']/1024,1)} | {fmt(median(e,'compile_wall_s'))} |")
    floor = env.get('observer_launch_wall_s', [])
    if floor:
        result += ["", f"Observer launch floor (`/bin/true`, seven trials): median {statistics.median(floor)*1000:.3f} ms. "
                   "It is recorded, not subtracted. Tiny timing differences and missing/zero kernel RSS readings are inconclusive."]
    result += ["", "## Sustained execution", "", "Calls/s are whole workload invocations, including validation/checksum overhead; "
               "compare targets within the same row/size. CPU/task is process CPU, including runtime threads, from available measured-phase snapshots. "
               "Memory is sampled RSS during measured phases, not live heap. Values are medians across independent process trials.", ""]
    for size in ('small', 'medium', 'large'):
        if not any((name,size,'jvm') in by_key for name in names): continue
        result += [f"### {size.capitalize()} inputs", "", "| Workload | Target | Calls/s ↑ | CPU µs/call ↓ | Measured RSS MiB ↓ | Throughput CV % |", "|---|---|---:|---:|---:|---:|"]
        for name in names:
            for target in targets:
                e = by_key.get((name,size,target))
                if not e: continue
                cpu, rss = median(e,'measured_cpu_s_per_call'), median(e,'measured_rss_median_bytes')
                cv = e['summary']['calls_per_s']['cv_percent']
                result.append(f"| {name} | {target} | {fmt(median(e,'calls_per_s'),1)} | {fmt(cpu*1e6 if cpu is not None else None)} | {fmt(rss/1048576 if rss else None)} | {fmt(cv,1)} |")
    result += ["", "## Native throughput relative to managed targets", "", "Ratios above 1 mean more completed calls per second for native in this experiment. "
               "Ratios below 1 favor the other target. These are ratios of medians, not confidence intervals or causal explanations.", "",
               "| Workload | Size | Native / JVM | Native / .NET |", "|---|---|---:|---:|"]
    for size in ('small','medium','large'):
        for name in names:
            entries = [by_key.get((name,size,t)) for t in targets]
            if not all(entries): continue
            jvm, clr, native = [median(e,'calls_per_s') for e in entries]
            result.append(f"| {name} | {size} | {fmt(native/jvm,2)}× | {fmt(native/clr,2)}× |")
    result += ["", "## Compilation scaling", "", "New output directory per trial; package/filesystem caches are warm. "
               "CPU includes waited-for backend subprocesses. Peak RSS is the largest process high-water value, not simultaneous summed tree memory.", "",
               "| Files | Target | Compile s | Compile CPU s | Compiler peak MiB | Application KiB |", "|---:|---|---:|---:|---:|---:|"]
    for size in ('small','medium','large'):
        for target in targets:
            e = by_key.get(('project',size,target))
            if not e: continue
            rss = median(e,'compile_peak_rss_bytes')
            result.append(f"| {e['files']} | {target} | {fmt(median(e,'compile_wall_s'))} | {fmt(median(e,'compile_cpu_s'))} | {fmt(rss/1048576 if rss else None)} | {fmt(e['application_bytes']/1024,1)} |")
    noisy = [f"{e['name']}/{e['size']}/{e['target']} ({e['summary']['calls_per_s']['cv_percent']:.1f}%)"
             for e in data['results'] if e['summary'].get('calls_per_s') and (e['summary']['calls_per_s']['cv_percent'] or 0) > 10]
    result += ["", "## Interpretation and limits", "",
               "- Shared hosted hardware, runtime heuristics and fixed warm-up windows limit generalization. Repeat on dedicated hardware before accepting an optimization; review per-batch rates for JIT stabilization.",
               "- File tests use buffered warm-cache UTF-8 I/O; no physical-disk/fsync claim is made.",
               "- Overall process CPU/time includes startup and warm-up. Use sustained throughput and measured CPU/task for equivalent useful work.",
               "- JVM uses Java 17-compatible bytecode/default JIT; CLR uses framework-dependent .NET 10 Release/default tiering; native uses LLVM -O2 and the existing collector.",
               "- RSS includes runtime/JIT/stacks/mapped pages. It is not PSS/USS, exclusive heap occupancy, total installation size or allocation rate.",
               "- Native GC counters are in separate untimed diagnostic logs. Comparable allocation bytes and GC pause distributions are not yet measured; forced-GC safety settings are absent from timings.",
               "- Seven trials support descriptive medians, min/max and CV; this report does not invent p95/p99 values or declare statistically significant wins.",
               "- Full source hashes, exact tool versions, launch-floor observations, effective configuration and CPU snapshot coverage accompany the raw data.", "",
               f"Throughput CV >10%: {len(noisy)} cases. " + ("Repeat these observations before drawing conclusions: " + "; ".join(noisy) + "." if noisy else "No cases crossed this descriptive noise flag."), ""]
    return '\n'.join(result)


def write_csv(data, path):
    keys = ('compile_wall_s','compile_cpu_s','compile_peak_rss_bytes','wall_s','cpu_s','peak_rss_bytes',
            'calls_per_s','measured_cpu_s_per_call','measured_rss_median_bytes')
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['name','size','target','application_bytes', *keys])
        for e in data['results']:
            writer.writerow([e['name'],e['size'],e['target'],e['application_bytes'], *[median(e,k) for k in keys]])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--run-url')
    args = parser.parse_args()
    data = json.loads(args.results.read_text(encoding='utf-8'))
    args.output.write_text(render(data,args.run_url), encoding='utf-8')
    write_csv(data,args.output.with_suffix('.csv'))


if __name__ == '__main__': main()
