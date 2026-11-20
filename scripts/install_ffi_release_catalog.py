#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Unpack only a hash-pinned provider release into a fresh compiler resource tree."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("target/ffi-catalog"))
    args = parser.parse_args()
    if args.archive.stat().st_size > 134217728 or hashlib.sha256(args.archive.read_bytes()).hexdigest() != args.sha256:
        raise SystemExit("Provider release checksum mismatch")
    if args.output.exists():
        raise SystemExit("Provider output must be a fresh directory")
    with zipfile.ZipFile(args.archive) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or sum(entry.file_size for entry in entries) > 268435456:
            raise SystemExit("Invalid provider archive inventory")
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                raise SystemExit("Invalid provider archive path")
            if entry.filename != "compression-artifacts.json" and not entry.filename.startswith("artifacts/"):
                raise SystemExit("Unexpected provider archive member")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise SystemExit("Provider archives must not contain symbolic links")
        archive.extractall(args.output)
    print("Installed hash-pinned provider catalog:", args.output)


if __name__ == "__main__":
    main()
