# SPDX-License-Identifier: Apache-2.0
"""Compile every lesson unit and verify published example/solution transcripts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def invoke(command, text=""):
    result = subprocess.run(command, cwd=ROOT, input=text, encoding="utf-8",
                            capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(f"{command}\n{result.stdout}\n{result.stderr}")
    return result.stdout.replace("\r\n", "\n"), result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["jvm", "clr"], default="jvm")
    args = parser.parse_args()
    lessons = ROOT / "lessons"
    catalog = json.loads((lessons / "catalog.json").read_text(encoding="utf-8"))
    compiler = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"
    if not compiler.is_file():
        parser.error("Build the compiler first: mvn package -DskipTests")
    covered = set()
    units = checks = 0
    with tempfile.TemporaryDirectory(prefix="bhash-lessons-") as temporary:
        for lesson in catalog:
            directory = lessons / lesson["path"]
            for role in ("example", "challenge", "solution"):
                source = directory / (role + ".bhash")
                if source.is_file():
                    covered.add(source)
                else:
                    source = directory / role
                    if not (source / "bhash.project").is_file():
                        raise AssertionError(f"Missing lesson unit: {source}")
                    covered.update(source.rglob("*.bhash"))
                output = Path(temporary) / f"unit-{units}{'.jar' if args.target == 'jvm' else ''}"
                invoke(["java", "-jar", str(compiler), "compile", str(source),
                        "--target", args.target, "-o", str(output)])
                units += 1
                # Starters are intentionally unfinished, but must still compile.
                cases = [lesson["example"]] if role == "example" else lesson["cases"] if role == "solution" else []
                command = (["java", "-Xverify:all", "-jar", str(output)] if args.target == "jvm"
                           else [os.environ.get("BHASH_DOTNET", "dotnet"), str(output / "BhashProgram.dll")])
                for case in cases:
                    actual, errors = invoke(command, case["input"])
                    if actual != case["output"] or errors:
                        raise AssertionError(f"{source} input={case['input']!r}\n"
                                             f"Expected {case['output']!r}, got {actual!r}; stderr={errors!r}")
                    checks += 1
                print(f"PASS {args.target}: {lesson['path']}/{role}", flush=True)
    missing = set(lessons.rglob("*.bhash")) - covered
    if missing:
        raise AssertionError(f"Lesson sources without coverage: {sorted(missing)}")
    print(f"PASS: {units} lesson units compiled; {checks} transcripts checked ({args.target}).")


if __name__ == "__main__":
    main()
