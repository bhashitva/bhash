#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a relocatable LLVM18/glibc sysroot bundle from the pinned Ubuntu snapshot.
Run in scripts/native-toolchain.Dockerfile. No Bhash runtime objects are frozen into this SDK.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ID = 'bhash-linux-x86_64-llvm18-v1'
ARCHIVE = ID + '-jammy.zip'
SOURCES = ID + '-jammy-sources.zip'
PACKAGES = {}
COPYRIGHTS = set()


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, encoding='utf-8').strip()


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def package(name):
    if name in PACKAGES:
        return
    fields = run('dpkg-query', '-W', '-f=${Package}\t${Version}\t${source:Package}\t${source:Version}', name).split('\t')
    assert len(fields) == 4, fields
    PACKAGES[name] = dict(zip(('binary', 'version', 'source', 'source_version'), fields))


def package_owner(output):
    # dpkg prints diversion diagnostics before ordinary ownership records on merged-/usr.
    for line in output.splitlines():
        match = re.match(r'^([a-z0-9][a-z0-9+.-]*(?::[a-z0-9][a-z0-9-]*)?): /', line)
        if match:
            return match.group(1)
    return None


def owner(path):
    path = str(path)
    variants = [path, str(Path(path).resolve())]
    variants += [p[4:] for p in variants[:] if p.startswith('/usr/lib/')]
    variants += ['/usr'+p for p in variants[:] if p.startswith('/lib/')]
    for candidate in variants:
        result = subprocess.run(['dpkg-query', '-S', candidate], capture_output=True, text=True)
        if result.returncode == 0:
            found = package_owner(result.stdout)
            if found:
                return found
    raise RuntimeError('Cannot establish package provenance: '+path)


def copy_file(source, destination, pkg):
    source = Path(source)
    if not source.is_file():
        return
    package(pkg)
    if source.is_symlink():
        package(owner(source.resolve()))
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o644)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':'))+'\n', encoding='utf-8')


