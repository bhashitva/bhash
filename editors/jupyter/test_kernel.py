"""Exercise the installed-kernel protocol with a real Jupyter client."""
import argparse
from importlib.metadata import version
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from time import monotonic

from jupyter_client import KernelManager
from jupyter_client.kernelspec import KernelSpecManager


def shell_reply(client, request, timeout):
    deadline = monotonic() + timeout
    while True:
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise TimeoutError("No shell reply for " + request)
        reply = client.get_shell_msg(timeout=remaining)
        if reply["parent_header"].get("msg_id") == request:
            return reply["content"]


def execute(client, source, expected=None, status="ok", streams=None, stdin=None, before_stdin=None):
    request = client.execute(source, allow_stdin=stdin is not None)
    output = []
    if stdin is not None:
        prompt = client.get_stdin_msg(timeout=60)
        assert prompt["parent_header"]["msg_id"] == request
        if before_stdin is not None:
            while len("".join(output)) < len(before_stdin):
                message = client.get_iopub_msg(timeout=20)
                if (message["parent_header"].get("msg_id") == request
                        and message["msg_type"] == "stream"):
                    output.append(message["content"]["text"])
            assert "".join(output).replace("\r\n", "\n") == before_stdin, output
        client.input(stdin)
    reply = shell_reply(client, request, timeout=90)
    assert reply["status"] == status, reply
    values = []
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
    return reply


def assert_keyword_coverage(sources):
    grammar = Path(__file__).resolve().parents[2] / "src/main/antlr4/Bhash.g4"
    keywords = set(re.findall(r"(?m)^[A-Z][A-Z_0-9]*\s*:\s*'([\u0900-\u097f]+)'\s*;",
                              grammar.read_text(encoding="utf-8")))
    assert keywords, "No Bhash keyword rules found"
    seen = set()
    for source in sources:
        code = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/', " ", source)
        seen.update(re.findall(r"[\u0900-\u097f_0-9]+", code))
    assert not keywords - seen, "Missing notebook keyword coverage: " + ", ".join(sorted(keywords - seen))
    print(f"Notebook coverage includes all {len(keywords)} Bhash keywords")


def test_language(execute_case):
    successful_sources = []

    def run(source, **expected):
        execute_case(source, **expected)
        if expected.get("status", "ok") == "ok":
            successful_sources.append(source)

    run("""सार्वजनिक वर्ग गणक {
        निजी मान संख्या: पूर्णांक;
        निर्माता(प्रारंभ: पूर्णांक) { यह.संख्या = प्रारंभ; }
        सार्वजनिक फलन बढ़ाओ() -> पूर्णांक {
            यह.संख्या = यह.संख्या + १;
            लौटाओ यह.संख्या;
        }
    }""")
    run('स्थिर ग: गणक = नया गणक(४); ग.बढ़ाओ()', expected="५")
    run('स्थिर ग: गणक = नया गणक(९); स्थिर बढ़ीसंख्या: पूर्णांक = ग.बढ़ाओ(); बढ़ीसंख्या', expected="१०")
    run("बढ़ीसंख्या", expected="१०")
    run("ग", status="error")
    run("वर्ग गणक {}", status="error")
    run("नया गणक(०).बढ़ाओ()", expected="१")
    run("वर्ग अधूरा {} अनुपलब्ध", status="error")
    run("नया अधूरा()", status="error")
    run("""परिगणना रंग { लाल, हरा=५ }
        अभिलेख बिंदु(अंक: पूर्णांक, नाम: पाठ);
        फलन पहचान<त>(मूल्य: त) -> त { लौटाओ मूल्य; }
    """)
    run("""स्थिर क: बिंदु = नया बिंदु(पहचान<पूर्णांक>(४), "भारत😀");
        स्थिर अ: पूर्णांक[] = नया पूर्णांक[]{१, २, ३};
        स्थिर स: सूची<पूर्णांक> = नया सूची<पूर्णांक>{अ[१], क.अंक};
        स्थिर म: मानचित्र<पाठ,पूर्णांक> = नया मानचित्र<पाठ,पूर्णांक>{"क": स[१]};
        स्थिर बदलो: फलन(पूर्णांक)->पूर्णांक = फलन(न: पूर्णांक)->पूर्णांक {
            लौटाओ न + म["क"];
        };
        मान योग: पूर्णांक = ०;
        प्रत्येक (अ में न: पूर्णांक) {
            यदि (न == २) { जारी; } अन्यथा { योग = योग + बदलो(न); }
        }
        चयन (रंग.हरा) {
            विकल्प रंग.हरा: लिखो(योग, क.नाम, १ << ३, ७ & ३);
            शेष: त्रुटि("गलत रंग");
        }
    """, streams="१२ भारत😀 ८ ३\n")
    run("""संकुल उदाहरण;
        आयात मानक.गणित उपनाम गण;
        सार्वजनिक फलन मुख्य() -> शून्य { लिखो(गण.वर्गमूल(९.०)); }
    """, streams="३.०\n")
    run("असमकालिक फलन मुख्य(तर्क: पाठ[]) -> शून्य { प्रतीक्षा मानक.कार्य.विलंब(१); लिखो(तर्क.आकार); }",
        streams="०\n")
    examples = Path(__file__).resolve().parents[2] / "examples"
    run((examples / "objects.bhash").read_text(encoding="utf-8"), streams="मनोज १५० १५ १६५\n")
    run((examples / "contracts-generics.bhash").read_text(encoding="utf-8"),
        streams="पूर्णांक: २१\nपाठ: नमस्ते\nपात्र ४२ ३.०\n१ १ १००\n")
    websocket_stream = examples / "websocket" / "stream.bhash"
    run(websocket_stream.read_text(encoding="utf-8"), streams="stream-ok\n")
    async_sqlite = examples.parent / "tutorial/async-io/sqlite.bhash"
    run(async_sqlite.read_text(encoding="utf-8"), streams="नमस्ते, भारत😀\n")
    run("""स्थिर ब: बाइटबफ़र = नया बाइटबफ़र{परिवर्तित<बाइट>(२५५)};
        स्थिर न: लघुपूर्णांक = परिवर्तित<लघुपूर्णांक>(७);
        स्थिर दश: दशमलव = १.५;
        स्थिर व: सर्ववस्तु = नया गणक(०);
        स्थिर ठीक: तार्किक = प्रकारहै<गणक>(व) && सुरक्षितरूप<गणक>(व) != रिक्त;
        जबतक (सत्य) { यदि (असत्य) { त्रुटि("गलत"); } विराम; }
        लिखो(ब[०], न, दश, ठीक, परिवर्तित<गणक>(व).बढ़ाओ());
    """, streams="२५५ ७ १.५ सत्य १\n")
    run("कुल", expected="१०")
    run("""आयात मानक.बंदनीय;
        वर्ग सफाई पालन बंदनीय {
            सार्वजनिक फलन बंदकरो() -> शून्य { लिखो("बंद"); }
        }
        प्रयास {
            संसाधन (स: सफाई = नया सफाई()) { फेंको नया अपवाद("दोष"); }
        } पकड़ो (ग: अपवाद) { लिखो(ग.संदेश); }
        अंततः { लिखो("अंत"); }
    """, streams="बंद\nदोष\nअंत\n")
    assert_keyword_coverage(successful_sources)


