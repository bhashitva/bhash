#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build or check the Hindi reference using the compiler's public API and source examples."""

import argparse
from collections import defaultdict
from dataclasses import dataclass
from functools import lru_cache
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[1]
TUTORIAL = ROOT / "tutorial/standard-library"
DATA = TUTORIAL / "reference-data"
RUNTIME_EXAMPLES = DATA / "runtime-uses.md"
OUTPUT = TUTORIAL / "reference"
DEFAULT_JAR = ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar"
INTRO = "<!-- scripts/build_library_reference.py से बनाया गया। बदलाव reference-data में करें। -->\n\n"

# Source names are supplied by the compiler's catalog loader.
GROUPS = {
    "__bhash_standard_library__.bhash": ("मूल फलन और रनटाइम बंधन", "tutorial/standard-library/README.md"),
    "__bhash_foundations__.bhash": ("संग्रह और अनुप्रयोग का आधार", "tutorial/application-foundations/README.md"),
    "__bhash_networking__.bhash": ("HTTP क्लाइंट", "tutorial/networking/README.md"),
    "__bhash_sqlite__.bhash": ("SQLite", "tutorial/sqlite/README.md"),
    "__bhash_database__.bhash": ("साझा डेटाबेस प्रदाता", "tutorial/database-providers/README.md"),
    "__bhash_migrations__.bhash": ("डेटाबेस माइग्रेशन", "tutorial/database-providers/README.md"),
    "__bhash_web__.bhash": ("HTTP सर्वर और वेब अनुप्रयोग", "tutorial/web-applications/README.md"),
    "__bhash_auth__.bhash": ("प्रमाणीकरण और सत्र", "tutorial/authentication/README.md"),
    "__bhash_tasks__.bhash": ("असमकालिक कार्य और निरस्तीकरण", "tutorial/async/README.md"),
    "__bhash_async_database__.bhash": ("असमकालिक डेटाबेस", "tutorial/async-io/README.md"),
    "__bhash_async_web__.bhash": ("असमकालिक HTTP सर्वर और धाराएँ", "docs/async-http-server.md"),
    "__bhash_websocket__.bhash": ("WebSocket सर्वर और संदेश", "docs/websocket.md"),
}
GUIDES = {
    "पाठस्वरूप": "tutorial/standard-library/08-formatting.md",
    "समुच्चय": "tutorial/standard-library/09-sets.md",
    "कूटशब्द": "tutorial/authentication/README.md",
    "सुरक्षा": "tutorial/authentication/README.md",
}
KINDS = {"class": "वर्ग", "interface": "अनुबंध", "record": "अभिलेख"}


@dataclass(order=True)
class Example:
    preference: int
    source_rank: int
    documentation: bool
    negative_score: int
    source: str
    block: int
    line: int
    excerpt: str


def inventory(jar):
    if not jar.is_file():
        raise SystemExit(f"Build the compiler first or pass --jar: {jar}")
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="library-api-", dir=ROOT / "target") as scratch:
        raw = subprocess.check_output(
            ["java", f"-Djava.io.tmpdir={scratch}", "-cp", str(jar),
             str(ROOT / "scripts/StandardLibraryApi.java"), scratch],
            cwd=ROOT,
        )
    return json.loads(raw)


def rows(name, minimum, maximum):
    result = {}
    path = DATA / name
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cells = tuple(cell.strip() for cell in line.split("|"))
        key, *values = cells
        if not minimum <= len(cells) <= maximum or not all(cells) or key in result:
            raise ValueError(f"{path}:{number}: invalid or duplicate entry for {key}")
        result[key] = tuple(values)
    return result


def link(page, target, label):
    path = ROOT / target
    if not path.is_file() and not path.is_relative_to(OUTPUT):
        raise ValueError(f"Missing reference target: {target}")
    relative = Path(os.path.relpath(path, page.parent)).as_posix()
    return f"[{label}]({relative})"


def code(text):
    return "\x60" + text.replace("|", "\\|") + "\x60"


def parameters(member):
    return ", ".join(f"{p['name']}: {p['type']}" for p in member["parameters"])


def describe(methods, owner, member):
    key = f"{owner}.{member['name']}"
    return methods.get(key) or methods[member["name"]]


def plain_type(declaration):
    return declaration.split("<", 1)[0]


