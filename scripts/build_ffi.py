#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the private FFI provider from a verified, explicitly supplied zlib source archive.

No network access or dependency restore is performed by this script.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile

ZLIB_SHA256 = "02dd159783677a749c04adbdd02b55b8a2bcc7e054f51b76b549199a916ad584"
ZLIB_SOURCES = ("adler32 compress crc32 deflate infback inffast inflate inftrees trees uncompr zutil").split()


def run(argv):
    subprocess.run([str(x) for x in argv], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--cc", default="clang")
    parser.add_argument("--ar", default="llvm-ar")
    parser.add_argument("--output", type=Path, default=Path("target/ffi-build"))
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--shared", action="store_true", help="Also build the private shared provider for managed bridges")
    parser.add_argument("--java-home", type=Path, help="Build and run the Java-17 JNI/forced-GC conformance probe")
    parser.add_argument("--bindings", type=Path, help="Reviewed output of ffi prepare --builtin compression")
    parser.add_argument("--catalog", type=Path, help="Write a host-specific compiler resource catalog (requires --bindings and --java-home)")
    args = parser.parse_args()
    prepared = None
    if args.bindings:
        prepared = json.loads((args.bindings / "provider.json").read_text(encoding="utf-8"))
        expected = {"bhffi.h", "bhffi.c", "bhffi_compression.c", "zlib-LICENSE.txt", "gateway.c", "adapter.c", "declarations.bhash"}
        if prepared.get("schemaVersion") != 1 or prepared.get("abi") != 1 or prepared.get("builtinCompression") is not True or set(prepared.get("files", {})) != expected:
            raise SystemExit("unexpected compression producer metadata")
        for name, digest in prepared["files"].items():
            file = args.bindings / name
            if file.is_symlink() or not file.is_file() or file.stat().st_size > 1048576 or hashlib.sha256(file.read_bytes()).hexdigest() != digest:
                raise SystemExit("changed prepared source: " + name)
        if not args.java_home:
            raise SystemExit("--bindings requires --java-home for the JNI producer headers")
    if args.catalog and not prepared:
        raise SystemExit("--catalog requires --bindings")
    if args.archive.stat().st_size > 8388608 or hashlib.sha256(args.archive.read_bytes()).hexdigest() != ZLIB_SHA256:
        raise SystemExit("zlib archive checksum mismatch; nothing was extracted or built")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    temporary = output / ".temporary"
    temporary.mkdir(exist_ok=True)
    for name in ("TEMP", "TMP", "TMPDIR"):
        os.environ[name] = str(temporary)
    with tarfile.open(args.archive, "r:gz") as archive:
        # Never extract links or paths outside the verified source directory.
        for member in archive.getmembers():
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "zlib-1.4" or not (member.isfile() or member.isdir()):
                raise SystemExit("unexpected zlib archive member: " + member.name)
        archive.extractall(output, filter="data")
    vendor = output / "zlib-1.4"
    root = Path(__file__).resolve().parents[1]
    provider = root / "src/main/resources/dev/bhash/ffi"
    if prepared:
        provider = args.bindings.resolve()
    triple = subprocess.check_output([args.cc, "-dumpmachine"], text=True).strip()
    windows = "mingw" in triple or "windows" in triple
    macos = "darwin" in triple or "apple" in triple
    flags = ["-std=c17", "-O2", "-fvisibility=hidden", "-DZ_SOLO", "-I" + str(vendor), "-I" + str(provider)]
    if not windows:
        flags += ["-fPIC", "-pthread"]
    if macos:
        flags += ["-mmacosx-version-min=14.0"]
    # Z_PREFIX's reviewed list includes zlib's internal linked symbols and types.
    # Use our versioned namespace rather than the generic z_ prefix, including in
    # static archives where hidden visibility alone does not prevent collisions.
    prefix_lines, depth, found = [], 0, False
    for line in (vendor / "zconf.h").read_text(encoding="utf-8").splitlines():
        if not found:
            if re.match(r"#\s*ifdef\s+Z_PREFIX\b", line):
                found, depth = True, 1
            continue
        if re.match(r"#\s*(?:if|ifdef|ifndef)\b", line):
            depth += 1
        elif re.match(r"#\s*endif\b", line):
            depth -= 1
            if depth == 0:
                break
        prefix_lines.append(line)
    if not found or depth:
        raise SystemExit("Verified zlib prefix configuration was not found")
    names = set(re.findall(r"^#\s*define\s+(\w+)\s+z_\w+\s*$", "\n".join(prefix_lines), re.M))
    macros = set(re.findall(r"^#\s*define\s+(\w+)\(", (vendor / "zlib.h").read_text(encoding="utf-8"), re.M))
    names -= macros
    names.update(("z_errmsg", "zmemcpy", "zmemcmp", "zmemzero", "z_error", "z_verbose", "inflate9_copyright"))
    namespace = output / "zlib-namespace.h"
    namespace.write_text("/* Generated for the verified zlib 1.4 sources. */\n" + "".join(
        "#define " + name + " bhffi_compression_zlib_1_4_" + name + "\n" for name in sorted(names)), encoding="ascii")
    vendor_flags = [*flags, "-include", str(namespace)]
    objects = []
    for name in ZLIB_SOURCES:
        obj = output / ("zlib_" + name + ".o")
        run([args.cc, *vendor_flags, "-c", vendor / (name + ".c"), "-o", obj])
        objects.append(obj)
    bridge = output / "bhffi.o"
    run([args.cc, *flags, "-Wall", "-Wextra", "-Werror", "-c", provider / "bhffi.c", "-o", bridge])
    codec = output / "bhffi_compression.o"
    run([args.cc, *vendor_flags, "-Wall", "-Wextra", "-Werror", "-c", provider / "bhffi_compression.c", "-o", codec])
    library = output / "libbhash_ffi.a"
    # Recreate the archive so removed object members cannot survive a rebuild.
    library.unlink(missing_ok=True)
    if macos:
        os.environ["ZERO_AR_DATE"] = "1"
    run([args.ar, "rcs" if macos else "rcsD", library, bridge, codec, *objects])
    if args.shared or prepared:
        shared_bridge = output / "bhffi_shared.o"
        run([args.cc, *flags, "-DBHFFI_SHARED", "-Wall", "-Wextra", "-Werror", "-c", provider / "bhffi.c", "-o", shared_bridge])
        shared_codec = output / "bhffi_compression_shared.o"
        run([args.cc, *vendor_flags, "-DBHFFI_SHARED", "-Wall", "-Wextra", "-Werror", "-c", provider / "bhffi_compression.c", "-o", shared_codec])
        shared = output / ("bhash_ffi.dll" if windows else "libbhash_ffi.dylib" if macos else "libbhash_ffi.so")
        export_flags = ["-Wl,--exclude-all-symbols", "-Wl,--no-insert-timestamp"] if windows else ["-pthread"]
        if macos:
            export_flags += ["-Wl,-install_name,@rpath/libbhash_ffi.dylib", "-mmacosx-version-min=14.0"]
        adapters = []
        if prepared:
            jni_platform = "win32" if windows else "darwin" if macos else "linux"
            for name in ("gateway", "adapter"):
                obj = output / (name + ".o")
                run([args.cc, *flags, "-DBHFFI_SHARED", "-I" + str(args.java_home.resolve() / "include"),
                     "-I" + str(args.java_home.resolve() / "include" / jni_platform),
                     "-Wall", "-Wextra", "-Werror", "-c", provider / (name + ".c"), "-o", obj])
                adapters.append(obj)
        run([args.cc, "-shared", *export_flags, shared_bridge, shared_codec, *adapters, *objects, "-o", shared])
    shutil.copy2(vendor / "LICENSE", output / "zlib-LICENSE.txt")
    (output / "provenance.json").write_text(json.dumps({
        "abi": 1, "provider": "bhash-ffi", "zlib": "1.4", "sourceSha256": ZLIB_SHA256,
        "archiveSha256": hashlib.sha256(library.read_bytes()).hexdigest(),
        "adapterSha256": hashlib.sha256((provider / "bhffi.c").read_bytes()).hexdigest(),
        "compressionSha256": hashlib.sha256((provider / "bhffi_compression.c").read_bytes()).hexdigest(),
        "headerSha256": hashlib.sha256((provider / "bhffi.h").read_bytes()).hexdigest(),
        "recipeSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "compiler": subprocess.check_output([args.cc, "--version"], text=True).splitlines()[0],
        "flags": [flag if not flag.startswith("-I") else "-I<source>" for flag in flags],
        "contractSha256": prepared["contractSha256"] if prepared else None,
        "coreSha256": prepared["coreSha256"] if prepared else None,
        "generatedSources": prepared["files"] if prepared else None,
        "vendorNamespace": "bhffi_compression_zlib_1_4_",
        "namespaceHeaderSha256": hashlib.sha256(namespace.read_bytes()).hexdigest(),
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.catalog:
        host = "windows-x64" if windows else "macos-arm64" if macos else "linux-x64"
        # Refuse accidentally publishing another architecture under a supported host name.
        if (macos and not triple.startswith(("arm64", "aarch64"))) or (not macos and not triple.startswith("x86_64")):
            raise SystemExit("unsupported producer target: " + triple)
        catalog = args.catalog.resolve()
        destination = catalog / "artifacts" / host
        destination.mkdir(parents=True, exist_ok=True)
        artifacts = []
        for kind, file in (("static", library), ("shared", shared)):
            shutil.copy2(file, destination / file.name)
            artifacts.append({"target": host, "kind": kind, "path": host + "/" + file.name,
                              "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
                              "sourceSha256": ZLIB_SHA256, "license": host + "/LICENSE.txt"})
        notices = (output / "zlib-LICENSE.txt").read_text(encoding="utf-8")
        notices += "\nBhash FFI adapter: Apache-2.0\n\n" + (root / "LICENSE").read_text(encoding="utf-8")
        if windows:
            notices += "\n" + (root / "src/main/resources/dev/bhash/backend/native/MINGW_RUNTIME_NOTICES.txt").read_text(encoding="utf-8")
        (destination / "LICENSE.txt").write_text(notices, encoding="utf-8")
        shutil.copy2(output / "provenance.json", destination / "provenance.json")
        (catalog / "compression-artifacts.json").write_text(json.dumps({"schemaVersion": 1,
            "contractSha256": prepared["contractSha256"], "coreSha256": prepared["coreSha256"], "artifacts": artifacts}, indent=2) + "\n", encoding="utf-8")
    if args.test:
        collision = output / "symbol-collision.o"
        run([args.cc, *flags, "-Wall", "-Wextra", "-Werror", "-c", root / "src/test/c/ffi_symbol_collision.c", "-o", collision])
        for name in ("ffi_compression_test", "ffi_resources_test"):
            executable = output / (name + (".exe" if windows else ""))
            run([args.cc, *vendor_flags, "-DBHFFI_TESTING", "-Wall", "-Wextra", "-Werror",
                 provider / "bhffi.c", provider / "bhffi_compression.c", root / ("src/test/c/" + name + ".c"), *objects, collision, "-o", executable])
            run([executable])
    if args.java_home:
        jdk = args.java_home.resolve()
        suffix = ".exe" if windows else ""
        jni_platform = "win32" if windows else "darwin" if macos else "linux"
        jni = output / ("ffi_jni_probe.dll" if windows else "libffi_jni_probe.dylib" if macos else "libffi_jni_probe.so")
        run([args.cc, *flags, "-I" + str(jdk / "include"), "-I" + str(jdk / "include" / jni_platform),
             "-Wall", "-Wextra", "-Werror", "-shared", root / "src/test/c/ffi_jni_probe.c", library, "-o", jni])
        run([jdk / ("bin/javac" + suffix), "--release", "17", "-encoding", "UTF-8", "-d", output,
             root / "src/test/ffi/CompressionBridgeProbe.java"])
        run([jdk / ("bin/java" + suffix), "-Xcheck:jni", "-Xmx32m", "-cp", output, "CompressionBridgeProbe", jni])


if __name__ == "__main__":
    main()
