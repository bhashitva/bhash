#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the public Hindi examples, including real file output, on all desktop targets."""
import argparse
import gzip
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler",type=Path)
    parser.add_argument("--java",default="java")
    args=parser.parse_args()
    for target in ("jvm","clr","native"):
        with TemporaryDirectory(prefix="ffi-examples-",dir=ROOT/"target") as temporary:
            work=Path(temporary)
            for sample in ("roundtrip","errors","files"):
                command=[args.java,"-Djava.io.tmpdir="+str(work),"-jar",str(args.compiler.resolve()),"run",
                         str(ROOT/"tutorial/compression"/(sample+".bhash")),"--target",target]
                result=subprocess.run(command,cwd=work,capture_output=True,encoding="utf-8",timeout=180)
                assert result.returncode==0,(target,sample,result.stderr)
                if sample=="roundtrip":
                    assert result.stdout.replace("\r\n","\n")=="नमस्ते भारत 😀\n",result.stdout
                elif sample=="errors":
                    assert "दूषितडेटा" in result.stdout,result.stdout
                else:
                    assert gzip.decompress((work/"compression-demo-output.gz").read_bytes())==(work/"compression-demo-input.txt").read_bytes()
            print(target+": Hindi roundtrip, typed errors and bounded file example passed",flush=True)


if __name__=="__main__":
    main()
