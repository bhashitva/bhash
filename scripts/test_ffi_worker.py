#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual Python notebook worker bridge over pipes, without Jupyter sockets.

This supplements, and does not replace, the full Jupyter client smoke test.
"""
import argparse
import json
from pathlib import Path
import threading
from bhash_jupyter.kernel import Worker


def execute(worker, code, expected=None):
    worker.send(kind="execute", code=code, allowStdin=False)
    results = []
    while True:
        message = worker.receive()
        if message["kind"] == "error":
            raise AssertionError(message)
        if message["kind"] == "result":
            results.append(message["text"])
        if message["kind"] == "done":
            break
    assert results == ([] if expected is None else [expected]), results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    worker = Worker(args.java, args.compiler, [])
    timer = threading.Timer(180, worker.process.kill)
    timer.daemon = True
    timer.start()
    try:
        execute(worker, "आयात मानक.संपीड़न; आयात मानक.संपीड़नप्रारूप; आयात मानक.संपीड़नदोष;")
        execute(worker, '''फलन खोलो(फल: मानक.परिणाम<बाइटबफ़र, संपीड़नदोष>) -> बाइटबफ़र {
            लौटाओ मिलान(फल) {
                विकल्प मानक.परिणाम.सफल(स्थिर व) => व,
                विकल्प मानक.परिणाम.असफल(स्थिर द) => नया बाइटबफ़र(०),
                विकल्प रिक्त => नया बाइटबफ़र(०),
            };
        }''')
        execute(worker, '''फलन चक्र(प: पाठ)->पाठ {
            स्थिर ब=खोलो(संपीड़न.संपीड़ितकरो(यूटीएफ८बफ़र(प),संपीड़नप्रारूप.गज़िप,६));
            लौटाओ यूटीएफ८पाठ(खोलो(संपीड़न.विसंपीड़ितकरो(ब,संपीड़नप्रारूप.गज़िप,१०२४)));
        }''')
        execute(worker, 'स्थिर पाठमान: पाठ="भारत😀";')
        execute(worker, "चक्र(पाठमान)", "भारत😀")
        execute(worker, "चक्र(पाठमान)", "भारत😀")
        execute(worker, 'चक्र("अगला cell")', "अगला cell")
        print("Notebook worker bridge: copied compression buffers, persistent scalars and repeated cell loaders passed")
        worker.close()
        timer.cancel()
        worker=Worker(args.java,args.compiler,[])
        timer=threading.Timer(180,worker.process.kill)
        timer.daemon=True
        timer.start()
        notebook=json.loads((Path(__file__).resolve().parents[1]/"editors/jupyter/compression.ipynb").read_text(encoding="utf-8"))
        cells=["".join(cell["source"]) for cell in notebook["cells"] if cell["cell_type"]=="code"]
        assert len(cells)==4
        for code,expected in zip(cells,[None,None,None,"नमस्ते भारत 😀"]):
            execute(worker,code,expected)
        print("Hindi compression notebook: every code cell passed")
    finally:
        timer.cancel()
        worker.close()


if __name__ == "__main__":
    main()