def split_type_arguments(declaration):
    head, separator, tail = declaration.partition("<")
    if not separator:
        return head, []
    parts = []
    start = depth = 0
    tail = tail[:-1]
    for offset, character in enumerate(tail):
        if character in "<(":
            depth += 1
        elif character in ">)" and not (character == ">" and offset and tail[offset - 1] == "-"):
            depth -= 1
        elif character == "," and depth == 0:
            parts.append(tail[start:offset])
            start = offset + 1
    return head, parts + [tail[start:]]


def inherited(api_type, types):
    seen = {(m["kind"], m["name"], tuple(p["type"] for p in m["parameters"])) for m in api_type["members"]}

    def walk(declaration):
        name, arguments = split_type_arguments(declaration)
        if name == "अपवाद":
            yield name, {"kind": "field", "name": "संदेश", "result": "पाठ",
                         "mutable": False, "shared": False}
            return
        if name not in types:
            return
        parent = types[name]
        substitutions = dict(zip(parent["typeParameters"], arguments))

        def substitute(text):
            return re.sub(r"[\w\u0900-\u097f]+", lambda match: substitutions.get(match[0], match[0]), text)

        for member in parent["members"]:
            if member["kind"] == "constructor":
                continue
            params = [dict(p, type=substitute(p["type"])) for p in member["parameters"]]
            key = member["kind"], member["name"], tuple(p["type"] for p in params)
            if key not in seen:
                seen.add(key)
                yield name, dict(member, parameters=params, result=substitute(member["result"]))
        for ancestor in parent["parents"]:
            yield from walk(substitute(ancestor))

    for parent in api_type["parents"]:
        yield from walk(parent)


def method_row(methods, owner, member, inherited_from=""):
    english, description = describe(methods, owner, member)
    name = f"{code(member['name'])} ({english})"
    modifiers = ["साझा" if member["shared"] else "सदस्य"]
    result = code(member["result"])
    if member["async"]:
        modifiers.append("असमकालिक")
        result += " · प्रतीक्षा का फल"
    if member["name"].startswith("आंतरिक"):
        modifiers.append("रनटाइम")
    if inherited_from:
        description = inherited_from + " · " + description
    return f"| {name} | {', '.join(modifiers)} | {code(parameters(member)) if member['parameters'] else '—'} | {result} | {description} |"


def field_row(descriptions, owner, member, origin=""):
    access = "पढ़ना और लिखना" if member["mutable"] else "केवल पढ़ना"
    shared = "साझा" if member["shared"] else "सदस्य"
    if member["name"].startswith("आंतरिक"):
        access += " · रनटाइम के लिए"
    name = member["name"]
    description = (descriptions.get(f"{owner}.{name}") or descriptions[name])[0]
    return f"| {code(name)} | {code(member['result'])} | {shared} | {access}{origin} | {description} |"


def example_sources():
    roots = ["tutorial", "examples", "book", "src/test/resources", "docs", "src/main/resources/dev/bhash/stdlib"]
    paths = []
    for rank, folder in enumerate(roots):
        paths.extend((5 if path == RUNTIME_EXAMPLES else rank, path) for path in sorted((ROOT / folder).rglob("*"))
                     if not path.is_relative_to(OUTPUT) and path.suffix in {".bhash", ".md"})
    for rank, path in paths:
        text = path.read_text(encoding="utf-8-sig")
        blocks = [text] if path.suffix == ".bhash" else re.findall(r"^\x60{3}bhash[^\n]*\n([\s\S]*?)^\x60{3}\s*$", text, re.M)
        for number, block in enumerate(blocks):
            yield rank, path.relative_to(ROOT).as_posix(), number, block, block.splitlines()


@lru_cache
def mentions(name):
    return re.compile(r"(?<![\w\u0900-\u097f])" + re.escape(name) + r"(?![\w\u0900-\u097f])")


def excerpt_score(name, line):
    if not mentions(name).search(line) or line.startswith(("आयात ", "संकुल ", "//", "/*", "*")):
        return 0
    if re.search(r"(वर्ग|अनुबंध|अभिलेख)\s+" + re.escape(name) + r"(?:\s|[<{(]|$)", line):
        return 0
    if re.search(re.escape(name) + r"(?:<[^;]*?>)?\s*\.\s*[\w\u0900-\u097f]+\s*\(", line):
        return 10
    if re.search(r"नया\s+" + re.escape(name) + r"\s*[<(]", line):
        return 9
    if line.startswith("पकड़ो") or "पकड़ो" in line or re.search(r"(पालन|विस्तार)\s+" + re.escape(name), line):
        return 7
    return 5


