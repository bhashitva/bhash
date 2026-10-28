#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Standalone collector/runtime checks. Windows MSVC is a runtime-only test aid."""
import argparse
from pathlib import Path
import subprocess
import os

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'src/main/resources/dev/bhash/backend/native'
BUILD = ROOT / 'target/native-runtime-tests'

def run(command):
    return subprocess.run(command, cwd=ROOT, capture_output=True, timeout=120)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cc', default='clang-18')
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    msvc = Path(args.cc).name.lower() in ('cl', 'cl.exe')
    for optimized in (False, True):
        name = 'optimized' if optimized else 'debug'
        exe = BUILD / (name + ('.exe' if os.name == 'nt' else ''))
        if msvc:
            command = [args.cc, '/nologo', '/std:c17', '/utf-8', '/W4', '/WX',
                       '/O2' if optimized else '/Od', '/I' + str(RUNTIME),
                       str(ROOT / 'tests/native/runtime_test.c'), str(RUNTIME / 'bhash_runtime.c'),
                       '/Fe:' + str(exe), '/Fo:' + str(BUILD) + os.sep]
        else:
            command = [args.cc, '-std=c17', '-Wall', '-Wextra', '-Werror', '-pedantic',
                       '-O2' if optimized else '-O0', '-I', str(RUNTIME),
                       str(ROOT / 'tests/native/runtime_test.c'), str(RUNTIME / 'bhash_runtime.c'), '-o', str(exe)]
            if args.sanitize:
                command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        result = run(command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native runtime checks passed.\n'
        for scenario in ('oversize', 'invalid-descriptor'):
            result = run([str(exe), scenario])
            assert result.returncode == 1, (scenario, result.returncode)
            assert not result.stdout
            assert result.stderr.startswith('भाष: '.encode('utf-8'))
        result = run([str(exe), 'output'])
        assert result.returncode == 0
        assert result.stdout.decode('utf-8').replace('\r\n', '\n') == '-९२२३३७२०३६८५४७७५८०८ ९२२३३७२०३६८५४७७५८०७ सत्य असत्य\n'
        print(f'{name}: collector, cycles, precision, roots, stress, Unicode, arithmetic and failure checks passed')
        text_exe = BUILD / ('text-' + name + ('.exe' if os.name == 'nt' else ''))
        text_command = [part.replace('runtime_test.c', 'text_runtime_test.c').replace(str(exe), str(text_exe)) for part in command]
        result = run(text_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(text_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native heap text checks passed.\n'
        for scenario in ('empty-replace', 'slice-bounds', 'negative-repeat', 'repeat-limit', 'mixed-digits', 'integer-overflow', 'explicit-failure'):
            result = run([str(text_exe), scenario])
            assert result.returncode == 1, (scenario, result.returncode)
            assert not result.stdout
            assert result.stderr.startswith('भाष: runtime-test:1:1: '.encode('utf-8'))
        print(f'{name}: heap text, helper rooting, Unicode, limits and failure checks passed')

        collection_exe = BUILD / ('collections-' + name + ('.exe' if os.name == 'nt' else ''))
        collection_command = [part.replace('runtime_test.c', 'collections_runtime_test.c').replace(str(exe), str(collection_exe)) for part in command]
        result = run(collection_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(collection_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native collection checks passed.\n'
        for scenario in ('negative-size', 'huge-size', 'invalid-repeat', 'array-bounds', 'null-key', 'missing-key', 'mutation'):
            result = run([str(collection_exe), scenario])
            assert result.returncode == 1, (scenario, result.returncode)
            assert not result.stdout
            assert result.stderr.startswith('भाष: '.encode('utf-8'))
        print(f'{name}: repeated tracing, scalar precision, collection cycles, growth, hashing, snapshots and failures passed')

if __name__ == '__main__':
    main()
