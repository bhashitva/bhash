#!/usr/bin/env python3
"""Test the public C ABI and task store on Linux x86-64 with forced GC."""
import argparse
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/native-embedding"
TASKS = ROOT / "examples/android-offline-task-list"
RUNTIME_EXPORTS = {"bhash_embed_abi_version", "bhash_runtime_start", "bhash_runtime_stop",
                   "bhash_bytes_free", "bhash_handle_release"}


def run(*command, **options):
    return subprocess.run(command, cwd=ROOT, check=True, text=True, encoding="utf-8", **options)


def build(jar, temp, name, source, manifest, harness):
    output = temp / name
    run("java", "-jar", jar, "native", "library", source, "--exports", manifest, "-o", output)
    symbols = run("nm", "-D", "--defined-only", output / "libbhash_app.so", capture_output=True).stdout
    wanted = RUNTIME_EXPORTS | {line.split("=")[0].strip() for line in manifest.read_text(encoding="utf-8").splitlines()}
    actual = {line.split()[-1] for line in symbols.splitlines() if line.strip()}
    if wanted != actual:
        raise AssertionError(f"Unexpected ELF exports: expected {wanted}, found {actual}")
    exe = temp / (name + "-test")
    run(os.environ.get("CC", "clang-18"), "-std=c17", "-Wall", "-Wextra", "-Werror", "-I", output,
        harness, "-L", output, "-lbhash_app", "-Wl,-rpath," + str(output), "-pthread", "-o", exe)
    return exe


def exercise(exe, *args):
    result = subprocess.run((exe, *args), cwd=ROOT,
                            env={**os.environ, "BHASH_GC_STRESS": "1", "BHASH_GC_STATS": "1"},
                            capture_output=True, text=True, encoding="utf-8")
    print(result.stdout + result.stderr, end="")
    result.check_returncode()
    if not re.search(r"collections=[1-9]\d* live=0", result.stderr):
        raise AssertionError("Expected forced collections and an empty heap at shutdown")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")
    jar = parser.parse_args().jar.resolve()
    with tempfile.TemporaryDirectory(prefix="bhash-embedding-") as workspace:
        temp = Path(workspace)
        boundary = build(jar, temp, "boundary", FIXTURES / "library.bhash", FIXTURES / "bhash.exports",
                         FIXTURES / "harness.c")
        exercise(boundary)
        tasklist = build(jar, temp, "tasks", TASKS / "bhash.project", TASKS / "bhash.exports",
                         FIXTURES / "task-list.c")
        exercise(tasklist, "create", temp)
        exercise(tasklist, "reopen", temp)
        with sqlite3.connect(temp / "tasks.db") as db:
            assert db.execute("PRAGMA user_version").fetchone() == (1,)
            assert db.execute("SELECT count(*) FROM tasks").fetchone() == (2,)
            db.execute("PRAGMA user_version=2")
        exercise(tasklist, "reject", temp)
        with sqlite3.connect(temp / "tasks.db") as db:
            assert db.execute("PRAGMA user_version").fetchone() == (2,)
            assert db.execute("SELECT count(*) FROM tasks").fetchone() == (2,)


if __name__ == "__main__":
    main()
