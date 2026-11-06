"""Export the Hindi book and its offline companion from a committed revision."""

import argparse
import base64
import hashlib
import io
import json
import mimetypes
import posixpath
import re
import subprocess
import tarfile
import tempfile
import unicodedata
from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import quote, unquote, urlsplit
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

import mistune


ROOT = Path(__file__).resolve().parents[1]
GITHUB = "https://github.com/bhashitva/bhash"
CONTENTS = "book/README.md"
TREES = ("book", "docs", "examples", "lessons", "tutorial", "editors/jupyter",
         "editors/vscode", "licenses")
FILES = ("README.md", "SUPPORT.md", "LICENSING.md", "LICENSE", "NOTICE",
         "THIRD_PARTY_NOTICES.md")
EXAMPLE_TREES = ("book/examples/", "examples/", "lessons/", "tutorial/")
EXAMPLE_METADATA = ("coverage.json", "expected-keywords.txt")
REPO_FILE = re.compile(r"^/bhashitva/bhash/(?:blob|tree|raw)/(.+)$")


def git(*args):
    return subprocess.check_output(("git", "-C", str(ROOT), *args))


def snapshot(revision):
    archive = git("archive", "--format=zip", revision)
    with ZipFile(io.BytesIO(archive)) as source:
        return {name: source.read(name) for name in source.namelist()
                if not name.endswith("/")}


def relative(start, end):
    return quote(posixpath.relpath(end, posixpath.dirname(start) or "."))


def slug(text):
    text = unescape(re.sub(r"<[^>]*>", "", text)).lower()
    return "".join("-" if ch == " " else ch for ch in text
                   if ch in " -_" or unicodedata.category(ch)[0] in "LMN")


