#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rebuild pinned SQLite shared libraries; no network or SDK installation occurs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INPUTS = ROOT / 'src/main/resources/dev/bhash/sqlite'


def build(output, compiler=None):
    lock = json.loads((INPUTS / 'sqlite.lock.json').read_text(encoding='utf-8'))
    archive = (INPUTS / lock['archive']).read_bytes()
    if hashlib.sha3_256(archive).hexdigest() != lock['archive_sha3_256']:
        raise RuntimeError('SQLite source archive does not match the lockfile')
    system, machine = platform.system(), platform.machine().lower()
    expected = {'Linux': ('x86_64', 'amd64'), 'Windows': ('x86_64', 'amd64'),
                'Darwin': ('arm64', 'aarch64')}
    if system not in expected or machine not in expected[system]:
        raise RuntimeError(f'Unsupported SQLite runtime distribution: {system}/{machine}')
    target, name = {'Linux': ('linux-x86_64', 'libbhash_sqlite.so'),
                    'Windows': ('windows-x86_64', 'bhash_sqlite.dll'),
                    'Darwin': ('macos-arm64', 'libbhash_sqlite.dylib')}[system]
    compiler = compiler or ('cl' if system == 'Windows' else 'clang')
    destination = output.resolve() / target
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='bhash-sqlite-build-') as temporary:
        work = Path(temporary)
        with zipfile.ZipFile(INPUTS / lock['archive']) as source:
            for filename in ('sqlite3.c', 'sqlite3.h'):
                entries = [n for n in source.namelist() if n.endswith('/' + filename)]
                if len(entries) != 1:
                    raise RuntimeError('Unexpected SQLite source archive layout')
                (work / filename).write_bytes(source.read(entries[0]))
        if hashlib.sha3_256((work / 'sqlite3.c').read_bytes()).hexdigest() != lock['source_sha3_256']:
            raise RuntimeError('SQLite amalgamation digest mismatch')
        defines = lock['defines']
        if system == 'Windows':
            command = [compiler, '/nologo', '/O2', '/MT', '/LD', '/Brepro',
                       '/DSQLITE_API=__declspec(dllexport)', *['/D' + d for d in defines],
                       'sqlite3.c', '/link', '/Brepro', '/OUT:' + name]
        else:
            command = [compiler, '-std=c17', '-O2', '-fPIC',
                       '-ffile-prefix-map=' + str(work) + '=/bhash/sqlite',
                       *['-D' + d for d in defines], 'sqlite3.c', '-o', name]
            command += (['-dynamiclib', '-arch', 'arm64', '-mmacosx-version-min=11.0',
                         '-Wl,-install_name,@rpath/' + name] if system == 'Darwin'
                        else ['-shared', '-pthread', '-Wl,--build-id=sha1'])
        subprocess.run(command, cwd=work, check=True)
        if system == 'Darwin':
            subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-',
                            '--timestamp=none', str(work / name)], check=True)
        data = (work / name).read_bytes()
        (destination / name).write_bytes(data)
        manifest = {'sqlite_version': lock['sqlite_version'], 'target': target,
                    'source_sha3_256': lock['source_sha3_256'], 'defines': defines,
                    'file': name, 'sha256': hashlib.sha256(data).hexdigest()}
        (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(manifest), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cc')
    options = parser.parse_args()
    build(options.output, options.cc)
