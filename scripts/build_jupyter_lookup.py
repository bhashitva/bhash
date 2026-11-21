#!/usr/bin/env python3
"""Build the shared offline reference as a prebuilt Jupyter frontend extension."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import platform
import jupyterlab

ROOT = Path(__file__).resolve().parent.parent
FRONTEND = ROOT / "editors/jupyter/frontend"
OUTPUT = ROOT / "editors/jupyter/bhash_jupyter/labextension"


def source_digest():
    sources = [*sorted((FRONTEND / "src").glob("*.mjs")),
               *sorted((FRONTEND / "style").glob("*.css")),
               Path(__file__).resolve(), FRONTEND / "scripts/build.mjs", FRONTEND / "package.json",
               FRONTEND / "package-lock.json",
               *sorted((ROOT / "playground/src").glob("library*.mjs")),
               ROOT / "playground/src/identifier-profile.js",
               ROOT / "playground/src/keywords.mjs", ROOT / "playground/src/keyword-picker.mjs",
               ROOT / "src/main/antlr4/Bhash.g4", ROOT / "editors/vscode/snippets/bhash.json",
               *[ROOT / "playground/public" / name for name in ("index.html", "style.css", "library.json")]]
    digest = hashlib.sha256()
    digest.update(jupyterlab.__version__.encode())
    for path in sources:
        digest.update(str(path.relative_to(ROOT)).replace("\\", "/").encode())
        digest.update(b"\0" + path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return digest.hexdigest()


def build():
    stamp = OUTPUT / "build-info.json"
    digest = source_digest()
    if stamp.is_file() and json.loads(stamp.read_text())["source_sha256"] == digest and list((OUTPUT / "static").glob("remoteEntry.*.js")):
        return
    node = shutil.which("node")
    supplied = os.environ.get("BHASH_NPM_CLI")
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    cli = Path(supplied) if supplied else (Path(npm).resolve() if npm and os.name != "nt" else Path(npm).parent / "node_modules/npm/bin/npm-cli.js" if npm else None)
    if not node or cli is None or not cli.is_file():
        raise SystemExit("Building Jupyter UI from source needs Node.js 22 and npm. Prebuilt wheel users do not need these tools.")
    dependency_stamp = FRONTEND / "node_modules/.bhash-dependencies-sha256"
    dependency_hash = hashlib.sha256((FRONTEND / "package-lock.json").read_bytes() +
                                    subprocess.check_output([node, "--version"]) +
                                    (sys.platform + platform.machine()).encode()).hexdigest()
    if not dependency_stamp.is_file() or dependency_stamp.read_text() != dependency_hash:
        subprocess.run([node, str(cli), "ci", "--no-audit", "--no-fund"], cwd=FRONTEND, check=True)
        dependency_stamp.write_text(dependency_hash)
    subprocess.run([node, str(cli), "run", "build"], cwd=FRONTEND, check=True)
    subprocess.run([node, str(FRONTEND / "node_modules/@jupyterlab/builder/lib/build-labextension.js"),
                    "--core-path", str(Path(jupyterlab.__file__).parent / "staging"), str(FRONTEND)],
                   cwd=FRONTEND, check=True)
    manifest_path = OUTPUT / "package.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["jupyterlab"]["_build"]["load"] = manifest["jupyterlab"]["_build"]["load"].replace("\\", "/")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "install.json").write_text(json.dumps({"packageManager": "python", "packageName": "bhash-jupyter",
        "uninstallInstructions": "Use pip uninstall bhash-jupyter in the Jupyter server environment."}) + "\n", encoding="utf-8")
    for package in ("webpack", "esbuild"):
        license_name = "LICENSE.md" if package == "esbuild" else "LICENSE"
        shutil.copyfile(FRONTEND / "node_modules" / package / license_name, OUTPUT / "static" / f"{package}-LICENSE.txt")
    stamp.write_text(json.dumps({"source_sha256": digest}) + "\n", encoding="utf-8")
    print("Built the offline Bhash Jupyter library lookup")


if __name__ == "__main__":
    build()