class Book:
    def __init__(self, files, revision, version):
        self.files = files
        self.revision = revision
        self.version = version
        self.chapters = re.findall(
            r"^\| \[[^\]\n]+\]\((\d\d-[a-z-]+\.md)\) \|",
            files[CONTENTS].decode(), re.M,
        )
        if not self.chapters or len(self.chapters) != len(set(self.chapters)):
            raise ValueError("Expected unique chapter entries in the contents table")
        self.sections = {CONTENTS: "contents"}
        self.sections.update({f"book/{name}": PurePosixPath(name).stem
                              for name in self.chapters})
        self.directories = {str(parent) for name in files for parent in PurePosixPath(name).parents}
        self.companion_paths = {name for name in files if name in FILES
                                or name.startswith(tuple(tree + "/" for tree in TREES))}
        self.references = {}

    def destination(self, target):
        if target in self.sections:
            return self.sections[target]
        return self.references.setdefault(target, "ref-" + hashlib.sha256(target.encode()).hexdigest()[:20])

    def pdf_target(self, source, parsed):
        if parsed.netloc.lower() == "github.com":
            match = REPO_FILE.fullmatch(unquote(parsed.path))
            if match:
                parts = match[1].split("/")
                if parts[:2] == ["refs", "heads"]:
                    parts = parts[2:]
                for index in range(1, len(parts)):
                    target = "/".join(parts[index:])
                    if target in self.files or target in self.directories:
                        return target
                raise ValueError(f"{source}: cannot find repository file {parsed.geturl()}")
        if parsed.scheme or parsed.netloc:
            return None
        if not parsed.path:
            return source
        return posixpath.normpath(posixpath.join(posixpath.dirname(source), unquote(parsed.path)))

    def repository_url(self, target, fragment=""):
        kind = "tree" if any(name.startswith(target + "/") for name in self.files) else "blob"
        return f"{GITHUB}/{kind}/{self.revision}/{quote(target)}{fragment}"

    def link(self, source, output, url, pdf):
        parsed = urlsplit(url)
        target = self.pdf_target(source, parsed) if pdf else (
            None if parsed.scheme or parsed.netloc else self.pdf_target(source, parsed))
        if target is None:
            return url if parsed.scheme in ("https", "http", "mailto") else ""
        if target.startswith(("../", "/")) or target == "..":
            raise ValueError(f"{source}: link escapes the repository: {url}")
        readme = posixpath.normpath(posixpath.join(target, "README.md"))
        if target not in self.files and readme in self.files:
            target = readme
        fragment = "#" + quote(unquote(parsed.fragment)) if parsed.fragment else ""
        if pdf:
            if target not in self.files and target not in self.directories:
                raise ValueError(f"{source}: missing PDF reference {url}")
            return "#" + self.destination(target) + ("--" + fragment[1:] if fragment else "")
        if target in self.companion_paths:
            if target.endswith(".md"):
                target = target[:-3] + ".html"
            return relative(output, target) + fragment
        if source in self.sections:
            raise ValueError(f"{source}: missing companion target {url}")
        return self.repository_url(target, fragment)

    def render(self, source, output, pdf=False, text=None, shift_headings=0):
        book = self
        prefix = self.destination(source) + "--" if pdf else ""

        class Renderer(mistune.HTMLRenderer):
            def __init__(self):
                super().__init__(escape=True)
                self.ids = set()

            def heading(self, text, level, **attrs):
                base = slug(text)
                unique = base
                suffix = 1
                while unique in self.ids:
                    unique = f"{base}-{suffix}"
                    suffix += 1
                self.ids.add(unique)
                level = min(level + shift_headings, 6)
                return f'<h{level} id="{escape(prefix + unique)}">{text}</h{level}>\n'

            def block_html(self, html):
                anchor = re.fullmatch(r'\s*<a id="([\w-]+)"></a>\s*', html)
                if anchor:
                    return f'<span id="{escape(prefix + anchor[1])}"></span>\n'
                return super().block_html(html)

            def paragraph(self, text):
                anchor = re.fullmatch(r'<a id="([\w-]+)"></a>', unescape(text))
                if anchor:
                    return f'<span id="{escape(prefix + anchor[1])}"></span>\n'
                return super().paragraph(text)

            def link(self, text, url, title=None):
                href = book.link(source, output, url, pdf)
                if pdf and href.startswith(("https:", "http:")):
                    text += ' <span class="online">(ऑनलाइन)</span>'
                return super().link(text, href, title)

            def image(self, text, url, title=None):
                href = book.link(source, output, url, pdf)
                # Reference pages may point at images hosted outside the companion.
                if pdf or not href or href.startswith(("http:", "https:")):
                    return super().link(text or "चित्र", href, title)
                return super().image(text, href, title)

        markdown = mistune.create_markdown(renderer=Renderer(), plugins=["table", "strikethrough"])
        if text is None:
            text = self.files[source].decode("utf-8-sig")
        if pdf and source == CONTENTS:
            text = re.sub(r"^## ऑफ़लाइन संस्करण\n.*?(?=^## )", "", text, flags=re.M | re.S)
        return markdown(text)

    def reference_body(self, source):
        if source not in self.files:
            prefix = "" if source == "." else source + "/"
            children = sorted({name[len(prefix):].split("/")[0]
                               for name in self.files if name.startswith(prefix)})
            return "<ul>" + "".join(
                f'<li><a href="{self.link(source + "/index", "", quote(name), True)}">'
                f'{escape(name)}</a></li>' for name in children) + "</ul>"
        if source.endswith(".md"):
            return self.render(source, "print.html", pdf=True, shift_headings=2)
        if source.endswith(".ipynb"):
            parts = []
            for cell in json.loads(self.files[source])["cells"]:
                text = "".join(cell["source"])
                if cell["cell_type"] == "markdown":
                    parts.append(self.render(source, "print.html", pdf=True, text=text, shift_headings=2))
                elif text.strip() or cell.get("outputs"):
                    parts.append(f'<pre><code>{escape(text)}</code></pre>')
                    for output in cell.get("outputs", []):
                        text = "".join(output.get("text", output.get("data", {}).get("text/plain", [])))
                        if text:
                            parts.append(f'<pre><code>{escape(text)}</code></pre>')
            return "".join(parts)
        media_type = mimetypes.guess_type(source)[0]
        if media_type in {"image/png", "image/jpeg", "image/gif", "image/webp"}:
            data = base64.b64encode(self.files[source]).decode("ascii")
            return f'<img src="data:{media_type};base64,{data}" alt="{escape(source)}">'
        text = self.files[source].decode("utf-8-sig")
        if "\0" in text:
            raise ValueError(f"Cannot print binary reference {source}")
        return f'<pre class="source-listing"><code>{escape(text)}</code></pre>'

    def document(self, body, output, title):
        css = relative(output, "assets/book.css")
        return (f'<!doctype html><html lang="hi"><meta charset="utf-8">'
                f'<meta name="viewport" content="width=device-width, initial-scale=1">'
                f'<title>{escape(title)}</title><link rel="stylesheet" href="{css}">'
                f'<main>{body}</main></html>\n').encode()

    def print_document(self):
        body = (f'<header class="cover"><p>हिन्दी संस्करण · {self.version}</p>'
                '<h1>भाष में प्रोग्रामिंग</h1><p class="subtitle">Programming with Bhash</p>'
                '<p>पहले क्रमानुदेश से पूरी वेब सेवा तक</p>'
                f'<p class="revision">स्रोत संशोधन <code>{self.revision}</code></p></header>'
                '<p class="edition-note">अध्याय, संदर्भ और उदाहरण इसी PDF में खुलते हैं। '
                '<a href="#reference-index">परिशिष्ट की सूची</a> से सीधे किसी फ़ाइल पर जाएँ। '
                'केवल बाहरी वेबसाइट और सेवाएँ ऑनलाइन चिह्नित हैं।</p>')
        for source in (CONTENTS, *(f"book/{name}" for name in self.chapters)):
            body += (f'<section class="chapter" id="{self.sections[source]}">'
                     f'{self.render(source, "print.html", pdf=True)}</section>')
        for name in sorted(self.files):
            if name.startswith(EXAMPLE_TREES) and posixpath.basename(name) not in EXAMPLE_METADATA:
                self.destination(name)
        rendered = {}
        while pending := sorted(self.references.keys() - rendered.keys()):
            for name in pending:
                rendered[name] = self.reference_body(name)
        groups = (
            ("संदर्भ और ट्यूटोरियल", sorted(name for name in rendered if name.endswith((".md", ".ipynb")))),
            ("स्रोत कोड, प्रोजेक्ट फ़ाइलें और अपेक्षित परिणाम",
             sorted(name for name in rendered if not name.endswith((".md", ".ipynb")))),
        )
        body += ('<section class="chapter" id="reference-index"><h1>परिशिष्ट की सूची</h1>'
                 '<p><a href="#contents">पुस्तक की विषय सूची</a></p>')
        for label, names in groups:
            body += f'<h2>{label}</h2><ul class="reference-index">'
            body += "".join(f'<li><a href="#{self.references[name]}">{escape(name)}</a></li>'
                            for name in names)
            body += "</ul>"
        body += "</section>"
        for label, names in groups:
            body += f'<section class="chapter"><h1>{label}</h1>'
            for name in names:
                kind = "reading" if name.endswith((".md", ".ipynb")) else "listing"
                body += (f'<section class="reference {kind}" id="{self.references[name]}">'
                         f'<h2>{escape(name)}</h2>'
                         '<p class="reference-nav"><a href="#reference-index">परिशिष्ट की सूची</a>'
                         ' · <a href="#contents">पुस्तक की विषय सूची</a></p>'
                         f'{rendered[name]}</section>')
            body += "</section>"
        print(f"PDF appendix: {len(groups[0][1])} reading references and {len(groups[1][1])} source/support files")
        return self.document(body, "print.html", "भाष में प्रोग्रामिंग")

    def companion(self):
        result = {name: self.files[name] for name in self.companion_paths}
        for source in sorted(self.companion_paths):
            if source.endswith(".md"):
                output = source[:-3] + ".html"
                result[output] = self.document(self.render(source, output), output,
                                               PurePosixPath(source).name)
        body = (f'<h1>भाष में प्रोग्रामिंग</h1><p>ऑफ़लाइन साथी · संस्करण {self.version}</p>'
                '<p><a href="book/README.html">विषय सूची खोलें</a></p>'
                '<p>पहले ZIP की सभी फ़ाइलें एक ही फ़ोल्डर में निकालें। ट्यूटोरियल और '
                'उदाहरणों के लिंक वहीं खुलते हैं। उदाहरण चलाने के लिए भाष और संबंधित '
                'लक्ष्य का रनटाइम अलग से स्थापित करें। नेटवर्क सेवा वाले अभ्यासों '
                'के लिए नेटवर्क या स्थानीय सेवा आवश्यक हो सकती है।</p>'
                f'<p>स्रोत संशोधन <code>{self.revision}</code></p>')
        result["index.html"] = self.document(body, "index.html", "भाष · ऑफ़लाइन पुस्तक")
        return result


