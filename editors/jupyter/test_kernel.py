"""Exercise the installed-kernel protocol with a real Jupyter client."""
import argparse
from importlib.metadata import version
import json
import os
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from time import monotonic

from jupyter_client import KernelManager
from jupyter_client.kernelspec import KernelSpecManager

CELL_NUMBER = 0


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
    global CELL_NUMBER
    CELL_NUMBER += 1
    if os.environ.get("BHASH_JUPYTER_SMOKE_PROGRESS") == "1":
        print("Jupyter smoke cell", CELL_NUMBER, "started", flush=True)
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
    keywords.update(("विकल्पसमूह", "मिलान"))
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
    run("विकल्पसमूह नोटउत्तर { मिला(व:पूर्णांक); नहीं(); }")
    run("फलन नोटलो()->नोटउत्तर {लौटाओ नोटउत्तर.मिला(७);}")
    run("मिलान(नोटलो()){विकल्प नोटउत्तर.मिला(स्थिर व)=>व, विकल्प नोटउत्तर.नहीं()=>०, विकल्प रिक्त=>-१}", expected="७")
    assert_keyword_coverage(successful_sources)


def test_safer_everyday(run):
    run("""अनुबंध सुरक्षितनामित { फलन नाम()->पाठ; }
        वर्ग सुरक्षितव्यक्ति पालन सुरक्षितनामित {
            स्थिर पाठ्य: पाठ;
            निर्माता(नाम: पाठ) { पाठ्य=नाम; }
            फलन नाम()->पाठ { लौटाओ पाठ्य; }
            फलन पहचान<त>(व: त)->त { लौटाओ व; }
        }
        फलन सुरक्षितनाम<त: सुरक्षितनामित>(व: त)->पाठ {लौटाओ व.नाम();}
    """)
    run('लिखो("अनुमान"); मान सुरक्षितगिनती=२; स्थिर सुरक्षितपाठ=सुरक्षितनाम(नया सुरक्षितव्यक्ति("सीमा"));', streams="अनुमान\n")
    run('सुरक्षितगिनती=सुरक्षितगिनती+१; सुरक्षितगिनती', expected="३")
    run('सुरक्षितपाठ', expected="सीमा")
    run('स्थिर सुरक्षितअगला=सुरक्षितगिनती+१; सुरक्षितअगला', expected="४")
    run('यदि (सुरक्षितगिनती>०) {लौटाओ;} स्थिर सुरक्षितछूटा=१;')
    run('सुरक्षितछूटा', status="error")
    run('{ स्थिर सुरक्षितअंदर=९; लिखो(सुरक्षितअंदर); }', streams="९\n")
    run('सुरक्षितअंदर', status="error")
    run('लिखो(नया सुरक्षितव्यक्ति("क").पहचान(७));', streams="७\n")
    run('सुरक्षितनाम(१)', status="error")


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