def examples_for(name, sources, prefer):
    candidates = []
    for rank, source, block, text, lines in sources:
        selectors = [(i, marker) for i, (file, marker) in enumerate(prefer) if file == source]
        if not selectors and not mentions(name).search(text):
            continue
        for index, raw in enumerate(lines):
            line = raw.strip()
            preferred = next((i for i, marker in selectors if not marker or marker in line), len(prefer))
            selected_line = preferred < len(prefer) and prefer[preferred][1]
            score = 20 if selected_line else excerpt_score(name, line)
            if not score or len(line) > 420:
                continue
            # Keep a short block intact and include the operation following a binding.
            variable = re.search(r"(?:स्थिर\s+|मान\s+|संसाधन\s*\(\s*)([\w\u0900-\u097f]+)\s*:", line)
            excerpt = [raw]
            if line.endswith("{"):
                indent = raw[:len(raw) - len(raw.lstrip())]
                for end in range(index + 1, min(index + 15, len(lines))):
                    if re.fullmatch(re.escape(indent) + r"\}[);,]*", lines[end]):
                        block_lines = lines[index:end + 1]
                        if max(map(len, block_lines)) <= 280:
                            excerpt = block_lines
                            score += 2
                        break
            elif variable:
                variable_use = mentions(variable[1])
                for follow in lines[index + 1:index + 5]:
                    if not follow.strip() or len(follow.strip()) > 280:
                        break
                    excerpt.append(follow)
                    if variable_use.search(follow):
                        score += 3
                        break
                if not any(variable_use.search(follow) for follow in excerpt[1:]):
                    excerpt = [raw]
            candidates.append(Example(preferred, rank, source.endswith(".md"), -score, source,
                                      block, index, textwrap.dedent("\n".join(excerpt))))
    candidates.sort()
    selected = []
    for candidate in candidates:
        if (not selected or candidate.source != selected[0].source
                or selected[0].preference < candidate.preference < len(prefer)):
            selected.append(candidate)
            if len(selected) == 2:
                return selected
    if selected:
        first = selected[0]
        for candidate in candidates:
            if (candidate.excerpt != first.excerpt and
                    (candidate.block != first.block or abs(candidate.line - first.line) > 1)):
                return [first, candidate]
    return selected


def type_link(page, name, label=None):
    return link(page, OUTPUT.relative_to(ROOT) / "types" / f"{name}.md", label or code(name))


