#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Linux/WSL cross-target baseline. Standard library only; never tunes the host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import signal
import statistics
import subprocess
import sys
import threading
import time

from workloads import SIZES, checksum, generate

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def clean_env():
    env = os.environ.copy()
    # No injected runtime flags, diagnostics, forced collection or build servers.
    for key in list(env):
        if key.startswith(("BHASH_GC_", "COMPlus_", "DOTNET_GC", "DOTNET_EnableDiagnostics")) or key in (
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH"):
            del env[key]
    env.update(DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", DOTNET_CLI_USE_MSBUILD_SERVER="0",
               MSBUILDDISABLENODEREUSE="1", LANG="C.UTF-8", LC_ALL="C.UTF-8")
    return env


def proc_sample(pid):
    """Main-process RSS and CPU include runtime threads; no shared-memory sums."""
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return {"rss_bytes": int(fields[21]) * os.sysconf("SC_PAGE_SIZE"),
                "cpu_s": (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")}
    except (FileNotFoundError, ProcessLookupError):
        return None


def measure(command, cwd, env, timeout, log):
    """wait4 accounts for reaped compiler children; no competing Popen.poll()."""
    lines, samples, errors = [], [], []
    start = time.perf_counter()
    process = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=True)

    def read_stdout():
        for line in process.stdout:
            lines.append({"at_s": time.perf_counter() - start, "text": line.decode("utf-8", "strict").strip(),
                          "process": proc_sample(process.pid)})

    def read_stderr():
        errors.append(process.stderr.read())

    threads = [threading.Thread(target=read_stdout), threading.Thread(target=read_stderr)]
    for thread in threads: thread.start()
    finished = threading.Event()
    waited = []

    def reap():
        waited.append((*os.wait4(process.pid, 0), time.perf_counter()))
        finished.set()

    waiter = threading.Thread(target=reap)
    waiter.start()
    timed_out = False
    while not finished.is_set():
        sample = proc_sample(process.pid)
        if sample: samples.append({"at_s": time.perf_counter() - start, **sample})
        if time.perf_counter() - start > timeout:
            timed_out = True
            os.killpg(process.pid, signal.SIGKILL)
            break
        finished.wait(.01)
    waiter.join()
    _, status, usage, ended = waited[0]
    elapsed = ended - start
    process.returncode = os.waitstatus_to_exitcode(status)
    for thread in threads: thread.join(timeout=10)
    process.stdout.close(); process.stderr.close()
    record = {"command": command, "cwd": str(cwd), "wall_s": elapsed,
              "cpu_s": usage.ru_utime + usage.ru_stime, "peak_rss_bytes": usage.ru_maxrss * 1024,
              "exit_code": process.returncode, "timed_out": timed_out,
              "stdout": lines, "stderr": b"".join(errors).decode("utf-8", "replace"), "samples": samples}
    log.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    if timed_out or process.returncode:
        raise RuntimeError(f"Command failed/timeout; see {log}")
    return record


def validate(case, record, batches):
    lines = record["stdout"]
    if "expected" in case:
        if len(lines) != 1 or lines[0]["text"].split() != ["BHASH_OK", str(case["expected"])]:
            # Python accepts Devanagari decimal digits; never normalize arbitrary text.
            if len(lines) != 1 or lines[0]["text"].split()[0] != "BHASH_OK" or int(lines[0]["text"].split()[1]) != case["expected"]:
                raise ValueError("Incorrect startup/project result")
        return {}
    if not lines or lines[0]["text"] != "BHASH_READY" or len(lines) != 2 + batches * 3:
        raise ValueError("Incomplete benchmark protocol")

    def result(line):
        fields = line["text"].split()
        if len(fields) != 3 or fields[0] != "BHASH_RESULT": raise ValueError("Invalid result")
        calls, value = map(int, fields[1:])
        if calls < 1 or value != checksum(case["name"], case["n"], calls): raise ValueError("Checksum mismatch")
        return calls

    result(lines[1])  # Warm-up correctness, excluded from throughput.
    phases = []
    for batch in range(batches):
        begin, value, end = lines[2 + batch * 3:5 + batch * 3]
        for line, marker in ((begin, "BHASH_BEGIN"), (end, "BHASH_END")):
            fields = line["text"].split()
            if len(fields) != 2 or fields[0] != marker or int(fields[1]) != batch: raise ValueError("Invalid phase")
        duration = end["at_s"] - begin["at_s"]
        if duration <= 0: raise ValueError("Invalid phase duration")
        memory = [s["rss_bytes"] for s in record["samples"] if begin["at_s"] <= s["at_s"] <= end["at_s"]]
        a, b = begin["process"], end["process"]
        phases.append({"calls": result(value), "wall_s": duration,
                       "cpu_s": b["cpu_s"] - a["cpu_s"] if a and b else None,
                       "rss_median_bytes": statistics.median(memory) if memory else None})
    total_calls = sum(p["calls"] for p in phases)
    total_time = sum(p["wall_s"] for p in phases)
    cpus = [p["cpu_s"] for p in phases]
    rss = [p["rss_median_bytes"] for p in phases if p["rss_median_bytes"] is not None]
    return {"ready_s": lines[0]["at_s"], "calls_per_s": total_calls / total_time,
            "measured_cpu_s_per_call": sum(cpus) / total_calls if all(c is not None for c in cpus) else None,
            "measured_rss_median_bytes": statistics.median(rss) if rss else None, "phases": phases}


def summary(values):
    values = [x for x in values if x is not None]
    if not values: return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values),
            "cv_percent": statistics.stdev(values) / statistics.mean(values) * 100 if len(values) > 1 and statistics.mean(values) else None,
            "samples": len(values)}


