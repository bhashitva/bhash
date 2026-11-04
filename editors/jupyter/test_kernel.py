"""Exercise the installed-kernel protocol with a real Jupyter client."""
import argparse
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory

from jupyter_client import KernelManager
from jupyter_client.kernelspec import KernelSpecManager


def execute(client, source, expected=None, status="ok", streams=None, stdin=None):
    request = client.execute(source, allow_stdin=stdin is not None)
    if stdin is not None:
        prompt = client.get_stdin_msg(timeout=60)
        assert prompt["parent_header"]["msg_id"] == request
        client.input(stdin)
    reply = client.get_shell_msg(timeout=90)
    assert reply["parent_header"]["msg_id"] == request
    assert reply["content"]["status"] == status, reply["content"]
    values = []
    output = []
    while True:
        message = client.get_iopub_msg(timeout=20)
        if message["parent_header"].get("msg_id") != request:
            continue
        content = message["content"]
        if message["msg_type"] == "execute_result":
            values.append(content["data"]["text/plain"])
        if message["msg_type"] == "stream":
            output.append(content["text"])
        if message["msg_type"] == "status" and content["execution_state"] == "idle":
            break
    assert values == ([] if expected is None else [expected]), values
    assert "".join(output).replace("\r\n", "\n") == (streams or ""), output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler", type=Path)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    with TemporaryDirectory(prefix="bhash-kernel-test-") as temporary:
        spec = Path(temporary, "bhash")
        spec.mkdir()
        (spec / "kernel.json").write_text(json.dumps({
            "argv": [sys.executable, "-m", "bhash_jupyter.kernel", "-f", "{connection_file}",
                     "--BhashKernel.compiler=" + str(args.compiler.resolve()), "--BhashKernel.java=" + args.java],
            "display_name": "Bhash",
            "language": "bhash",
        }), encoding="utf-8")
        manager = KernelManager(kernel_name="bhash", connection_file=str(Path(temporary, "connection.json")),
                                kernel_spec_manager=KernelSpecManager(kernel_dirs=[temporary]))
        manager.start_kernel()
        client = manager.client()
        client.start_channels()
        try:
            client.wait_for_ready(timeout=30)
            execute(client, 'मान कुल: पूर्णांक = ०; लिखो("एक बार");', streams="एक बार\n")
            execute(client, "कुल = कुल + २; कुल", expected="२")
            execute(client, "कुल = कुल + २; कुल", expected="४")
            execute(client, "फलन दुगुना(अ:पूर्णांक)->पूर्णांक { लौटाओ अ*२; }")
            execute(client, "दुगुना(कुल)", expected="८")
            execute(client, "फलन दुगुना(अ:पूर्णांक)->पूर्णांक { लौटाओ अ+अ+१; }")
            execute(client, "दुगुना(कुल)", expected="९")
            execute(client, 'मान नया_मान: पूर्णांक = १; कुल = ९; त्रुटि("रुको");', status="error")
            execute(client, "कुल", expected="९")
            execute(client, "नया_मान", status="error")
            execute(client, 'प्रयास { प्रतीक्षा मानक.कार्य.विलंब(२); कुल = कुल + १; } '
                            'अंततः { लिखो("सफ़ाई"); } कुल', expected="१०", streams="सफ़ाई\n")
            execute(client, "पाठपढ़ो()", expected="भारत😀", stdin="भारत😀")
            client.complete("😀 कु", cursor_pos=4)
            completion = client.get_shell_msg(timeout=20)["content"]
            assert completion["matches"] == ["कुल"] and completion["cursor_start"] == 2, completion
            client.inspect("कुल", cursor_pos=3)
            assert client.get_shell_msg(timeout=20)["content"]["found"]
            manager.restart_kernel()
            client.wait_for_ready(timeout=30)
            execute(client, "कुल", status="error")
        finally:
            client.stop_channels()
            manager.shutdown_kernel()
    print("Bhash Jupyter kernel smoke test passed")


if __name__ == "__main__":
    main()
