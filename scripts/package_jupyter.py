#!/usr/bin/env python3
"""Stage the tested compiler, notebook and redistribution notices in the wheel."""
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from tempfile import TemporaryDirectory
from build_jupyter_lookup import build as build_lookup

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / "editors/jupyter"
ASSETS = PACKAGE / "bhash_jupyter/assets"
JAR = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"


def main():
    if not JAR.is_file():
        raise SystemExit("Build the compiler with Maven before packaging the kernel")
    with zipfile.ZipFile(JAR) as jar:
        if "dev/bhash/backend/jvm/NotebookWorker.class" not in jar.namelist():
            raise SystemExit("Compiler does not include the notebook worker")
    build_lookup()
    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(JAR, ASSETS / "bhash-compiler.jar")
    shutil.copyfile(PACKAGE / "पहली-नोटबुक.ipynb", ASSETS / "starter.ipynb")
    shutil.copyfile(PACKAGE / "compression.ipynb", ASSETS / "compression.ipynb")
    for name in ("LICENSE", "NOTICE", "LICENSING.md", "THIRD_PARTY_NOTICES.md"):
        shutil.copyfile(ROOT / name, ASSETS / name)
    # setuptools' wheel staging paths are deep. A short temporary source tree
    # also supports Windows checkouts in long, space-containing user folders.
    with TemporaryDirectory(prefix="bhash-wheel-") as temporary:
        staged = Path(temporary) / "source"
        staged.mkdir()
        shutil.copyfile(PACKAGE / "pyproject.toml", staged / "pyproject.toml")
        shutil.copytree(PACKAGE / "bhash_jupyter", staged / "bhash_jupyter",
                        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        subprocess.run([sys.executable, "-m", "build", "--wheel", "--outdir",
                        str(ROOT / "target/jupyter-dist"), str(staged)], check=True)


if __name__ == "__main__":
    main()