class HtmlLinks(HTMLParser):
    def __init__(self, data):
        super().__init__()
        self.ids = set()
        self.links = set()
        self.feed(data.decode())

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.add(attrs["id"])
        self.links.update(attrs[key] for key in ("href", "src") if attrs.get(key))


def check_links(files, pages):
    documents = {page: HtmlLinks(files[page]) for page in pages}
    for page in pages:
        for link in sorted(documents[page].links):
            parsed = urlsplit(link)
            if parsed.scheme:
                continue
            target = (posixpath.normpath(posixpath.join(posixpath.dirname(page),
                      unquote(parsed.path))) if parsed.path else page)
            if target not in files:
                raise ValueError(f"{page}: missing local link {link}")
            if (parsed.fragment and target in documents
                    and unquote(parsed.fragment) not in documents[target].ids):
                raise ValueError(f"{page}: missing anchor {link}")


def assets(font_package, css):
    files = {"assets/book.css": css}
    with tarfile.open(font_package, "r:gz") as package:
        for name in ("LICENSE", "files/noto-sans-devanagari-devanagari-400-normal.woff2",
                     "files/noto-sans-devanagari-devanagari-700-normal.woff2"):
            files["assets/" + PurePosixPath(name).name] = package.extractfile("package/" + name).read()
    return files


