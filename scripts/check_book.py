#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check local book links and optionally run its standalone Bhash examples."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
BOOK = ROOT / "book"
LINK = re.compile(r"\]\(([^)\s]+)\)")


def check_links():
    errors = []
    pages = sorted(BOOK.glob("*.md"))
    for page in pages:
        fenced = False
        for number, line in enumerate(page.read_text(encoding="utf-8").splitlines(), 1):
            if line.startswith("```"):
                fenced = not fenced
                continue
            if fenced:
                continue
            for match in LINK.finditer(line):
                href = urlsplit(match[1])
                if href.scheme or href.netloc or not href.path:
                    continue
                destination = (page.parent / unquote(href.path)).resolve()
                if not destination.is_relative_to(ROOT) or not destination.exists():
                    errors.append(f"{page.relative_to(ROOT)}:{number}: {match[1]}")
    if errors:
        raise SystemExit("Broken book links:\n" + "\n".join(errors))
    return len(pages)


def check_examples():
    examples = BOOK / "examples"
    sources = sorted(examples.glob("*.bhash"))
    expected = {file.name.removesuffix(".expected.txt") for file in examples.glob("*.expected.txt")}
    if not sources or {file.stem for file in sources} != expected:
        raise SystemExit("Each book example needs a matching .expected.txt file")
    return sources


def run_examples(sources, compiler, targets):
    if not compiler.is_file():
        raise SystemExit("Build the Bhash compiler or pass --compiler")
    output = ROOT / "target"
    output.mkdir(exist_ok=True)
    for target in targets:
        with TemporaryDirectory(prefix="book-", dir=output) as temporary:
            env = os.environ | {"TMP": temporary, "TEMP": temporary, "TMPDIR": temporary}
            for source in sources:
                expected = source.with_suffix(".expected.txt").read_text(encoding="utf-8")
                result = subprocess.run(
                    ["java", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={temporary}", "-jar", str(compiler),
                     "run", str(source), "--target", target],
                    cwd=ROOT, env=env, capture_output=True, encoding="utf-8", timeout=120,
                )
                if result.returncode != 0 or result.stdout != expected:
                    raise SystemExit(
                        f"{source.relative_to(ROOT)} ({target}): exit {result.returncode}\n"
                        f"Expected: {expected!r}\nActual: {result.stdout!r}\n{result.stderr}"
                    )
            print(f"Book examples passed ({target}): {len(sources)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, default=ROOT / "target/bhash-compiler-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--target", choices=["jvm", "clr", "native"], action="append", default=[])
    args = parser.parse_args()
    pages = check_links()
    sources = check_examples()
    print(f"Book links and example fixtures passed: {pages} pages, {len(sources)} examples")
    if args.target:
        run_examples(sources, args.compiler.resolve(), dict.fromkeys(args.target))


if __name__ == "__main__":
    main()
