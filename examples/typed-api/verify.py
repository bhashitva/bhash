#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the starter's Bhash integration tests and the real HTTP service."""
import argparse
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory
from time import monotonic, sleep

EXAMPLE = Path(__file__).resolve().parent
ROOT = EXAMPLE.parents[1]
JAVA = ["java", "-Dfile.encoding=UTF-8"]


def request(port, method, path, status, body=None):
    connection = HTTPConnection("127.0.0.1", port, timeout=8)
    try:
        headers = {"Content-Type": "application/json"} if body is not None else {}
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        payload = response.read()
        if response.status != status or response.getheader("Content-Type") != "application/json; charset=utf-8":
            raise AssertionError(f"{method} {path}: expected {status} JSON, got {response.status}: {payload!r}")
        return json.loads(payload)
    finally:
        connection.close()


def exercise_http(command, work, env, target):
    stdout, stderr = work / "stdout.log", work / "stderr.log"
    with stdout.open("wb") as out, stderr.open("wb") as err:
        process = subprocess.Popen([*command, "--port=0", "--db=:memory:", "--requests=4"],
                                   cwd=work, env=env, stdout=out, stderr=err)
        try:
            deadline = monotonic() + 30
            while monotonic() < deadline and process.poll() is None:
                ready = re.search(rb"(?m)^READY ([1-9][0-9]*)\r?$", stdout.read_bytes())
                if ready:
                    break
                sleep(0.05)
            else:
                raise AssertionError("server did not report a bound port")
            port = int(ready[1])
            document = request(port, "GET", "/openapi.json", 200)
            assert document == json.loads((EXAMPLE / "openapi.json").read_text(encoding="utf-8"))
            task = {"id": 12, "title": "भारत😀", "priority": 4}
            expected = {**task, "done": False}
            assert request(port, "POST", "/api/tasks", 201, json.dumps(task).encode()) == expected
            assert request(port, "GET", "/api/tasks/12", 200) == expected
            assert request(port, "POST", "/api/tasks", 400, b"{")["status"] == 400
            assert process.wait(timeout=20) == 0, "service exited unsuccessfully"
            if target == "native":
                stats = stderr.read_text(encoding="utf-8", errors="replace")
                assert re.search(r"collections=[1-9][0-9]* .*?\blive=0\b", stats), stats
        except Exception:
            for log in (stdout, stderr):
                print(log.read_text(encoding="utf-8", errors="replace"), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, default=ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--target", action="append", choices=["jvm", "clr", "native"], required=True)
    parser.add_argument("--routes-only", action="store_true", help="Skip the additional HTTP socket test")
    args = parser.parse_args()
    compiler = args.compiler.resolve()
    if not compiler.is_file():
        parser.error("Build the compiler or supply --compiler")
    subprocess.run([sys.executable, str(EXAMPLE / "generate.py"), "--check"], check=True)
    target_dir = ROOT / "target"
    target_dir.mkdir(exist_ok=True)
    for target in dict.fromkeys(args.target):
        with TemporaryDirectory(prefix=f"typed-api-{target}-", dir=target_dir) as directory:
            work = Path(directory)
            env = os.environ.copy()
            env.update(TMP=str(work), TEMP=str(work), TMPDIR=str(work))
            if target == "native":
                env.update(BHASH_GC_STRESS="1", BHASH_GC_STATS="1")
            cli = [*JAVA, f"-Djava.io.tmpdir={work}", "-jar", str(compiler)]
            subprocess.run([*cli, "test", str(EXAMPLE), "--target", target],
                           cwd=ROOT, env=env, check=True, timeout=240)
            if not args.routes_only:
                output = work / {"jvm": "app.jar", "clr": "clr",
                                 "native": "app.exe" if os.name == "nt" else "app"}[target]
                subprocess.run([*cli, "compile", str(EXAMPLE / "app"), "--target", target, "-o", str(output)],
                               cwd=ROOT, env=env, check=True, timeout=240)
                command = {"jvm": [*JAVA, "-jar", str(output)],
                           "clr": [os.environ.get("BHASH_DOTNET", "dotnet"), str(output / "BhashProgram.dll")],
                           "native": [str(output)]}[target]
                exercise_http(command, work, env, target)
            print(f"Typed API verification passed ({target})", flush=True)


if __name__ == "__main__":
    main()