def render_type(page, api_type, types, meta, methods, descriptions, notes, examples):
    name = api_type["name"]
    english, introduction, *internal = meta[name]
    generic = "<" + ", ".join(api_type["typeParameters"]) + ">" if api_type["typeParameters"] else ""
    title = name + generic
    members = api_type["members"]
    ancestors = list(inherited(api_type, types))
    out = [INTRO.rstrip(), "", f"# {code(title)} ({english})", "",
           link(page, OUTPUT.relative_to(ROOT) / "README.md", "सभी प्रकार"), "",
           introduction, "", f"**प्रकार** · {KINDS[api_type['kind']]} · {code('मानक.' + title)}", ""]
    if internal:
        out += ["**रनटाइम का सहायक प्रकार।** इसके सीधे उपयोग के लिए नीचे पुस्तकालय के कार्यान्वयन के अंश देखें।", ""]
    if api_type["parents"]:
        base_links = []
        for parent in api_type["parents"]:
            base = plain_type(parent)
            base_links.append(type_link(page, base, code(parent)) if base in types else link(page, "docs/exceptions.md", code(parent)))
        out += ["**आधार प्रकार और अनुबंध** · " + ", ".join(base_links), ""]
    guide = GUIDES.get(name, GROUPS[api_type["source"]][1])
    out += [link(page, guide, "व्यवहार, लक्ष्य और चलाने की तैयारी"), ""]
    if name in notes:
        out += ["**उपयोग का अनुबंध** · " + notes[name][0], ""]
    out += ["## निर्माता (Constructors)", ""]
    constructors = [m for m in members if m["kind"] == "constructor"]
    if constructors:
        out += ["| निर्माण | मापदंड (नाम और प्रकार) | बना मान |", "| --- | --- | --- |"]
        for member in constructors:
            args = parameters(member)
            call = "नया " + title + "(" + ", ".join(p["name"] for p in member["parameters"]) + ")"
            out.append(f"| {code(call)} | {code(args) if args else '—'} | {code(title)} |")
        out.append("")
    elif api_type["kind"] == "interface":
        out += ["इस अनुबंध को किसी वर्ग में लागू करें।", ""]
    else:
        out += ["यहाँ कोई सार्वजनिक निर्माता उपलब्ध नहीं है। साझा फलन या संबंधित फ़ैक्टरी से शुरू करें।", ""]
    out += ["## सार्वजनिक फलन (Methods)", ""]
    own_methods = [m for m in members if m["kind"] == "method"]
    inherited_methods = [(origin, m) for origin, m in ancestors if m["kind"] == "method"]
    if own_methods or inherited_methods:
        out += ["| फलन | बुलाने का रूप | मापदंड (नाम और प्रकार) | फल (प्रकार) | व्यवहार |", "| --- | --- | --- | --- | --- |"]
        out += [method_row(methods, name, m) for m in own_methods]
        out += [method_row(methods, origin, m, type_link(page, origin)) for origin, m in inherited_methods]
        out.append("")
        if any(m["result"] == "शून्य" for m in own_methods) or any(m["result"] == "शून्य" for _, m in inherited_methods):
            out += [f"{code('शून्य')} वाले फलन कोई मान नहीं लौटाते।", ""]
    else:
        out += ["यह प्रकार कोई सार्वजनिक फलन घोषित या प्राप्त नहीं करता।", ""]
    fields = [field_row(descriptions, name, m) for m in members if m["kind"] == "field"]
    fields += [field_row(descriptions, origin, m, " · " + (type_link(page, origin) if origin in types else
               link(page, "docs/exceptions.md", code(origin)))) for origin, m in ancestors if m["kind"] == "field"]
    if fields:
        out += ["## सार्वजनिक क्षेत्र (Fields)", "", "| क्षेत्र | प्रकार | रूप | पहुँच | अर्थ |",
                "| --- | --- | --- | --- | --- |", *fields, ""]
    out += ["## उपयोग के उदाहरण (Usage examples)", "",
            "ये कोड अंश हैं। आयात, आरंभिक मान और चलाने की तैयारी के लिए जुड़ा स्रोत या मार्गदर्शिका खोलें।", ""]
    for index, example in enumerate(examples, 1):
        role = "रनटाइम का अंश" if example.source_rank == 5 else "संबंधित पूरा स्रोत या ट्यूटोरियल"
        label = f"{role} · {Path(example.source).name}"
        out += [f"### उदाहरण {index}", "", link(page, example.source, label), "",
                "\x60\x60\x60bhash", example.excerpt, "\x60\x60\x60", ""]
    return "\n".join(out)


