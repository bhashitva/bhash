#!/usr/bin/env python3
"""Stage the tested compiler, notebook and redistribution notices in the wheel."""
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

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
    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(JAR, ASSETS / "bhash-compiler.jar")
    shutil.copyfile(PACKAGE / "पहली-नोटबुक.ipynb", ASSETS / "starter.ipynb")
    for name in ("LICENSE", "NOTICE", "LICENSING.md", "THIRD_PARTY_NOTICES.md"):
        shutil.copyfile(ROOT / name, ASSETS / name)
    subprocess.run([sys.executable, "-m", "build", "--wheel", "--outdir",
                    str(ROOT / "target/jupyter-dist"), str(PACKAGE)], check=True)


if __name__ == "__main__":
    main()