def test_ordered_bindings(run):
    console_cell = """लिखो("० से २० तक एक पूर्णांक लिखें:");
        स्थिर संख्या: पूर्णांक = पढ़ो();
        यदि (संख्या < ० || संख्या > २०) {
            लिखो("कृपया ० से २० तक की संख्या दें।");
            लौटाओ;
        }
        स्थिर गणना: गणक = नया गणक(संख्या);
        स्थिर परिणाम: पूर्णांक = गणना.बढ़ाओ();
        लिखो("परिणाम:", परिणाम);
    """
    prompt = "० से २० तक एक पूर्णांक लिखें:\n"
    run(console_cell, stdin="२१", before_stdin=prompt,
            streams=prompt + "कृपया ० से २० तक की संख्या दें।\n")
    run("संख्या", expected="२१")
    run("परिणाम", status="error")
    run(console_cell, stdin="५", before_stdin=prompt, streams=prompt + "परिणाम: ६\n")
    run("संख्या + परिणाम", expected="११")
    run(console_cell, stdin="२१", before_stdin=prompt,
            streams=prompt + "कृपया ० से २० तक की संख्या दें।\n")
    run("परिणाम", expected="६")
    run("{ स्थिर परिणाम: पूर्णांक = ८; स्थिर अंदर: पूर्णांक = परिणाम; लिखो(अंदर); }",
            streams="८\n")
    run("अंदर", status="error")
    run("परिणाम", expected="६")
    run("असमकालिक फलन बादमें() -> पूर्णांक { "
                    "प्रतीक्षा मानक.कार्य.विलंब(१); लौटाओ ७; }")
    run('लिखो("प्रतीक्षा"); स्थिर मिला: पूर्णांक = प्रतीक्षा बादमें(); मिला',
            streams="प्रतीक्षा\n", expected="७")
    run("मिला", expected="७")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler", type=Path, nargs="?")
    parser.add_argument("--installed", action="store_true", help="Use the installed bhash kernelspec")
    parser.add_argument("--notebook", type=Path, help="Also execute the Hindi starter notebook")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if args.installed == (args.compiler is not None):
        parser.error("Provide either a compiler JAR or --installed")
    with TemporaryDirectory(prefix="bhash-kernel-test-") as temporary:
        spec = Path(temporary, "bhash")
        if not args.installed:
            spec.mkdir()
            (spec / "kernel.json").write_text(json.dumps({
                "argv": [sys.executable, "-m", "bhash_jupyter.kernel", "-f", "{connection_file}",
                         "--BhashKernel.compiler=" + str(args.compiler.resolve()), "--BhashKernel.java=" + args.java],
                "display_name": "Bhash",
                "language": "bhash",
            }), encoding="utf-8")
        manager = KernelManager(kernel_name="bhash", connection_file=str(Path(temporary, "connection.json")),
                                kernel_spec_manager=KernelSpecManager() if args.installed
                                else KernelSpecManager(kernel_dirs=[temporary]))
        manager.start_kernel()
        client = manager.client()
        client.start_channels()
        try:
            client.wait_for_ready(timeout=30)
            info = shell_reply(client, client.kernel_info(), timeout=20)
            assert info["implementation_version"] == version("bhash-jupyter"), info
            execute(client, 'मान कुल: पूर्णांक = ०; लिखो("एक बार");', streams="एक बार\n")
            execute(client, "कुल = कुल + २; कुल", expected="२")
            execute(client, "कुल = कुल + २; कुल", expected="४")
            execute(client, "फलन दुगुना(अ:पूर्णांक)->पूर्णांक { लौटाओ अ*२; }")
            execute(client, "दुगुना(कुल)", expected="८")
            execute(client, "फलन दुगुना(अ:पूर्णांक)->पूर्णांक { लौटाओ अ+अ+१; }")
            execute(client, "दुगुना(कुल)", expected="९")
            execute(client, "स्थिर सीमा: पूर्णांक = ५; मान गिनती: पूर्णांक = १; "
                            "जबतक (गिनती <= सीमा) { लिखो(गिनती, दुगुना(गिनती)); गिनती = गिनती + १; }",
                    streams="१ ३\n२ ५\n३ ७\n४ ९\n५ ११\n")
            execute(client, "सीमा = ६;", status="error")
            execute(client, "सीमा", expected="५")
            execute(client, 'मान नया_मान: पूर्णांक = १; कुल = ९; त्रुटि("रुको");', status="error")
            execute(client, "कुल", expected="९")
            execute(client, "नया_मान", status="error")
            execute(client, 'प्रयास { प्रतीक्षा मानक.कार्य.विलंब(२); कुल = कुल + १; } '
                            'अंततः { लिखो("सफ़ाई"); } कुल', expected="१०", streams="सफ़ाई\n")
            execute(client, "पाठपढ़ो()", expected="भारत😀", stdin="भारत😀")
            test_language(lambda source, **expected: execute(client, source, **expected))
            test_ordered_bindings(lambda source, **expected: execute(client, source, **expected))
            execute(client, 'लिखो("आरम्भ"); स्‍थिर क्‍षमा: पूर्‍णांक = ७;', streams="आरम्भ\n")
            execute(client, "क्षमा", expected="७")
            example = Path(__file__).resolve().parents[2] / "tutorial/unicode-identifiers/identifiers.bhash"
            execute(client, example.read_text(encoding="utf-8"), streams="५ ५ ५ ६ ७ ८ ९ ६\n३.०\n६\nक्‍ष असत्य\n")
            execute(client, "क्‌षमा = ९;", status="error")
            execute(client, "क्षमा", expected="७")
            bad_joiner = execute(client, "स्थिर अ\u200dब: पूर्णांक = १;", status="error")
            assert "BH1003" in bad_joiner["evalue"], bad_joiner
            request = client.complete("क्‌ष", cursor_pos=4)
            matches = shell_reply(client, request, timeout=20)["matches"]
            assert "क्‍षमा" in matches, matches
            request = client.inspect("क्‌षमा", cursor_pos=6)
            assert shell_reply(client, request, timeout=20)["found"]
            request = client.complete("😀 कु", cursor_pos=4)
            completion = shell_reply(client, request, timeout=20)
            assert completion["matches"] == ["कुल"] and completion["cursor_start"] == 2, completion
            request = client.inspect("कुल", cursor_pos=3)
            assert shell_reply(client, request, timeout=20)["found"]
            manager.restart_kernel()
            client.wait_for_ready(timeout=30)
            execute(client, "कुल", status="error")
            execute(client, "नया गणक(०)", status="error")
            if args.notebook:
                notebook = json.loads(args.notebook.read_text(encoding="utf-8"))
                code = ["".join(cell["source"]) for cell in notebook["cells"] if cell["cell_type"] == "code"]
                code = [source for source in code if source.strip()]
                assert len(code) == 4
                for source, expected in zip(code, [None, "२", None, "४"]):
                    execute(client, source, expected=expected)
        finally:
            client.stop_channels()
            manager.shutdown_kernel()
    print("Bhash Jupyter kernel smoke test passed")


if __name__ == "__main__":
    main()