def write_zip(directory, output):
    # ZIP timestamps, order, ownership-independent permission metadata and compression are fixed.
    with zipfile.ZipFile(output, 'w', allowZip64=True) as archive:
        for path in sorted(directory.rglob('*')):
            if not path.is_file():
                continue
            entry = zipfile.ZipInfo(path.relative_to(directory).as_posix(), (2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if os.access(path, os.X_OK) else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_STORED if path.name.endswith(('.xz', '.gz', '.bz2', '.zst', '.zip')) else zipfile.ZIP_DEFLATED
            with path.open('rb') as source, archive.open(entry, 'w', force_zip64=True) as target:
                shutil.copyfileobj(source, target, 1024*1024)


def main():
    assert run('dpkg', '--print-architecture') == 'amd64'
    output = Path(sys.argv[1]); output.mkdir(parents=True, exist_ok=True)
    llvm = re.search(r'version (18\.\d+\.\d+)', run('clang-18', '--version')).group(1)
    assert re.search(r'version (18\.\d+\.\d+)', run('opt-18', '--version')).group(1) == llvm
    with tempfile.TemporaryDirectory(prefix='bhash-sdk-') as temporary:
        root = Path(temporary)/'payload'; root.mkdir()
        sources = Path(temporary)/'sources'; sources.mkdir()
        tools = {'clang-18': shutil.which('clang-18'), 'opt-18': shutil.which('opt-18'),
                 'ld.lld': shutil.which('ld.lld-18'), 'llvm-readelf': shutil.which('llvm-readelf-18')}
        runtime_dependencies = {}
        for name, path in tools.items():
            assert path, name
            copy_file(path, root/'libexec'/name, owner(path))
            (root/'libexec'/name).chmod(0o755)
            # ldd is applied only to authenticated distribution tools, never user input.
            for line in run('ldd', path).splitlines():
                match = re.match(r'\s*(\S+) => (/\S+)', line)
                if match:
                    soname, library = match.groups()
                    pkg = owner(library)
                    if pkg.split(':')[0] == 'libc6':
                        continue # The host's glibc/loader remain system runtime prerequisites.
                    runtime_dependencies[soname] = (library, pkg)
        # The dependencies of ELF libraries returned by ldd are already transitive.
        for soname, (library, pkg) in sorted(runtime_dependencies.items()):
            copy_file(library, root/'lib'/soname, pkg)
        resource = Path(run('clang-18', '-print-resource-dir'))
        resource_owners = {}
        for pkg in ('clang-18', 'libclang-common-18-dev', 'libclang-rt-18-dev'):
            for name in run('dpkg-query', '-L', pkg).splitlines():
                path = Path(name)
                if path.is_file():
                    resource_owners[str(path.resolve())] = pkg
        for directory, _, names in os.walk(resource, followlinks=True):
            for name in names:
                path = Path(directory)/name
                pkg = resource_owners.get(str(path.resolve())) or owner(path)
                copy_file(path, root/'resource'/path.relative_to(resource), pkg)
        assert (root/'resource/include/stddef.h').is_file()
        for pkg in ('libc6', 'libc6-dev', 'linux-libc-dev', 'libgcc-11-dev', 'libgcc-s1'):
            for name in run('dpkg-query', '-L', pkg).splitlines():
                path = Path(name)
                if not path.is_file():
                    continue
                header = name.startswith('/usr/include/') or '/include/' in name and name.startswith('/usr/lib/gcc/')
                library = name.startswith(('/lib/', '/lib64/', '/usr/lib/')) and re.search(r'\.(?:o|a|so(?:\.[0-9.]+)?)$', name)
                if header or library:
                    copy_file(path, root/'sysroot'/name.lstrip('/'), pkg)
        # Explicit aliases cover merged-/usr paths referenced by glibc linker scripts.
        for name in ('libc.so.6', 'libm.so.6', 'libmvec.so.1', 'libgcc_s.so.1'):
            source = Path('/lib/x86_64-linux-gnu')/name
            for prefix in ('lib', 'usr/lib'):
                copy_file(source, root/'sysroot'/prefix/'x86_64-linux-gnu'/name, owner(source))
        loader = Path('/lib64/ld-linux-x86-64.so.2')
        copy_file(loader, root/'sysroot/lib64/ld-linux-x86-64.so.2', owner(loader))
        common = '''#!/bin/sh
set -eu
bhash_sdk=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export LD_LIBRARY_PATH="$bhash_sdk/lib"
unset CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH OBJC_INCLUDE_PATH LIBRARY_PATH GCC_EXEC_PREFIX COMPILER_PATH
'''
        for name in tools:
            path = root/'bin'/name;path.parent.mkdir(exist_ok=True)
            command = 'exec "$bhash_sdk/libexec/'+name+'" "$@"\n'
            if name == 'clang-18':
                command = '''exec "$bhash_sdk/libexec/clang-18" -Wno-unused-command-line-argument -gno-record-command-line -fno-record-command-line --sysroot="$bhash_sdk/sysroot" --gcc-toolchain="$bhash_sdk/sysroot/usr" -resource-dir "$bhash_sdk/resource" --ld-path="$bhash_sdk/bin/ld.lld" "-ffile-prefix-map=$bhash_sdk=/bhash/toolchain" "-fdebug-prefix-map=$bhash_sdk=/bhash/toolchain" "$@"
'''
            path.write_text(common+command, encoding='utf-8');path.chmod(0o755)
        # Declaring a lower minimum is insufficient: inspect every host-side ELF.
        for directory in ('libexec', 'lib'):
            for binary in sorted((root/directory).iterdir()):
                if binary.read_bytes()[:4] != b'\x7fELF':
                    continue
                versions = re.findall(r'\bGLIBC_(\d+)\.(\d+)\b', run('readelf', '--version-info', '--wide', str(binary)))
                if any((int(major), int(minor)) > (2, 35) for major, minor in versions):
                    raise RuntimeError('SDK host binary exceeds glibc 2.35 baseline: '+str(binary))
        # Retain notices for every package whose files were actually copied.
        for pkg in sorted(PACKAGES):
            binary = PACKAGES[pkg]['binary']
            copyright = Path('/usr/share/doc')/binary/'copyright'
            if not copyright.is_file():
                raise RuntimeError('Missing redistribution notices: '+pkg)
            target = root/'licenses'/(binary+'.copyright')
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(copyright, target)
        write_json(root/'packages.json', {'snapshot': os.environ['BHASH_UBUNTU_SNAPSHOT'], 'llvm_repository': 'https://apt.llvm.org/jammy/', 'llvm_version': os.environ['BHASH_LLVM_DEB_VERSION'], 'packages': PACKAGES})
        source_packages = sorted({(p['source'], p['source_version']) for p in PACKAGES.values()})
        for source, version in source_packages:
            print('Corresponding source:', source, version, flush=True)
            subprocess.run(['apt-get', 'source', '--download-only', '-y', source+'='+version], cwd=sources, check=True)
        write_json(sources/'packages.json', {'snapshot': os.environ['BHASH_UBUNTU_SNAPSHOT'], 'llvm_repository': 'https://apt.llvm.org/jammy/', 'llvm_version': os.environ['BHASH_LLVM_DEB_VERSION'], 'packages': PACKAGES})
        write_zip(sources, output/SOURCES)
        source_info = {'file': SOURCES, 'sha256': sha(output/SOURCES), 'packages': [{'name': name, 'version': version} for name, version in source_packages]}
        write_json(root/'sources.json', source_info)
        shutil.copyfile('/builder/LICENSE', root/'LICENSE')
        shutil.copyfile('/builder/NOTICE', root/'NOTICE')
        (root/'README.txt').write_text('Bhash managed Linux x86-64 LLVM 18 toolchain.\nRequires host glibc >= 2.35 and /bin/sh.\nInstall through native toolchain install with the published archive SHA-256.\nThe matching -sources.zip contains corresponding upstream/distribution sources.\nRedistribute both archives together; retain licenses/ and packages.json.\nThe SDK contains third-party components with their own licenses.\n', encoding='utf-8')
        files = {p.relative_to(root).as_posix(): {'sha256': sha(p), 'size': p.stat().st_size, 'executable': os.access(p, os.X_OK)} for p in sorted(root.rglob('*')) if p.is_file()}
        write_json(root/'toolchain.json', {'format': 1, 'id': ID, 'target': 'x86_64-unknown-linux-gnu', 'llvm_version': llvm, 'minimum_glibc': '2.35', 'files': files, 'sources': source_info})
        write_zip(root, output/ARCHIVE)
        # Rebuild the ZIP from the same immutable payload to verify packaging determinism.
        again = Path(temporary)/'again.zip';write_zip(root, again)
        assert sha(again) == sha(output/ARCHIVE)
        for name in (ARCHIVE, SOURCES):
            digest = sha(output/name)
            (output/(name+'.sha256')).write_text(digest+'  '+name+'\n', encoding='ascii')
            print(name, (output/name).stat().st_size, digest, flush=True)
        (output/'toolchain.json').write_bytes((root/'toolchain.json').read_bytes())
        print('Portable toolchain bundle and companion sources built reproducibly', flush=True)


if __name__ == '__main__':
    main()
