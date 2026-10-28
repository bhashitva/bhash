#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build every example application into separate JVM, .NET and native trees."""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {"jvm": "jvm", "clr": "dotnet", "native": "native"}


@dataclass(frozen=True)
class Example:
    name: str
    source: Path
    files: tuple
    library: bool = False


def discover(examples):
    """A project is one compilation unit; never compile its helper files alone."""
    ignored = {".bhash", ".git", "target", "build", "node_modules"}
    def inputs(pattern):
        return sorted(p for p in examples.rglob(pattern) if not (set(p.relative_to(examples).parts) & ignored))
    manifests = inputs("bhash.project")
    projects = {p.parent: p for p in manifests}
    members = {p: [] for p in projects}
    standalone = []
    for source in inputs("*.bhash"):
        owner = next((p for p in source.parents if p in projects), None)
        if owner is None:
            standalone.append(Example(source.relative_to(examples).with_suffix("").as_posix(), source, (source,)))
        else:
            members[owner].append(source)
    units = list(standalone)
    for directory, manifest in projects.items():
        library = bool(re.search(r"^\s*kind\s*[:=]\s*library\s*$", manifest.read_text(encoding="utf-8"), re.MULTILINE))
        units.append(Example(directory.relative_to(examples).as_posix(), manifest, tuple(members[directory]), library))
    units.sort(key=lambda u: u.name)
    names = set()
    for unit in units:
        key = unit.name.casefold()
        if any(key == n or key.startswith(n + "/") or n.startswith(key + "/") for n in names):
            raise ValueError(f"Example output name collision: {unit.name}")
        names.add(key)
    if not units:
        raise ValueError(f"No examples found in {examples}")
    return units


def artifact_path(output, target, name):
    path = output / TARGETS[target] / name
    return path.with_name(path.name + ".jar") if target == "jvm" else path


def native_unavailable():
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        return "native compilation requires Linux x86-64"
    for variable, default in (("BHASH_CLANG", "clang-18"), ("BHASH_LLVM_OPT", "opt-18")):
        tool = os.environ.get(variable) or default
        if not shutil.which(tool):
            return f"missing LLVM 18 tool: {tool}"
        result = subprocess.run([tool, "--version"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)
        if result.returncode or not re.search(r"\bversion\s+18\.", result.stdout + result.stderr):
            return f"incompatible LLVM tool (version 18 required): {tool}"
    return None


def unsupported_native(returncode, diagnostics):
    codes = re.findall(r"\[(BH\d{4})\]", diagnostics)
    return returncode == 1 and bool(codes) and set(codes) == {"BH9001"}


def relative(path, base):
    try:
        return path.relative_to(base).as_posix()
    except ValueError:
        return str(path)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", action="append", choices=list(TARGETS), help="Repeat to select targets; default: all three")
    parser.add_argument("--compiler", type=Path, default=ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--examples", type=Path, default=ROOT / "examples")
    parser.add_argument("--output-root", type=Path, default=ROOT / "target/examples")
    parser.add_argument("--require-native", action="store_true", help="Fail if the native platform/toolchain is unavailable")
    args = parser.parse_args(argv)
    targets = list(dict.fromkeys(args.target or TARGETS))
    if args.require_native and "native" not in targets:
        parser.error("--require-native needs the native target")
    compiler, examples, output = (p.resolve() for p in (args.compiler, args.examples, args.output_root))
    if not compiler.is_file():
        parser.error("Build the compiler first: mvn package -DskipTests")
    if output == examples or examples in output.parents or output in examples.parents:
        parser.error("The output tree must be separate from the example sources")
    try:
        units = discover(examples)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    if not shutil.which(java):
        parser.error(f"Java not found: {java}")
    unavailable = {}
    if "clr" in targets and not shutil.which(os.environ.get("BHASH_DOTNET") or "dotnet"):
        unavailable["clr"] = "missing .NET 10 SDK (dotnet executable not found)"
    if "native" in targets:
        try:
            reason = native_unavailable()
        except (OSError, subprocess.TimeoutExpired) as error:
            reason = f"cannot inspect LLVM toolchain: {error}"
        if reason:
            unavailable["native"] = reason
    output.mkdir(parents=True, exist_ok=True)
    temporary = output / ".tmp"
    temporary.mkdir(exist_ok=True)
    for target in targets:
        (output / TARGETS[target]).mkdir(exist_ok=True)
    report = {"examples_root": relative(examples, ROOT), "output_root": relative(output, ROOT),
              "source_count": sum(len(u.files) for u in units), "targets": targets, "results": []}
    failures = 0
    for target in targets:
        reason = unavailable.get(target)
        if reason and (target != "native" or args.require_native):
            failures += 1
        for unit in units:
            entry = {"target": target, "name": unit.name, "source": relative(unit.source, ROOT),
                     "sources": [relative(p, ROOT) for p in unit.files]}
            if unit.library:
                entry.update(status="library", reason="library package (application dependency); no application entry point")
            elif reason:
                entry.update(status="unavailable", reason=reason)
            else:
                destination = artifact_path(output, target, unit.name)
                destination.parent.mkdir(parents=True, exist_ok=True)
                log = output / "logs" / TARGETS[target] / (unit.name + ".log")
                log.parent.mkdir(parents=True, exist_ok=True)
                command = [java, "-Djava.io.tmpdir=" + str(temporary), "-jar", str(compiler), "compile", str(unit.source), "--target", target, "-o", str(destination)]
                try:
                    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                            encoding="utf-8", errors="replace", timeout=180)
                    diagnostics = result.stdout + result.stderr
                    log.write_text(diagnostics, encoding="utf-8")
                    entry["log"] = relative(log, output)
                    if result.returncode == 0:
                        expected = destination / "BhashProgram.dll" if target == "clr" else destination
                        if not expected.is_file():
                            entry.update(status="failed", reason="compiler reported success without the expected artifact")
                        else:
                            entry.update(status="built", artifact=relative(destination, output))
                    elif target == "native" and not unit.name.startswith("native/") and unsupported_native(result.returncode, diagnostics):
                        entry.update(status="unsupported", reason="outside the native language profile (BH9001)")
                    else:
                        entry.update(status="failed", reason=f"compiler exited with status {result.returncode}")
                except (OSError, subprocess.TimeoutExpired) as error:
                    log.write_text(str(error), encoding="utf-8")
                    entry.update(status="failed", reason=str(error), log=relative(log, output))
                if entry["status"] == "failed":
                    failures += 1
            report["results"].append(entry)
            print(f"{entry['status'].upper()} {target}: {unit.name}" + (f" — {entry['reason']}" if "reason" in entry else ""), flush=True)
    report["summary"] = {status: sum(e["status"] == status for e in report["results"])
                         for status in ("built", "unsupported", "unavailable", "library", "failed")}
    report["success"] = failures == 0
    (output / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Build report: {output / 'build-report.json'}", flush=True)
    print(f"Covered {report['source_count']} sources; {report['summary']}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
