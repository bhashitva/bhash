#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify and combine the three independently tested provider resource catalogs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

HOSTS = {"linux-x64", "windows-x64", "macos-arm64"}


def digest(file):
    with file.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def contained(root, relative, limit):
    file = root / "artifacts" / relative
    if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(root.resolve()) or file.stat().st_size > limit:
        raise ValueError("Invalid provider file: " + str(file))
    return file


def merge(inputs, output):
    if output.exists():
        raise ValueError("Merged output must be a new directory")
    contracts, cores, targets, artifacts = set(), set(), set(), []
    with tempfile.TemporaryDirectory(prefix=".ffi-merge-", dir=output.parent) as temporary:
        stage = Path(temporary) / "catalog"
        stage.mkdir()
        for root in inputs:
            file = root / "compression-artifacts.json"
            if file.stat().st_size > 262144:
                raise ValueError("Oversized provider catalog")
            catalog = json.loads(file.read_text(encoding="utf-8"))
            if set(catalog) != {"schemaVersion", "contractSha256", "coreSha256", "artifacts"} or catalog["schemaVersion"] != 1:
                raise ValueError("Invalid provider catalog schema")
            for key in ("contractSha256", "coreSha256"):
                if not isinstance(catalog[key], str) or not re.fullmatch("[a-f0-9]{64}", catalog[key]):
                    raise ValueError("Invalid provider identity")
            contracts.add(catalog["contractSha256"])
            cores.add(catalog["coreSha256"])
            for artifact in catalog["artifacts"]:
                host, kind = artifact["target"], artifact["kind"]
                if host not in HOSTS or kind not in {"static", "shared"} or (host, kind) in targets:
                    raise ValueError("Unexpected or duplicate provider host/kind")
                targets.add((host, kind))
                if not re.fullmatch(re.escape(host) + r"/[a-zA-Z0-9_.-]+", artifact["path"]) or artifact["license"] != host + "/LICENSE.txt":
                    raise ValueError("Unsafe provider path")
                binary = contained(root, artifact["path"], 134217728)
                if digest(binary) != artifact["sha256"]:
                    raise ValueError("Changed provider binary")
                license_file = contained(root, artifact["license"], 1048576)
                if not license_file.stat().st_size:
                    raise ValueError("Empty provider license")
                provenance = contained(root, host + "/provenance.json", 65536)
                info = json.loads(provenance.read_text(encoding="utf-8"))
                if info["abi"] != 1 or info["contractSha256"] != catalog["contractSha256"] or info["coreSha256"] != catalog["coreSha256"] or info["sourceSha256"] != artifact["sourceSha256"]:
                    raise ValueError("Provider provenance does not match catalog")
                if kind == "static" and info["archiveSha256"] != artifact["sha256"]:
                    raise ValueError("Provider provenance archive hash mismatch")
                destination = stage / "artifacts" / host
                destination.mkdir(parents=True, exist_ok=True)
                for source in (binary, license_file, provenance):
                    target = destination / source.name
                    if target.exists() and digest(target) != digest(source):
                        raise ValueError("Conflicting provider metadata")
                    shutil.copyfile(source, target)
                artifacts.append(artifact)
        if len(contracts) != 1 or len(cores) != 1 or targets != {(host, kind) for host in HOSTS for kind in ("static", "shared")}:
            raise ValueError("A release needs one matching contract/runtime and all six host artifacts")
        catalog = {"schemaVersion": 1, "contractSha256": contracts.pop(), "coreSha256": cores.pop(),
                   "artifacts": sorted(artifacts, key=lambda artifact: (artifact["target"], artifact["kind"]))}
        (stage / "compression-artifacts.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        shutil.move(str(stage), output)


def bundle(root, archive):
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for file in sorted(root.rglob("*")):
            if file.is_file():
                info = zipfile.ZipInfo(file.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                output.writestr(info, file.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--zip", type=Path)
    args = parser.parse_args()
    merge(args.inputs, args.output)
    if args.zip:
        bundle(args.output, args.zip)
        args.zip.with_name(args.zip.name + ".sha256").write_text(digest(args.zip) + "  " + args.zip.name + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
