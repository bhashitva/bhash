#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build pinned providers twice and validate real Bhash consumers on this CI host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*command, **options):
    subprocess.run([str(x) for x in command], check=True, cwd=ROOT, **options)


def fetch(url, file, sha):
    if not file.is_file():
        with urllib.request.urlopen(url, timeout=60) as source, file.open("wb") as output:
            shutil.copyfileobj(source, output)
    if hashlib.sha256(file.read_bytes()).hexdigest() != sha:
        raise SystemExit("Untrusted producer archive: " + str(file))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jupyter", action="store_true")
    args = parser.parse_args()
    work = ROOT / "target/ffi-ci"
    work.mkdir(parents=True, exist_ok=True)
    for name in ("TEMP", "TMP", "TMPDIR"):
        os.environ[name] = str(work)
    windows = platform.system() == "Windows"
    macos = platform.system() == "Darwin"
    java_home = Path(os.environ["JAVA_HOME"])
    java = java_home / ("bin/java.exe" if windows else "bin/java")
    maven = shutil.which("mvn.cmd" if windows else "mvn")
    if not maven:
        raise SystemExit("Maven is required by the CI producer")
    jar = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"
    # Native compiler setup is deliberate; FFI analysis/compilation never invokes it.
    # The CLI deliberately never selects a remote archive implicitly. Use the
    # same immutable SDK assets and trusted digests as the VS Code integration.
    sdk_name, sdk_archive, sdk_sha = (
        ("native-sdk-windows-1.0.0", "bhash-windows-x86_64-llvm18-v1.zip",
         "b0d5934ea134e500ff598f737c40b3076400e85e5760a22bfcc28f7aaf321f3f") if windows else
        ("native-sdk-macos-1.0.0", "bhash-macos-arm64-llvm18-v1.zip",
         "c92917d04e8514ed497173326fafa138ba1b9767ac17a0f88b568d6b6ee3e22d") if macos else
        ("native-sdk-1.1.0", "bhash-linux-x86_64-llvm18-v1-jammy.zip",
         "52e8a1c7bd1b71082c0c9d7364776a53f5d7a3b8b87d6501f1cf0894ed09e844"))
    run(java, "-jar", jar, "native", "toolchain", "install", "--url",
        "https://github.com/bhashitva/bhash/releases/download/" + sdk_name + "/" + sdk_archive,
        "--sha256", sdk_sha, "--json")
    os.environ["BHASH_NATIVE_TOOLCHAIN"] = "managed"
    cc, ar = shutil.which("clang"), shutil.which("ar")
    if windows:
        from build_windows_toolchain import URL, SHA256, UPSTREAM
        archive = work / "llvm-mingw.zip"
        fetch(URL, archive, SHA256)
        upstream = work / UPSTREAM
        if not upstream.exists():
            with zipfile.ZipFile(archive) as source:
                for name in source.namelist():
                    if Path(name).is_absolute() or ".." in Path(name).parts:
                        raise SystemExit("Unexpected producer archive path")
                source.extractall(work)
        cc, ar = upstream / "bin/clang.exe", upstream / "bin/llvm-ar.exe"
    if not cc or not ar:
        raise SystemExit("A C17 compiler and deterministic archiver are required by the producer")
    archive = work / "zlib-1.4.tar.gz"
    from build_ffi import ZLIB_SHA256
    fetch("https://zlib.net/zlib-1.4.tar.gz", archive, ZLIB_SHA256)
    prepared = work / "prepared"
    run(java, "-jar", jar, "ffi", "prepare", "--builtin", "compression", "-o", prepared)
    for name in ("first", "second"):
        run(shutil.which("python3") or shutil.which("python"), "scripts/build_ffi.py", "--archive", archive,
            "--cc", cc, "--ar", ar, "--output", work / name, "--bindings", prepared,
            "--java-home", java_home, "--shared", "--test", "--catalog", work / (name + "-catalog"))
    first = json.loads((work / "first-catalog/compression-artifacts.json").read_text())
    second = json.loads((work / "second-catalog/compression-artifacts.json").read_text())
    if first != second:
        raise SystemExit("Provider output is not reproducible across build directories")
    destination = ROOT / "target/ffi-catalog"
    shutil.copytree(work / "first-catalog", destination, dirs_exist_ok=True)
    run(maven, "--batch-mode", "--no-transfer-progress", "-DskipTests", "package")
    run(java, "-jar", jar, "ffi", "verify", "compression", "--offline")
    run(maven, "--batch-mode", "--no-transfer-progress", "-Dbhash.ffi.cc=" + str(cc), "-Dbhash.ffi.ar=" + str(ar),
        "-Dbhash.ffi.archive=" + str(work / "first/libbhash_ffi.a"), "-Dtest=Foreign*Test", "test")
    shared = work / "first" / ("bhash_ffi.dll" if windows else "libbhash_ffi.dylib" if macos else "libbhash_ffi.so")
    run(shutil.which("python3") or shutil.which("python"), "scripts/ffi_wire_test.py", shared)
    os.environ["BHASH_GC_STRESS"] = "1"
    run(shutil.which("python3") or shutil.which("python"),"scripts/test_ffi_examples.py",jar,"--java",java)
    consumer = work / "consumer"
    consumer.mkdir(exist_ok=True)
    outputs = {"jvm": consumer / "app.jar", "clr": consumer / "clr", "native": consumer / ("program.exe" if windows else "program")}
    for target, output in outputs.items():
        run(java, "-jar", jar, "compile", "tutorial/compression/roundtrip.bhash", "--target", target, "-o", output)
    # Run through absolute runtime paths with no tool lookup available. Linux CI
    # additionally executes these artifacts in runtime-only containers below.
    environment = os.environ.copy()
    environment["PATH"] = ""
    for command in ((java, "-jar", outputs["jvm"]), (shutil.which("dotnet"), outputs["clr"] / "BhashProgram.dll"), (outputs["native"],)):
        result = subprocess.run([str(x) for x in command], env=environment, capture_output=True, check=True, encoding="utf-8")
        if result.stdout.replace("\r\n", "\n") != "नमस्ते भारत 😀\n":
            raise SystemExit("Runtime-only consumer output mismatch: " + result.stdout)
    if args.jupyter:
        python=shutil.which("python3") or shutil.which("python")
        run(python,"scripts/package_jupyter.py")
        wheels=list((ROOT/"target/jupyter-dist").glob("*.whl"))
        if len(wheels)!=1:
            raise SystemExit("Expected exactly one freshly built kernel wheel")
        run(python,"-m","pip","install","--force-reinstall","--no-deps",wheels[0])
        run(python,"-m","bhash_jupyter.install","--java",java)
        run(shutil.which("python3") or shutil.which("python"), "scripts/test_ffi_worker.py", jar, "--java", java)
        run(python, "editors/jupyter/test_kernel.py", "--installed", "--ffi")


if __name__ == "__main__":
    main()
