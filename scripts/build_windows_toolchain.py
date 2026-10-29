#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Repackage the checksum-pinned official LLVM-MinGW 18 UCRT release; no host SDKs.
Usage: python scripts/build_windows_toolchain.py UPSTREAM.zip OUTPUT_DIRECTORY
Run on Windows x64. The input hash is checked before executing any bundled tool.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ID = 'bhash-windows-x86_64-llvm18-v1'
VERSION = '20240619'
UPSTREAM = f'llvm-mingw-{VERSION}-ucrt-x86_64'
URL = f'https://github.com/mstorsjo/llvm-mingw/releases/download/{VERSION}/{UPSTREAM}.zip'
SHA256 = '810703594a7e3eea03385b5329c7ea3bd65f5e496b44cf1b68c17ff436d265e7'

def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def write_zip(root, output):
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for file in sorted(root.rglob('*')):
            if file.is_file():
                info = zipfile.ZipInfo(file.relative_to(root).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, file.read_bytes(), compresslevel=9)

def main():
    source, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    if sha(source) != SHA256:
        raise ValueError('Official LLVM-MinGW archive SHA-256 mismatch')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as temp:
        work = Path(temp)
        with zipfile.ZipFile(source) as z:
            for name in z.namelist():
                if not name.startswith(UPSTREAM + '/') or '..' in Path(name).parts or '\\' in name:
                    raise ValueError('Unsafe upstream path: ' + name)
            z.extractall(work)
        upstream = work / UPSTREAM
        root = work / 'sdk'
        root.mkdir()
        for name in ('include', 'lib/clang/18', 'x86_64-w64-mingw32'):
            shutil.copytree(upstream / name, root / name)
        shutil.copyfile(upstream / 'LICENSE.TXT', root / 'LLVM_LICENSE.txt')
        (root / 'bin').mkdir()
        # Keep just the compiler, GNU-flavour LLD, inspector and their private DLL closure.
        pending = ['clang.exe', 'clang-18.exe', 'ld.lld.exe', 'llvm-readobj.exe']
        copied = set()
        inspector = upstream / 'bin/llvm-readobj.exe'
        while pending:
            name = pending.pop()
            if name.lower() in copied:
                continue
            copied.add(name.lower())
            binary = upstream / 'bin' / name
            shutil.copyfile(binary, root / 'bin' / name)
            text = subprocess.check_output([str(inspector), '--coff-imports', str(binary)], text=True, encoding='utf-8')
            for dll in re.findall(r'^  Name: (.+)$', text, re.M):
                local = upstream / 'bin' / dll
                if local.is_file():
                    pending.append(dll)
                elif dll.lower() not in ('kernel32.dll', 'advapi32.dll', 'ntdll.dll', 'user32.dll', 'shell32.dll', 'ole32.dll', 'ws2_32.dll', 'bcrypt.dll', 'version.dll', 'msvcrt.dll') and not dll.lower().startswith(('api-ms-win-', 'ext-ms-win-')):
                    raise ValueError('Unaccounted tool dependency: ' + dll)
        # Preserve runtime attribution, also embedded by Bhash into generated applications.
        provenance = dict(url=URL, sha256=SHA256, llvm='https://github.com/llvm/llvm-project/tree/llvmorg-18.1.8',
                          builder='https://github.com/mstorsjo/llvm-mingw/tree/20240619',
                          mingw='https://github.com/mingw-w64/mingw-w64')
        (root / 'provenance.json').write_text(json.dumps(provenance, sort_keys=True) + '\n', encoding='utf-8')
        (root / 'README.txt').write_text('Bhash Windows x64 SDK, LLVM 18.1.8, UCRT, Windows 10+.\n'
            'Tools and private DLLs stay inside the SDK. No PATH changes or administrator access.\n'
            'Unmodified LLVM-MinGW components; upstream SHA and source links: provenance.json.\n'
            'Notices: LLVM_LICENSE.txt and x86_64-w64-mingw32/share/mingw32/COPYING*.\n', encoding='utf-8')
        inventory = {p.relative_to(root).as_posix(): dict(sha256=sha(p), executable=p.suffix.lower() == '.exe')
                     for p in sorted(root.rglob('*')) if p.is_file()}
        manifest = dict(format=1, id=ID, target='x86_64-w64-windows-gnu', llvm_version='18.1.8',
                        minimum_windows='10.0', c_runtime='ucrt', upstream=provenance, files=inventory)
        (root / 'toolchain.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n', encoding='utf-8')
        archive = output / (ID + '.zip')
        write_zip(root, archive)
        digest = sha(archive)
        (output / (archive.name + '.sha256')).write_text(digest + '  ' + archive.name + '\n', encoding='utf-8')
        print(json.dumps(dict(archive=str(archive), sha256=digest, bytes=archive.stat().st_size, files=len(inventory))))

if __name__ == '__main__':
    main()
