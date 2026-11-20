#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate shipped compiler bytes on a consumer host without a producer build."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    for row in (args.directory / "SHA256SUMS").read_text().splitlines():
        expected, name = row.split()
        assert hashlib.sha256((args.directory / name).read_bytes()).hexdigest() == expected, name
    jar = (args.directory / "bhash-compiler-0.28.0.jar").resolve()
    command = ["java", "-jar", str(jar)]
    subprocess.run(command + ["ffi", "verify", "compression", "--offline"], check=True)
    sdk, archive, digest = {
        "Windows": ("native-sdk-windows-1.0.0", "bhash-windows-x86_64-llvm18-v1.zip", "b0d5934ea134e500ff598f737c40b3076400e85e5760a22bfcc28f7aaf321f3f"),
        "Darwin": ("native-sdk-macos-1.0.0", "bhash-macos-arm64-llvm18-v1.zip", "c92917d04e8514ed497173326fafa138ba1b9767ac17a0f88b568d6b6ee3e22d"),
        "Linux": ("native-sdk-1.1.0", "bhash-linux-x86_64-llvm18-v1-jammy.zip", "52e8a1c7bd1b71082c0c9d7364776a53f5d7a3b8b87d6501f1cf0894ed09e844"),
    }[platform.system()]
    subprocess.run(command + ["native", "toolchain", "install", "--url",
        f"https://github.com/bhashitva/bhash/releases/download/{sdk}/{archive}", "--sha256", digest], check=True)
    os.environ["BHASH_NATIVE_TOOLCHAIN"] = "managed"
    os.environ["BHASH_GC_STRESS"] = "1"
    os.environ["JAVA_TOOL_OPTIONS"] = (os.environ.get("JAVA_TOOL_OPTIONS", "") + " -Dbhash.ffi.gcStress=true").strip()
    subprocess.run([sys.executable, "scripts/test_ffi_examples.py", str(jar)], check=True)
    for target in ("jvm", "clr", "native"):
        result = subprocess.run(command + ["run", "src/test/ffi/control-flow.bhash", "--target", target],
            check=True, capture_output=True, encoding="utf-8", timeout=180)
        assert result.stdout.replace("\r\n", "\n") == "catch\ncase\n", (target, result.stdout)
    print("Shipped compiler: provider inventory and real JVM/.NET/native applications passed")


if __name__ == "__main__":
    main()