def test_sqlserver(client):
    execute(client, """आयात मानक.डेटा;
आयात मानक.डेटासंबंध;
आयात मानक.डेटाकथन;
आयात मानक.डेटालेनदेन;
आयात मानक.परीक्षण;
आयात मानक.विन्यास;
स्थिर संबंधपाठ: पाठ = विन्यास.परिवेश("BHASH_SQLSERVER_URL", "");
स्थिर नमूना: पाठ = "कक्षा 😀 ' ? ;";
""")
    execute(client, """संसाधन (ड: डेटासंबंध = डेटा.एसक्यूएलसर्वर(संबंधपाठ)) {
    संसाधन (ल: डेटालेनदेन = ड.लेनदेन()) {
        संसाधन (क: डेटाकथन = ड.तैयार("INSERT INTO dbo.gradelevel (id, name) VALUES (?, ?)")) {
            परीक्षण.बराबर(२, क.बंधनसंख्या());
            क.पूर्णांकरखो(१, ९९);
            क.पाठरखो(२, नमूना);
            परीक्षण.बराबर(१, क.चलाओ());
        }
        संसाधन (क: डेटाकथन = ड.तैयार("SELECT name FROM dbo.gradelevel WHERE id=?")) {
            क.पूर्णांकरखो(१, ९९);
            परीक्षण.सत्यहै(क.अगला(), "लेनदेन में लिखी पंक्ति मिली।");
            परीक्षण.बराबर(नमूना, क.पाठलो(०));
            परीक्षण.सत्यहै(!क.अगला(), "एक ही पंक्ति मिली।");
        }
    }
}
""")
    execute(client, """संसाधन (ड: डेटासंबंध = डेटा.एसक्यूएलसर्वर(संबंधपाठ)) {
    संसाधन (क: डेटाकथन = ड.तैयार("SELECT name FROM dbo.gradelevel WHERE id=?")) {
        क.पूर्णांकरखो(१, ९९);
        परीक्षण.सत्यहै(!क.अगला(), "नए संबंध में पंक्ति नहीं है।");
    }
}
लिखो("SQLSERVER_JUPYTER_OK");
""", streams="SQLSERVER_JUPYTER_OK\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler", type=Path, nargs="?")
    parser.add_argument("--installed", action="store_true", help="Use the installed bhash kernelspec")
    parser.add_argument("--notebook", type=Path, help="Also execute the Hindi starter notebook")
    parser.add_argument("--java", default="java")
    parser.add_argument("--mysql", action="store_true", help="Exercise MySQL with the installed kernelspec and fixture")
    parser.add_argument("--sqlserver", action="store_true", help="Exercise SQL Server with the installed kernelspec and fixture")
    parser.add_argument("--ffi", action="store_true", help="Exercise the packaged compression provider through real notebook cells")
    parser.add_argument("--ffi-only", action="store_true", help="Run only the compression protocol cells (requires --ffi)")
    args = parser.parse_args()
    if args.ffi_only and not args.ffi:
        parser.error("--ffi-only requires --ffi")
    if args.sqlserver and not args.installed:
        parser.error("--sqlserver requires an installed kernelspec configured with --sqlserver-driver")
    if args.mysql and not args.installed:
        parser.error("--mysql requires an installed kernelspec configured with --mysql-driver")
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
            if args.ffi:
                execute(client, 'आयात मानक.संपीड़न; आयात मानक.संपीड़नप्रारूप; आयात मानक.संपीड़नदोष;')
                execute(client, '''फलन संपीड़नखोलो(फल: मानक.परिणाम<बाइटबफ़र, संपीड़नदोष>) -> बाइटबफ़र {
                    लौटाओ मिलान (फल) {
                        विकल्प मानक.परिणाम.सफल(स्थिर मूल्य) => मूल्य,
                        विकल्प मानक.परिणाम.असफल(स्थिर दोष) => नया बाइटबफ़र(०),
                        विकल्प रिक्त => नया बाइटबफ़र(०),
                    };
                }''')
                execute(client, '''फलन संपीड़नपाठ(मूल: पाठ) -> पाठ {
                    स्थिर पैक=संपीड़नखोलो(संपीड़न.संपीड़ितकरो(यूटीएफ८बफ़र(मूल), संपीड़नप्रारूप.गज़िप, ६));
                    लौटाओ यूटीएफ८पाठ(संपीड़नखोलो(संपीड़न.विसंपीड़ितकरो(पैक, संपीड़नप्रारूप.गज़िप, १०२४)));
                }''')
                execute(client, 'स्थिर संपीड़नमूल: पाठ = "भारत😀";')
                # Scalar notebook state crosses cell loaders; native buffers remain local to each call.
                execute(client, 'संपीड़नपाठ(संपीड़नमूल)', expected='भारत😀')
                execute(client, 'संपीड़नपाठ(संपीड़नमूल)', expected='भारत😀')
                execute(client, '''यदि (सत्य) {
                    स्थिर पैक=संपीड़नखोलो(संपीड़न.संपीड़ितकरो(यूटीएफ८बफ़र(संपीड़नमूल), संपीड़नप्रारूप.गज़िप, ६));
                    मिलान (संपीड़न.विसंपीड़ितकरो(पैक, संपीड़नप्रारूप.गज़िप, १)) {
                    विकल्प मानक.परिणाम.सफल(स्थिर मूल्य): { लिखो("अप्रत्याशित"); }
                    विकल्प मानक.परिणाम.असफल(स्थिर दोष): { लिखो(दोष.कोड.नाम); }
                    विकल्प रिक्त: { लिखो("अप्रत्याशित रिक्त"); }
                    }
                }''', streams="निर्गतसीमा\n")
                if args.ffi_only:
                    print("Bhash Jupyter compression cells passed", flush=True)
                    return
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
            test_safer_everyday(lambda source, **expected: execute(client, source, **expected))
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
            execute(client, "// bhash:null-safety=strict\nमान विकल्पमान: पूर्णांक? = रिक्त;")
            execute(client, "विकल्पसमूह खोज<त> { मिला(मूल्य:त); अनुपस्थित(); }")
            execute(client, "फलन खोजलो()->खोज<पूर्णांक> {लौटाओ खोज<पूर्णांक>.मिला(७);}")
            execute(client, "मिलान(खोजलो()){विकल्प खोज.मिला(स्थिर व)=>व, विकल्प खोज.अनुपस्थित()=>०}", expected="७")
            execute(client, "फलन परिणामलो()->मानक.परिणाम<पूर्णांक,पाठ> {लौटाओ मानक.परिणाम<पूर्णांक,पाठ>.असफल(\"दोष\");}")
            execute(client, "मिलान(परिणामलो()){विकल्प मानक.परिणाम.सफल(स्थिर व)=>अंकपाठ(व), विकल्प मानक.परिणाम.असफल(स्थिर दोष)=>दोष}", expected="दोष")
            execute(client, "मिलान(खोजलो()){विकल्प खोज.मिला(स्थिर व)=>व, विकल्प खोज.अनुपस्थित()=>०}", expected="७")
            execute(client, "मिलान(खोजलो()){विकल्प खोज.मिला(स्थिर व)=>व}", status="error")
            execute(client, "विकल्पमान ?? ७", expected="७")
            execute(client, "विकल्पमान = ०;")
            execute(client, "विकल्पमान ?? ७", expected="०")
            execute(client, "विकल्पमान = रिक्त;")
            execute(client, "स्थिर गलती: पूर्णांक = विकल्पमान;", status="error")
            execute(client, "विकल्पमान ?? ९", expected="९")
            execute(client, "असमकालिक फलन वैकल्पिकउत्तर()->पूर्णांक { प्रतीक्षा मानक.कार्य.विलंब(१); लौटाओ ८; }")
            execute(client, "विकल्पमान ?? प्रतीक्षा वैकल्पिकउत्तर()", expected="८")
            manager.restart_kernel()
            client.wait_for_ready(timeout=30)
            if args.notebook:
                notebook = json.loads(args.notebook.read_text(encoding="utf-8"))
                code = ["".join(cell["source"]) for cell in notebook["cells"] if cell["cell_type"] == "code"]
                code = [source for source in code if source.strip()]
                assert len(code) == 4
                for source, expected in zip(code, [None, "२", None, "४"]):
                    execute(client, source, expected=expected)
            if args.mysql:
                example = Path(__file__).resolve().parents[2] / "src/test/resources/mysql/integration.bhash"
                source = example.read_text(encoding="utf-8")
                execute(client, source.replace("फलन मुख्य()", "फलन माईएसक्यूएलपरीक्षण()", 1))
                execute(client, "माईएसक्यूएलपरीक्षण();", streams="MYSQL_OK\n")
            if args.sqlserver:
                test_sqlserver(client)
                print("SQL Server 2022 Jupyter parameter binding and rollback passed")
        finally:
            client.stop_channels()
            manager.shutdown_kernel()
    print("Bhash Jupyter kernel smoke test passed")


if __name__ == "__main__":
    main()
