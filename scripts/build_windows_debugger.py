#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Repackage locked official MSYS2 UCRT64 GDB/Python, with corresponding sources.

--resolve is a maintainer-only operation to create a new package lock. Ordinary
builds use exact URLs and hashes and never resolve versions or install MSYS2.
Linux builder requires tar with zstd support; the resulting ZIP is Windows-only.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from windows_gdb_manifest import utf8_gdb

MIRROR = 'https://repo.msys2.org/mingw/'
ID = 'bhash-windows-x86_64-gdb-v1'


def download(url, destination, digest=None):
    if not url.startswith(MIRROR):
        raise ValueError('Only the official MSYS2 repository is accepted')
    with urllib.request.urlopen(url, timeout=120) as response:
        destination.write_bytes(response.read(512 * 1024 * 1024 + 1))
    if destination.stat().st_size > 512 * 1024 * 1024:
        raise ValueError('Package exceeds download limit')
    actual = hashlib.sha256(destination.read_bytes()).hexdigest()
    if digest and actual != digest:
        raise ValueError('Package checksum mismatch: ' + url)
    return actual


def resolve(destination):
    with tempfile.TemporaryDirectory() as work:
        database = Path(work) / 'ucrt64.db'
        download(MIRROR + 'ucrt64/ucrt64.db', database)
        packages = {}
        if database.read_bytes()[:4] == bytes.fromhex('28b52ffd'):
            unpacked = Path(work) / 'database.tar'
            with unpacked.open('wb') as stream:
                subprocess.run(['zstd', '-d', '-c', str(database)], stdout=stream, check=True)
            database = unpacked
        with tarfile.open(database) as archive:
            for member in archive:
                if member.name.endswith('/desc'):
                    text = archive.extractfile(member).read().decode()
                    record = {key: value.strip().splitlines() for key, value in
                              re.findall(r'%([A-Z0-9]+)%\n(.*?)(?=\n%|\Z)', text, re.S)}
                    packages[record['NAME'][0]] = record
        provided = {name.split('=')[0]: record for record in packages.values()
                    for name in record.get('PROVIDES', [])}
        result = {}

        def add(name):
            name = re.split(r'[<>=]', name)[0]
            record = packages.get(name) or provided.get(name)
            if record is None:
                raise ValueError('Missing UCRT64 dependency: ' + name)
            name = record['NAME'][0]
            if name in result:
                return
            result[name] = {
                'name': name, 'version': record['VERSION'][0],
                'url': MIRROR + 'ucrt64/' + record['FILENAME'][0],
                'sha256': record['SHA256SUM'][0],
                'source_url': MIRROR + 'sources/' + record['BASE'][0] + '-' + record['VERSION'][0] + '.src.tar.zst',
            }
            for dependency in record.get('DEPENDS', []):
                add(dependency)

        add('mingw-w64-ucrt-x86_64-gdb')
        # Source archives are also locked, and retained beside the binary release.
        for item in result.values():
            item['source_sha256'] = download(item['source_url'], Path(work) / 'source')
        lock = {'format': 1, 'id': ID, 'packages': sorted(result.values(), key=lambda p: p['name'])}
        destination.write_text(json.dumps(lock, indent=2) + '\n', encoding='utf-8', newline='\n')


def keep(name):
    parts = PurePosixPath(name).parts
    if not parts or parts[0] != 'ucrt64':
        return False
    relative = '/'.join(parts[1:])
    license_file = parts[-1].lower().startswith(('license', 'licence', 'copying', 'copyright')) or parts[-1].lower().endswith('.terms')
    return (license_file or relative == 'bin/gdb.exe' or relative.startswith('bin/') and relative.endswith('.dll')
            or relative.startswith(('share/gdb/', 'share/licenses/', 'share/terminfo/'))
            or relative.startswith('lib/python') and not any(p in parts for p in ('test', 'tests', '__pycache__', 'site-packages')))


