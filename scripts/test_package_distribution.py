#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Publish -> discover -> add -> clean restore -> execute, without a registry server."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/bhash-compiler-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--target', action='append', choices=['jvm', 'clr', 'native'], default=[])
    args = parser.parse_args()
    jar = args.jar.resolve()
    targets = list(dict.fromkeys(args.target or ['jvm']))
    (ROOT / 'target').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='package-reuse-', dir=ROOT / 'target') as temporary:
        work = Path(temporary).resolve()
        java_tmp = work / 'java-tmp'
        java_tmp.mkdir()
        env = os.environ | {'TEMP': str(java_tmp), 'TMP': str(java_tmp), 'TMPDIR': str(java_tmp)}

        def run(command, expected=None, extra_env=None):
            result = subprocess.run(command, cwd=work, env=env | (extra_env or {}), capture_output=True,
                                    text=True, encoding='utf-8', timeout=240)
            if result.returncode:
                raise AssertionError(f'{command[0:3]} failed ({result.returncode})\n{result.stdout}\n{result.stderr}')
            if expected is not None and result.stdout.replace('\r\n', '\n') != expected:
                raise AssertionError(f'Unexpected output: {result.stdout!r} != {expected!r}')
            return result.stdout

        def cli(*values, **kwargs):
            arguments = list(map(str, values))
            if os.name == 'nt':
                envelope = base64.b64encode(json.dumps({'cwd': str(work), 'args': arguments}, ensure_ascii=False).encode('utf-8')).decode('ascii')
                arguments = ['--utf8-args', envelope]
            return run(['java', '-Dfile.encoding=UTF-8', f'-Djava.io.tmpdir={java_tmp}', '-jar', str(jar), *arguments], **kwargs)

        repo = work / 'source'
        shutil.copytree(ROOT / 'packages/examples/text-utils', repo, ignore=shutil.ignore_patterns('.bhash'))
        run(['git', 'init', str(repo)])
        run(['git', '-C', str(repo), 'add', 'bhash.project', 'src', 'LICENSE', 'README.md'])
        run(['git', '-C', str(repo), '-c', 'user.name=Package Fixture', '-c', 'user.email=fixture@example.invalid',
             '-c', 'commit.gpgsign=false', 'commit', '-qm', 'release fixture'])
        revision = run(['git', '-C', str(repo), 'rev-parse', 'HEAD']).strip()
        index, release = work / 'index.json', work / 'release'
        cli('package', 'publish', repo, '--index', index, '--git', repo.as_uri(), '--rev', revision,
            '--description', 'Text helpers · पाठ सहायक', '--license', 'Apache-2.0', '-o', release)
        archive = release / 'bhash-text-utils-1.0.1.zip'
        assert (release / 'SHA256SUMS').read_text().split()[0] == hashlib.sha256(archive.read_bytes()).hexdigest()
        assert revision == json.loads(index.read_text())['packages'][0]['rev']
        assert 'bhash-text-utils@1.0.1' in cli('package', 'search', 'पाठ', '--index', index, '--offline')
        assert revision in cli('package', 'info', 'bhash-text-utils@1.0.1', '--index', index)

        app = work / 'consumer'
        (app / 'src').mkdir(parents=True)
        shutil.copyfile(ROOT / 'examples/package-reuse/src/main.bhash', app / 'src/main.bhash')
        (app / 'bhash.project').write_text('name=consumer\nsources=src\nnullSafety=strict\n', encoding='utf-8')
        cli('package', 'add', 'bhash-text-utils@1.0.1', '--project', app, '--alias', 'text', '--index', index)
        lock = (app / 'bhash.lock').read_bytes()
        cli('package', 'update', 'bhash-text-utils@1.0.1', '--project', app, '--alias', 'text', '--index', index, '--offline')
        assert (app / 'bhash.lock').read_bytes() == lock

        clean = work / 'clean-consumer'
        shutil.copytree(app, clean, ignore=shutil.ignore_patterns('.bhash'))
        # The index is no longer available: restore and builds must not consult it.
        index.rename(work / 'index.not-used')
        cli('deps', 'restore', clean)
        cli('deps', 'verify', clean, '--offline')
        assert (clean / 'bhash.lock').read_bytes() == lock
        expected = (ROOT / 'examples/package-reuse/expected.txt').read_text(encoding='utf-8')
        for target in targets:
            cli('run', clean, '--target', target, expected=expected,
                extra_env={'BHASH_GC_STRESS': '1', 'BHASH_GC_STATS': '1'} if target == 'native' else {})
            print(f'PASS package publish/discovery/add/update/clean restore/offline execution: {target}', flush=True)
        cli('deps', 'restore', clean, '--offline')
        # Altering a cached source is detected rather than accepted through index metadata.
        cached = next((clean / '.bhash/dependencies').glob('*/src/text.bhash'))
        cached.write_text(cached.read_text(encoding='utf-8') + '\n// changed\n', encoding='utf-8')
        result = subprocess.run(['java', '-jar', str(jar), 'deps', 'verify', str(clean), '--offline'],
                                cwd=work, env=env, capture_output=True, timeout=30)
        assert result.returncode == 2 and b'BH8003' in result.stderr
        print('PASS changed-source rejection and immutable lock', flush=True)


if __name__ == '__main__':
    main()
