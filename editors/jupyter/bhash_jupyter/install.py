"""Register a Bhash kernelspec in the current user's Jupyter environment."""
import argparse
import json
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory

from jupyter_client.kernelspec import KernelSpecManager


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", required=True, type=Path, help="Path to the built Bhash compiler JAR")
    parser.add_argument("--java", default="java", help="Java executable from a full JDK 17 or newer")
    args = parser.parse_args()
    compiler = args.compiler.resolve()
    java = shutil.which(args.java)
    if not compiler.is_file() or java is None:
        parser.error("A readable compiler JAR and a Java executable are required")
    spec = {
        "argv": [sys.executable, "-m", "bhash_jupyter.kernel", "-f", "{connection_file}",
                 "--BhashKernel.compiler=" + str(compiler), "--BhashKernel.java=" + str(Path(java).resolve())],
        "display_name": "Bhash (भाष)",
        "language": "bhash",
    }
    with TemporaryDirectory(prefix="bhash-kernelspec-") as temporary:
        Path(temporary, "kernel.json").write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")
        location = KernelSpecManager().install_kernel_spec(temporary, kernel_name="bhash", user=True)
    print(location)


if __name__ == "__main__":
    main()