def build(lock_path, output):
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    if lock['format'] != 1 or lock['id'] != ID:
        raise ValueError('Unsupported debugger lock')
    output.mkdir(parents=True, exist_ok=True)
    payload = {}
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        source_output = output / 'sources'
        source_output.mkdir(exist_ok=True)
        for number, package in enumerate(lock['packages']):
            archive = work / f'{number}.pkg.tar.zst'
            download(package['url'], archive, package['sha256'])
            expanded = work / 'package.tar'
            with expanded.open('wb') as stream:
                subprocess.run(['zstd', '-d', '-c', str(archive)], stdout=stream, check=True)
            with tarfile.open(expanded) as files:
                for entry in files:
                    if entry.isdir() or not keep(entry.name):
                        continue
                    if not entry.isfile() or any(p in ('..', '.') for p in PurePosixPath(entry.name).parts):
                        raise ValueError('Unsupported debugger archive member: ' + entry.name)
                    name = '/'.join(PurePosixPath(entry.name).parts[1:])
                    content = files.extractfile(entry).read()
                    if name in payload and payload[name] != content:
                        raise ValueError('Conflicting package file: ' + name)
                    payload[name] = content
            source = source_output / package['source_url'].rsplit('/', 1)[1]
            download(package['source_url'], source, package['source_sha256'])
    payload['bin/gdb.exe'] = utf8_gdb(payload['bin/gdb.exe'])
    # MSYS2 uses common GNU licenses rather than copying them into each package.
    # Retain the complete standard texts in the standalone payload as well.
    for license_name in ('GPL-2', 'GPL-3', 'LGPL-2.1', 'LGPL-3'):
        payload['share/licenses/common/' + license_name] = (Path('/usr/share/common-licenses') / license_name).read_bytes()
    payload['NOTICE.txt'] = (
        'Bhash Windows debugger: MSYS2 UCRT64 GDB and Python runtime files.\n'
        'GDB manifest modified by Bhash on 2026-10-30 to enable per-process UTF-8; code unchanged.\n'
        'The corresponding-source release includes the exact manifest patch script.\n'
        'GDB is GPL-3.0-or-later. Python and dependencies retain their own licenses.\n'
        'See share/licenses and the adjacent corresponding-source archives and package lock.\n'
        'This separate debugger distribution does not change the license of Bhash programs.\n'
    ).encode()
    payload['packages.lock.json'] = lock_path.read_bytes()
    manifest = {'format': 1, 'id': ID, 'files': {name: hashlib.sha256(data).hexdigest() for name, data in sorted(payload.items())}}
    payload['debugger.json'] = (json.dumps(manifest, sort_keys=True, indent=2) + '\n').encode()
    archive = output / (ID + '.zip')
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipout:
        for name, data in sorted(payload.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zipout.writestr(info, data)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix('.zip.sha256').write_text(digest + '  ' + archive.name + '\n', encoding='ascii')
    (output / 'windows-debugger.json').write_text(json.dumps({
        'id': ID, 'url': 'https://github.com/bhashitva/bhash/releases/download/native-debugger-windows-1.0.0/' + archive.name,
        'sha256': digest}, indent=2) + '\n', encoding='utf-8')
    with zipfile.ZipFile(output / 'bhash-windows-debugger-corresponding-sources.zip', 'w', compression=zipfile.ZIP_STORED) as sources:
        for script_name in ('build_windows_debugger.py', 'windows_gdb_manifest.py'):
            info = zipfile.ZipInfo(script_name, (2026, 1, 1, 0, 0, 0))
            sources.writestr(info, Path(__file__).with_name(script_name).read_bytes())
        info = zipfile.ZipInfo('packages.lock.json', (2026, 1, 1, 0, 0, 0))
        sources.writestr(info, lock_path.read_bytes())
        for source in sorted(source_output.iterdir()):
            info = zipfile.ZipInfo(source.name, (2026, 1, 1, 0, 0, 0))
            sources.writestr(info, source.read_bytes())
    print('Built', archive, digest, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=Path('scripts/windows-debugger-packages.lock.json'))
    parser.add_argument('--resolve', action='store_true')
    parser.add_argument('--output', type=Path, default=Path('target/windows-debugger-sdk'))
    args = parser.parse_args()
    if args.resolve:
        resolve(args.lock)
    build(args.lock, args.output)
