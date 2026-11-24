#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify identical, complete provider payloads before staging public packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMPILER, KERNEL = "0.30.0", "0.1.17"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--providers", type=Path, required=True)
    args = parser.parse_args()
    out = ROOT / "target/platform-release"
    out.mkdir(parents=True, exist_ok=True)
    jar = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"
    vsix = ROOT / f"target/bhash-vscode-{COMPILER}.vsix"
    wheel = ROOT / f"target/jupyter-dist/bhash_jupyter-{KERNEL}-py3-none-any.whl"
    payload = jar.read_bytes()
    with zipfile.ZipFile(jar) as compiler, zipfile.ZipFile(args.providers) as providers:
        catalog = json.loads(compiler.read("dev/bhash/ffi/compression-artifacts.json"))
        assert catalog == json.loads(providers.read("compression-artifacts.json"))
        assert {(x["target"], x["kind"]) for x in catalog["artifacts"]} == {
            (host, kind) for host in ("linux-x64", "windows-x64", "macos-arm64")
            for kind in ("static", "shared")}
        for item in catalog["artifacts"]:
            path = "artifacts/" + item["path"]
            data = compiler.read("dev/bhash/ffi/" + path)
            assert data == providers.read(path) and hashlib.sha256(data).hexdigest() == item["sha256"]
            assert compiler.read("dev/bhash/ffi/artifacts/" + item["license"])
    with zipfile.ZipFile(vsix) as package:
        assert package.read("extension/server/bhash-compiler.jar") == payload
        assert json.loads(package.read("extension/package.json"))["version"] == COMPILER
        overview = package.read("extension/readme.md").decode("utf-8")
        assert f"What's new in {COMPILER}" in overview and "package search" in overview
    with zipfile.ZipFile(wheel) as package:
        assert package.read("bhash_jupyter/assets/bhash-compiler.jar") == payload
        assert f"Version: {KERNEL}" in package.read(f"bhash_jupyter-{KERNEL}.dist-info/METADATA").decode()
        assert package.read("bhash_jupyter/assets/compression.ipynb") == (ROOT / "editors/jupyter/compression.ipynb").read_bytes()
    files = {f"bhash-compiler-{COMPILER}.jar": jar, vsix.name: vsix, wheel.name: wheel,
             "hindi-starter.ipynb": ROOT / "editors/jupyter/पहली-नोटबुक.ipynb",
             "compression.ipynb": ROOT / "editors/jupyter/compression.ipynb"}
    provenance = {"compiler_version": COMPILER, "kernel_version": KERNEL,
                  "source_revision": os.environ.get("GITHUB_SHA", "local"),
                  "compiler_sha256": hashlib.sha256(payload).hexdigest()}
    (out / "release-info.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    files["release-info.json"] = out / "release-info.json"
    rows = []
    for name, source in files.items():
        if source.resolve() != (out / name).resolve():
            shutil.copyfile(source, out / name)
        row = hashlib.sha256((out / name).read_bytes()).hexdigest() + "  " + name + "\n"
        rows.append(row)
        if name.endswith((".jar", ".vsix")):
            (out / (name + ".sha256")).write_text(row, encoding="ascii")
    (out / "SHA256SUMS").write_text("".join(rows), encoding="ascii")
    print("Verified identical compiler payloads, six provider artifacts, notices and notebooks")


if __name__ == "__main__":
    main()