def render_index(page, types, meta):
    groups = defaultdict(list)
    for api_type in types:
        groups[api_type["source"]].append(api_type)
    out = [INTRO.rstrip(), "", "# मानक पुस्तकालय की प्रकार सूची (Standard Library API)", "",
           f"भाष संकलक के {code('मानक')} संकुल में {len(types)} सार्वजनिक प्रकार हैं। हर नाम से उसके निर्माता, फलन, मापदंड, फल, क्षेत्र और कोड उदाहरण खोलें।", "",
           f"आम तौर पर {code('आयात मानक.प्रकार;')} से नाम आयात करें। साझा फलन के लिए प्रकार का नाम, और सदस्य फलन के लिए बनी वस्तु का नाम उपयोग करें।", "",
           "**रनटाइम** चिह्न वाले प्रकार संकलक या पुस्तकालय की अंदरूनी व्यवस्था के लिए हैं।", "",
           "## विषय (Topics)", ""]
    for index, (source, (name, _)) in enumerate(GROUPS.items(), 1):
        out.append(f"- [{name}](#विषय-{index})")
    out += ["", "## भाषा में पहले से मिले प्रकार (Built-in Types)", "",
            link(page, "docs/collections.md", "सरणी, सूची, मानचित्र और बाइटबफ़र") + " · " + link(page, "docs/exceptions.md", "अपवाद"), ""]
    for index, (source, (name, guide)) in enumerate(GROUPS.items(), 1):
        out += [f'<a id="विषय-{index}"></a>', "", f"## {name}", "", link(page, guide, "विषय की मार्गदर्शिका"), "",
                "| नाम (English) | रूप | काम |", "| --- | --- | --- |"]
        for api_type in groups[source]:
            name = api_type["name"]
            english, summary, *internal = meta[name]
            label = type_link(page, name, f"{code(name)} ({english})")
            kind = KINDS[api_type["kind"]] + (" · रनटाइम" if internal else "")
            out.append(f"| {label} | {kind} | {summary} |")
        out.append("")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--check", action="store_true", help="fail if checked-in pages differ")
    parser.add_argument("--test-examples", action="append", choices=("jvm", "clr", "native"), default=[],
                        metavar="TARGET", help="run the new reference example programs on this target")
    args = parser.parse_args()
    api = inventory(args.jar.resolve())
    types = {t["name"]: t for t in api}
    if len(types) != len(api):
        raise ValueError("Duplicate exported types in compiler inventory")
    meta = rows("types.txt", 3, 4)
    methods = rows("methods.txt", 3, 3)
    descriptions = rows("fields.txt", 2, 2)
    notes = rows("notes.txt", 2, 2)
    if set(meta) != set(types):
        raise ValueError(f"Update types.txt: missing={set(types) - set(meta)} extra={set(meta) - set(types)}")
    for values in meta.values():
        if len(values) == 3 and values[2] != "internal":
            raise ValueError(f"Unknown reference audience: {values}")
    preferences = {name: [(source.split("::", 1) + [""])[:2] for source in examples]
                   for name, examples in rows("examples.txt", 2, 3).items()}
    if (set(notes) | set(preferences)) - set(types):
        raise ValueError("Notes or examples reference a type that is no longer public")
    for choices in preferences.values():
        for path, marker in choices:
            if not (ROOT / path).is_file():
                raise ValueError(f"Missing preferred example: {path}")
            if marker and marker not in (ROOT / path).read_text(encoding="utf-8-sig"):
                raise ValueError(f"Missing example selector {marker!r} in {path}")
    sources = list(example_sources())
    pages = {OUTPUT / "README.md": render_index(OUTPUT / "README.md", api, meta)}
    missing = []
    for api_type in api:
        name = api_type["name"]
        examples = examples_for(name, sources, preferences.get(name, ()))
        if len(examples) != 2:
            missing.append(f"{name} ({len(examples)})")
        page = OUTPUT / "types" / f"{name}.md"
        pages[page] = render_type(page, api_type, types, meta, methods, descriptions, notes, examples)
    if missing:
        raise ValueError("Two usage examples required per type: " + ", ".join(missing))
    existing = {p for p in OUTPUT.rglob("*.md")}
    stale = sorted(existing - set(pages))
    if stale:
        raise ValueError("Remove obsolete API pages: " + ", ".join(str(p.relative_to(ROOT)) for p in stale))
    changed = []
    for path, content in pages.items():
        if not path.is_file() or path.read_text(encoding="utf-8") != content:
            changed.append(path)
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8", newline="\n")
    if args.check and changed:
        raise SystemExit("Regenerate the API reference: " + ", ".join(str(p.relative_to(ROOT)) for p in changed))
    members = sum(len(t["members"]) for t in api)
    print(f"Standard library reference: {len(types)} types, {members} declared public members, {len(types) * 2} usage excerpts.")
    for target in args.test_examples:
        test_examples(args.jar.resolve(), target)


def test_examples(jar, target):
    examples = DATA / "examples"
    for program in sorted(examples.glob("*.bhash")):
        expected = program.with_suffix(".expected.txt").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="library-example-", dir=ROOT / "target") as scratch:
            run = subprocess.run(
                ["java", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={scratch}",
                 "-jar", str(jar), "run", str(program), "--target", target],
                cwd=scratch, capture_output=True, timeout=180,
            )
        actual = run.stdout.decode("utf-8").replace("\r\n", "\n")
        if run.returncode or actual != expected:
            raise ValueError(f"{program.name} ({target}): {actual!r} != {expected!r}\n"
                             + run.stderr.decode("utf-8", errors="replace"))
        print(f"PASS {target}: {program.name}")


if __name__ == "__main__":
    main()