def report(data, path):
    rows = ["# Bhash performance baseline", "", f"Compiler SHA-256: `{data['compiler_sha256']}`", "",
            f"Host: {data['environment']['platform']}; CPU: {data['environment']['cpu']}", "",
            "All checksums passed. No compiler/runtime optimizations were made. "
            "Values are medians of fresh-process trials; min/max/CV and all raw samples are in results.json and logs/.", "",
            "Hosted runners share hardware: these are an initial observation, not release gates or a ranking of languages. "
            "CPU includes runtime threads; peak RSS is OS high-water memory. Measured-phase RSS is sampled resident memory, not live heap.", "",
            "| Workload | Size | Target | Compile s | Compiler peak MiB* | App KiB | Runtime s | CPU s | Peak MiB | Measured MiB | Calls/s |",
            "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    def number(item, scale=1): return "—" if item is None else f"{item['median'] / scale:.3f}"
    for entry in data["results"]:
        s = entry["summary"]
        rows.append("| " + " | ".join([entry["name"], entry["size"], entry["target"], number(s["compile_wall_s"]),
            number(s["compile_peak_rss_bytes"], 1048576), f"{entry['application_bytes']/1024:.1f}",
            number(s["wall_s"]), number(s["cpu_s"]), number(s["peak_rss_bytes"], 1048576),
            number(s["measured_rss_median_bytes"], 1048576), number(s["calls_per_s"])]) + " |")
    rows += ["", "*Compiler RSS includes the maximum high-water RSS of the compiler or a waited-for child, "
             "not simultaneous summed process-tree memory. CPU time includes waited-for children. "
             "Compile trials use new output paths and warmed installed dependency caches; they are not cold-cache SDK installs.", "",
             "Runtime s/CPU s include startup, warm-up and measured phases. Compare calls/s and measured CPU seconds per call "
             "for sustained work; minimal startup has no warm-up. File I/O measures buffered OS-cache performance, not physical disk/fsync.", "",
             "Application bytes are generated deployment files, excluding the shared JVM/.NET runtime and developer SDK. "
             "Runtime/SDK requirements and exact versions are recorded in environment.json; do not interpret this as total installation size.", "",
             "GC allocation count/collection count/peak charged bytes are collected in separate native diagnostic runs. "
             "Cross-runtime allocation-byte totals and GC pause timings are unavailable in this initial suite; they are not inferred from RSS.", "",
             "See benchmarks/README.md for methodology, limitations and reproduction. No optimization recommendation should be based on one noisy workload."]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, default=ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "target/benchmarks")
    parser.add_argument("--trials", type=int, default=7)
    parser.add_argument("--compile-trials", type=int, default=3)
    parser.add_argument("--sizes", nargs="+", choices=("small", "medium", "large"), default=["small", "medium", "large"])
    parser.add_argument("--workloads", nargs="+", choices=list(SIZES))
    parser.add_argument("--warmup-ms", type=int, default=1500)
    parser.add_argument("--batch-ms", type=int, default=300)
    parser.add_argument("--batches", type=int, default=5)
    parser.add_argument("--seed", type=int, default=1729)
    parser.add_argument("--generate-only", action="store_true")
    args = parser.parse_args()
    if min(args.trials, args.compile_trials, args.warmup_ms, args.batch_ms, args.batches) < 1: parser.error("Counts/durations must be positive")
    out = args.output.resolve()
    if out.exists(): parser.error("Output directory must be new; preserve prior baselines or choose another --output")
    out.mkdir(parents=True)
    cases = generate(out / "sources", args.sizes, args.warmup_ms, args.batch_ms, args.batches, args.workloads)
    (out / "cases.json").write_text(json.dumps(cases, indent=2), encoding="utf-8")
    if args.generate_only: return
    if sys.platform != "linux": parser.error("Timing/memory collection currently requires Linux or WSL; use --generate-only elsewhere")
    compiler = args.compiler.resolve()
    env = clean_env()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    dotnet = env.get("BHASH_DOTNET", "dotnet")
    logs = out / "logs"; logs.mkdir()

    def version(command):
        p = subprocess.run(command, env=env, capture_output=True, text=True, timeout=60)
        if p.returncode: raise RuntimeError(f"Toolchain unavailable: {command}: {p.stderr}")
        return p.stdout + p.stderr

    environment = {"platform": platform.platform(), "cpu": next((s.split(":", 1)[1].strip() for s in Path('/proc/cpuinfo').read_text().splitlines() if s.startswith('model name')), "unknown"),
        "logical_cpus": os.cpu_count(), "memory": Path('/proc/meminfo').read_text(), "java": version([java, "-version"]),
        "dotnet": version([dotnet, "--info"]), "native_doctor": version([java, "-jar", str(compiler), "native", "doctor", "--json"]),
        "python": sys.version, "clock": str(time.get_clock_info("perf_counter")),
        "relevant_env": {k: v for k, v in env.items() if k.startswith(("BHASH_", "DOTNET_", "MSBUILD"))},
        "runner": {k: os.environ.get(k) for k in ("GITHUB_SHA", "GITHUB_RUN_ID", "RUNNER_OS", "RUNNER_ARCH")},
        "removed_env_names": sorted(set(os.environ) - set(env)), "runtime_dependencies": "JVM: installed Java; CLR: installed .NET 10; native: system libc/libm, LLVM is build-only"}
    (out / "environment.json").write_text(json.dumps(environment, indent=2), encoding="utf-8")
    data = {"schema": 1, "compiler_sha256": sha(compiler), "environment": environment,
            "configuration": vars(args) | {"compiler": str(compiler), "output": str(out)},
            "source_sha256": {str(p.relative_to(out)): sha(p) for p in (out / "sources").rglob("*") if p.is_file()}, "results": []}
    rng = random.Random(args.seed)
    entries = []
    for case in cases:
        for target in ("jvm", "clr", "native"):
            entries.append({**case, "target": target, "compile": [], "runtime": []})
    # Every compile creates new destination files; tool/dependency provisioning is excluded.
    for trial in range(args.compile_trials):
        order = entries.copy(); rng.shuffle(order)
        for entry in order:
            key = f"{entry['name']}-{entry['size']}-{entry['target']}"
            directory = out / "build" / key / str(trial); directory.mkdir(parents=True)
            artifact = directory / ("app.jar" if entry["target"] == "jvm" else "app")
            record = measure([java, "-jar", str(compiler), "compile", str(out / "sources" / entry["source"]), "--target", entry["target"], "-o", str(artifact)],
                             out, env, 300, logs / f"{key}-compile-{trial}.json")
            entry["compile"].append({k: record[k] for k in ("wall_s", "cpu_s", "peak_rss_bytes")})
            entry["artifact"] = artifact
            entry["application_bytes"] = sum(p.stat().st_size for p in directory.rglob("*") if p.is_file())
            print(f"compiled {key} {trial + 1}/{args.compile_trials}", flush=True)
    # Interleave target/workload order per trial; never time compiler-mediated 'run'.
    for trial in range(args.trials):
        order = entries.copy(); rng.shuffle(order)
        for entry in order:
            key = f"{entry['name']}-{entry['size']}-{entry['target']}"
            cwd = out / "runs" / key / str(trial); cwd.mkdir(parents=True)
            artifact = str(entry["artifact"])
            command = [java, "-jar", artifact] if entry["target"] == "jvm" else [dotnet, str(entry["artifact"] / "BhashProgram.dll")] if entry["target"] == "clr" else [artifact]
            record = measure(command, cwd, env, 180, logs / f"{key}-run-{trial}.json")
            metrics = validate(entry, record, args.batches)
            entry["runtime"].append({k: record[k] for k in ("wall_s", "cpu_s", "peak_rss_bytes")} | metrics)
            print(f"validated {key} {trial + 1}/{args.trials}", flush=True)
    for entry in entries:
        entry["artifact"] = str(entry["artifact"].relative_to(out))
        entry["summary"] = {key: summary([r.get(key) for r in entry["runtime"]]) for key in (
            "wall_s", "cpu_s", "peak_rss_bytes", "ready_s", "calls_per_s", "measured_cpu_s_per_call", "measured_rss_median_bytes")}
        entry["summary"].update({"compile_" + key: summary([r[key] for r in entry["compile"]]) for key in ("wall_s", "cpu_s", "peak_rss_bytes")})
        data["results"].append(entry)
    # Diagnostic collection is deliberately outside the timed trials.
    for entry in entries:
        if entry["target"] != "native" or "expected" in entry: continue
        key = f"{entry['name']}-{entry['size']}-native"
        diagnostic_env = env | {"BHASH_GC_STATS": "1"}
        record = measure([str(out / entry["artifact"])], out / "runs" / key / "0", diagnostic_env, 180, logs / f"{key}-gc.json")
        validate(entry, record, args.batches)
        entry["native_gc_stats"] = record["stderr"]
    (out / "results.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    report(data, out / "report.md")
    print(out / "report.md")


if __name__ == "__main__": main()
