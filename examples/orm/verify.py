#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile and execute the generated mapper on SQLite or a dedicated PostgreSQL test DB."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("jvm", "clr", "native"), required=True)
    parser.add_argument("--provider", choices=("sqlite", "postgresql"), default="sqlite")
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / "scripts/generate_orm.py"), str(HERE / "mapping.json"),
                    "--output", str(HERE / "models/src/generated.bhash"), "--check"], check=True)
    workroot = ROOT / "target"
    workroot.mkdir(exist_ok=True)
    with TemporaryDirectory(prefix="orm-", dir=workroot, ignore_cleanup_errors=os.name == "nt") as tmp:
        tmp = Path(tmp)
        env = os.environ | {"TEMP": str(tmp), "TMP": str(tmp), "TMPDIR": str(tmp),
                            "BHASH_ORM_PROVIDER": args.provider, "BHASH_ORM_DB": str(tmp / "school.db")}
        if args.target == "native":
            env |= {"BHASH_GC_STRESS": "1", "BHASH_GC_STATS": "1"}
        cli = ["java", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={tmp}", "-jar",
               str(ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")]
        output = tmp / {"jvm": "school.jar", "clr": "clr",
                        "native": "school.exe" if os.name == "nt" else "school"}[args.target]
        subprocess.run(cli + ["compile", str(HERE / "integration.test"), "--target", args.target,
                              "-o", str(output)], env=env, check=True, timeout=240)
        command = {"jvm": ["java", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-jar", str(output)],
                   "clr": ["dotnet", str(output / "BhashProgram.dll")],
                   "native": [str(output)]}[args.target]
        result = subprocess.run(command, env=env, capture_output=True, encoding="utf-8",
                                errors="replace", timeout=120)
        if result.returncode or "ORM विद्यालय ठीक" not in result.stdout:
            raise AssertionError(result.stdout + result.stderr)
        if args.target == "native":
            assert re.search(r"collections=[1-9][0-9]* .*?\blive=0\b", result.stderr), result.stderr
    print(f"ORM typed mapping, foreign keys, transactions and conflicts passed ({args.target}, {args.provider})")


if __name__ == "__main__":
    main()
