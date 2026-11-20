#!/usr/bin/env python3
"""Check that the wheel installs the offline UI through standard Jupyter data files."""
from pathlib import Path
import json
import re
import hashlib
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r'^version\s*=\s*"([^"]+)"', (root / "editors/jupyter/pyproject.toml").read_text(), re.M)[1]
wheels = list((root / "target/jupyter-dist").glob(f"bhash_jupyter-{version}-*.whl"))
assert len(wheels) == 1, "Expected exactly one current kernel wheel"
with zipfile.ZipFile(wheels[0]) as wheel:
    base = next(name.rsplit("/", 1)[0] for name in wheel.namelist()
                if name.endswith("share/jupyter/labextensions/@bhashitva/library-lookup/package.json"))
    manifest = json.loads(wheel.read(base + "/package.json"))
    assert manifest["version"] == version
    remote = manifest["jupyterlab"]["_build"]["load"]
    assert remote.startswith("static/") and "\\" not in remote
    assert wheel.read(base + "/" + remote)
    assert wheel.read(base + "/install.json")
    for package in ("webpack", "esbuild"):
        assert wheel.read(base + f"/static/{package}-LICENSE.txt")
    scripts = b"\n".join(wheel.read(name) for name in wheel.namelist() if name.startswith(base + "/static/") and name.endswith(".js"))
    # esbuild emits ASCII Unicode escapes; verify actual catalog names, not
    # markup tokens that could survive even when the bundled catalog is empty.
    text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), scripts.decode("utf-8"))
    catalog = json.loads((root / "playground/public/library.json").read_text(encoding="utf-8"))
    for entry in catalog["types"]:
        for item in [entry, *entry["members"]]:
            assert item["name"] in text, "Missing catalog name: " + item["name"]
    assert b"library-search" in scripts and b"New example cell" in scripts
    assert "bhash_jupyter/assets/bhash-compiler.jar" in wheel.namelist()
    assert not any("node_modules/" in name or "frontend/src/" in name for name in wheel.namelist())
wheels[0].with_suffix(wheels[0].suffix + ".sha256").write_text(hashlib.sha256(wheels[0].read_bytes()).hexdigest() + "  " + wheels[0].name + "\n", encoding="utf-8")
print("Wheel includes the compiler, offline catalog, reference renderer and portable prebuilt Jupyter extension")