def print_pdf(files, path):
    from playwright.sync_api import sync_playwright
    from pypdf import PdfReader

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for name, data in files.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            page = browser.new_page(offline=True)
            page.goto((root / "print.html").as_uri())
            loaded = page.evaluate("""async () => {
                await document.fonts.ready;
                return [400, 700].every(weight => [...document.fonts].some(font =>
                    font.family === 'Book Hindi' && font.weight === String(weight)
                    && font.status === 'loaded'));
            }""")
            if not loaded:
                raise ValueError("The embedded Hindi fonts did not load")
            page.pdf(path=path, prefer_css_page_size=True, print_background=True,
                     tagged=True, outline=True)
            browser.close()

    reader = PdfReader(path)
    expected = {unquote(link[1:]) for link in HtmlLinks(files["print.html"]).links
                if link.startswith("#")}
    destinations = {unquote(name.lstrip("/")): dest for name, dest in reader.named_destinations.items()}
    hindi_fonts = set()
    for page in reader.pages:
        for entry in page["/Resources"].get("/Font", {}).get_object().values():
            font = entry.get_object()
            if "NotoSansDevanagari" not in font.get("/BaseFont", ""):
                continue
            descendants = font.get("/DescendantFonts", [])
            descendant = descendants[0].get_object() if descendants else font
            descriptor = descendant.get("/FontDescriptor")
            if (descriptor and font.get("/ToUnicode") and
                    any(key in descriptor.get_object() for key in ("/FontFile", "/FontFile2", "/FontFile3"))):
                hindi_fonts.add(font["/BaseFont"])
    if not hindi_fonts:
        raise ValueError("The PDF is missing an embedded Devanagari font with a Unicode mapping")
    links = [annotation.get_object() for page in reader.pages
             for annotation in page.get("/Annots", [])]
    jumps = [link for link in links if link.get("/Dest") or
             link.get("/A", {}).get("/S") == "/GoTo"]
    linked = {unquote(str(link.get("/Dest") or link["/A"].get("/D")).lstrip("/"))
              for link in jumps}
    missing = expected - (destinations.keys() & linked)
    if missing:
        raise ValueError(f"PDF lost {len(missing)} internal destinations: {sorted(missing)[:8]}")
    web = [link["/A"]["/URI"] for link in links if link.get("/A", {}).get("/URI")]
    if not reader.outline or not jumps or not web or any(
            not url.startswith(("https://", "http://", "mailto:")) or
            (urlsplit(url).netloc.lower() == "github.com"
             and REPO_FILE.fullmatch(unquote(urlsplit(url).path)))
            for url in web):
        raise ValueError("PDF bookmarks, page jumps, or web link annotations are missing or invalid")
    print(f"PDF: {len(reader.pages)} pages, {len(jumps)} local jumps, {len(web)} web links, "
          f"embedded Hindi fonts: {', '.join(sorted(hindi_fonts))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Book edition, e.g. 1.0.0")
    parser.add_argument("--revision", default="HEAD")
    parser.add_argument("--font-package", required=True, type=Path)
    parser.add_argument("--output", default=ROOT / "target/book", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("--version must use MAJOR.MINOR.PATCH")
    revision = git("rev-parse", "--verify", "--end-of-options", args.revision + "^{commit}").decode().strip()
    book = Book(snapshot(revision), revision, args.version)
    font_files = assets(args.font_package, book.files["book/print.css"])
    companion = book.companion() | font_files
    pages = ["index.html", *(name[:-3] + ".html" for name in book.companion_paths if name.endswith(".md"))]
    check_links(companion, pages)
    print_files = {"print.html": book.print_document()} | font_files
    check_links(print_files, ["print.html"])
    args.output.mkdir(parents=True, exist_ok=True)
    stem = f"bhash-book-{args.version}-hi"
    pdf = args.output / f"{stem}.pdf"
    offline = args.output / f"{stem}-offline.zip"
    print_pdf(print_files, pdf)
    with ZipFile(offline, "w") as archive:
        for name, data in sorted(companion.items()):
            info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)
    with ZipFile(offline) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(companion):
            raise ValueError("The companion ZIP failed integrity verification")
    (args.output / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
        for path in (pdf, offline)), encoding="ascii", newline="\n")
    print(f"Companion: {len(companion)} files, built from {revision}")


if __name__ == "__main__":
    main()
