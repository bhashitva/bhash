#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Repackage official LLVM 18 ARM64 tools. Apple's SDK/linker are never bundled."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
from build_native_toolchain import write_zip

ID = 'bhash-macos-arm64-llvm18-v1'
UPSTREAM = 'https://github.com/llvm/llvm-project/releases/download/llvmorg-18.1.8/clang+llvm-18.1.8-arm64-apple-macos11.tar.xz'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def build(archive, digest, output):
    if not re.fullmatch('[0-9a-f]{64}', digest) or sha(archive) != digest:
        raise ValueError('Official LLVM archive SHA-256 mismatch')
    if run('uname', '-s') != 'Darwin' or run('uname', '-m') != 'arm64':
        raise RuntimeError('Build on an Apple Silicon Mac; cross compilation is not supported')
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='bhash-macos-sdk-') as temporary:
        temporary = Path(temporary)
        upstream = temporary/'upstream'
        # data filter rejects escaping links, devices and absolute paths.
        with tarfile.open(archive) as tar:
            tar.extractall(upstream, filter='data')
        roots = list(upstream.iterdir())
        if len(roots) != 1 or not (roots[0]/'bin/clang').is_file():
            raise RuntimeError('Unexpected official LLVM archive layout')
        source = roots[0].resolve()
        root = temporary/'payload'; root.mkdir()
        tools = {'clang-18': 'clang', 'opt-18': 'opt', 'llvm-readobj': 'llvm-readobj', 'dsymutil': 'dsymutil'}
        copied = {}

        def copy(path, relative):
            path = path.resolve()
            if not path.is_relative_to(source):
                raise RuntimeError('LLVM dependency outside authenticated archive: '+str(path))
            target = root/relative; target.parent.mkdir(parents=True, exist_ok=True)
            if relative not in copied:
                shutil.copyfile(path, target); target.chmod(0o755)
                copied[relative] = path
            return target

        for name, tool in tools.items():
            copy(source/'bin'/tool, 'bin/'+name)
        resource = source/'lib/clang/18'
        if not (resource/'include/stddef.h').is_file():
            raise RuntimeError('Missing LLVM resource headers')
        for path in sorted(resource.rglob('*')):
            if path.is_file():
                resolved = path.resolve()
                if not resolved.is_relative_to(source):
                    raise RuntimeError('Resource link escapes upstream archive')
                target = root/path.relative_to(source); target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(resolved, target); target.chmod(0o644)

        # Only authenticated upstream Mach-O dependencies are examined. System libraries stay external.
        done = set()
        while set(copied)-done:
            relative = sorted(set(copied)-done)[0]; done.add(relative)
            target = root/relative
            for line in run('/usr/bin/otool', '-L', target).splitlines()[1:]:
                dependency = line.strip().split(' (', 1)[0]
                if dependency.startswith(('/usr/lib/', '/System/Library/')):
                    continue
                candidate = source/'lib'/Path(dependency).name
                if not candidate.is_file():
                    raise RuntimeError('Nonportable LLVM dependency: '+dependency)
                destination = 'lib/'+candidate.name
                copy(candidate, destination)
                replacement = '@loader_path/'+os.path.relpath(root/destination, target.parent)
                # A dylib's own install ID is not a dependency edge.
                if target.suffix == '.dylib' and Path(dependency).name == target.name:
                    run('/usr/bin/install_name_tool', '-id', '@rpath/'+target.name, target)
                else:
                    run('/usr/bin/install_name_tool', '-change', dependency, replacement, target)
        for relative in sorted(copied):
            run('/usr/bin/codesign', '--force', '--sign', '-', '--timestamp=none',
                '--identifier', 'org.bhash.sdk.'+relative.replace('/', '.'), root/relative)
            run('/usr/bin/codesign', '--verify', '--strict', root/relative)
        licenses = root/'licenses'; licenses.mkdir()
        notices = sorted(source.rglob('LICENSE.TXT')) + sorted(source.rglob('LICENSE.txt'))
        if not notices:
            raise RuntimeError('Official LLVM license missing')
        for index, path in enumerate(notices):
            shutil.copyfile(path, licenses/f'LLVM-{index}.txt')
        provenance = {'upstream_url': UPSTREAM, 'upstream_sha256': digest,
                      'source_url': 'https://github.com/llvm/llvm-project/tree/llvmorg-18.1.8',
                      'apple_sdk': 'external', 'apple_linker': 'external'}
        (root/'provenance.json').write_text(json.dumps(provenance, sort_keys=True)+'\n')
        (root/'README.txt').write_text('Bhash macOS 14+ Apple Silicon SDK. LLVM 18.1.8.\nApple Command Line Tools must be installed separately. No Apple SDK or linker is redistributed.\nLLVM uses Apache-2.0 WITH LLVM-exception; preserve licenses and provenance.\nLocal ad-hoc signatures only; no Developer ID signature or notarization is claimed.\n')
        files = {p.relative_to(root).as_posix(): {'sha256': sha(p), 'size': p.stat().st_size,
                 'executable': os.access(p, os.X_OK)} for p in sorted(root.rglob('*')) if p.is_file()}
        manifest = {'format': 1, 'id': ID, 'target': 'arm64-apple-macosx14.0.0',
                    'llvm_version': '18.1.8', 'minimum_macos': '14.0', 'c_runtime': 'libSystem',
                    'apple_sdk': 'external', 'files': files, 'provenance': provenance}
        (root/'toolchain.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':'))+'\n')
        result = output/(ID+'.zip'); write_zip(root, result)
        digest = sha(result)
        (output/(result.name+'.sha256')).write_text(digest+'  '+result.name+'\n')
        print(digest+'  '+result.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--upstream-sha256', required=True)
    parser.add_argument('--output', type=Path, default=Path('target/macos-sdk'))
    args = parser.parse_args()
    build(args.archive, args.upstream_sha256, args.output)
