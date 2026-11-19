#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check identical compiler payloads and stage the null-safety release assets."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "target/null-safety-release"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    jar = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"
    vsix = ROOT / "target/bhash-vscode-0.26.0.vsix"
    wheel = ROOT / "target/jupyter-dist/bhash_jupyter-0.1.12-py3-none-any.whl"
    payload = jar.read_bytes()
    with zipfile.ZipFile(vsix) as package:
        assert package.read("extension/server/bhash-compiler.jar") == payload
        assert json.loads(package.read("extension/package.json"))["version"] == "0.26.0"
        overview = package.read("extension/readme.md").decode("utf-8")
        assert "What's new in 0.26.0" in overview and "nullSafety=strict" in overview
    with zipfile.ZipFile(wheel) as package:
        assert package.read("bhash_jupyter/assets/bhash-compiler.jar") == payload
        metadata = package.read("bhash_jupyter-0.1.12.dist-info/METADATA").decode()
        assert "Version: 0.1.12" in metadata
    files = {
        "bhash-compiler-0.26.0.jar": jar,
        vsix.name: vsix,
        wheel.name: wheel,
        "hindi-starter.ipynb": ROOT / "editors/jupyter/पहली-नोटबुक.ipynb",
    }
    rows = []
    for name, source in files.items():
        shutil.copyfile(source, OUT / name)
        row = f"{hashlib.sha256((OUT / name).read_bytes()).hexdigest()}  {name}\n"
        rows.append(row)
        if name.endswith((".jar", ".vsix")):
            (OUT / (name + ".sha256")).write_text(row, encoding="utf-8")
    (OUT / "SHA256SUMS").write_text("".join(rows), encoding="utf-8")
    print("Verified compiler, VSIX and wheel payloads; staged", OUT)


if __name__ == "__main__":
    main()
