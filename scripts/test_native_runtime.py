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

def run(command, input=None):
    return subprocess.run(command, cwd=ROOT, capture_output=True, timeout=120, input=input)

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
            if os.name != 'nt':
                command += ['-lm']
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

        structured_exe = BUILD / ('structured-' + name + ('.exe' if os.name == 'nt' else ''))
        structured_command = [part.replace('runtime_test.c', 'structured_runtime_test.c').replace(str(exe), str(structured_exe)) for part in command]
        result = run(structured_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(structured_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native structured text checks passed.\n'
        print(f'{name}: split/join, nested JSON, scalar boxes, Unicode, cycles, depth and partial-failure root cleanup passed')

        files_exe = BUILD / ('files-' + name + ('.exe' if os.name == 'nt' else ''))
        files_command = [part.replace('runtime_test.c', 'files_runtime_test.c').replace(str(exe), str(files_exe)) for part in command]
        result = run(files_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(files_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native file checks passed.\n'
        print(f'{name}: UTF-8 files, byte limits, errors, descriptor cleanup and forced GC passed')

        bytes_exe = BUILD / ('bytes-' + name + ('.exe' if os.name == 'nt' else ''))
        bytes_command = [part.replace('runtime_test.c', 'bytes_runtime_test.c').replace(str(exe), str(bytes_exe)) for part in command]
        result = run(bytes_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(bytes_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native byte/buffer checks passed.\n'
        print(f'{name}: packed bytes, binary layouts, UTF-8, file round trips, precision and exception cleanup passed')

        console_exe = BUILD / ('console-' + name + ('.exe' if os.name == 'nt' else ''))
        console_command = [part.replace('runtime_test.c', 'console_runtime_test.c').replace(str(exe), str(console_exe)) for part in command]
        result = run(console_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        console_input = ("क😀" * 50000 + "\nनमस्ते😀\0ब\r\n-९२२३३७२०३६८५४७७५८०८\r9223372036854775807\ninvalid\n" + "9" * 100 + "x\n9223372036854775808\n").encode('utf-8')
        console_input += b'\xff\r\n7\n' + ("क😀\ninvalid\n" * 1000 + "last").encode('utf-8')
        result = run([str(console_exe)], input=console_input)
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native console checks passed.\n'
        # Closing stdin triggers MSVC's invalid-parameter abort; exercise POSIX I/O failures on Linux.
        if os.name != 'nt':
            for scenario in ('text', 'integer'):
                result = run([str(console_exe), scenario], input=b'')
                assert result.returncode == 0, result.stderr
                assert result.stdout.replace(b'\r\n', b'\n') == b'Native console I/O failure checks passed.\n'
        print(f'{name}: UTF-8 console lines, integer limits, malformed-input recovery and forced-GC unwinding passed')

        time_exe = BUILD / ('time-' + name + ('.exe' if os.name == 'nt' else ''))
        time_command = [part.replace('runtime_test.c', 'time_runtime_test.c').replace(str(exe), str(time_exe)) for part in command]
        result = run(time_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(time_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native UTC time checks passed.\n'
        print(f'{name}: entire Gregorian calendar, UTC milliseconds, strict parsing, clock bounds and GC cleanup passed')

        numeric_exe = BUILD / ('math-random-' + name + ('.exe' if os.name == 'nt' else ''))
        numeric_command = [part.replace('runtime_test.c', 'math_random_runtime_test.c').replace(str(exe), str(numeric_exe)) for part in command]
        result = run(numeric_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(numeric_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native math/random checks passed.\n'
        print(f'{name}: math domains, signed zeros, rounding, golden mixed random sequences, range rejection and unwinding passed')

        int32_exe = BUILD / ('int32-' + name + ('.exe' if os.name == 'nt' else ''))
        int32_command = [part.replace('runtime_test.c', 'int32_runtime_test.c').replace(str(exe), str(int32_exe)) for part in command]
        result = run(int32_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(int32_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native int32 checks passed.\n'
        print(f'{name}: signed int32 boundaries, boxed key identity, JSON, scalar storage and forced-GC failure cleanup passed')

        decimal_exe = BUILD / ('decimals-' + name + ('.exe' if os.name == 'nt' else ''))
        decimal_command = [part.replace('runtime_test.c', 'decimal_runtime_test.c').replace(str(exe), str(decimal_exe)) for part in command]
        result = run(decimal_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(decimal_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native decimal checks passed.\n'
        print(f'{name}: binary64 conversions, formatting round trips, scalar tracing, NaN/zero hashing and failures passed')

        exception_exe = BUILD / ('exceptions-' + name + ('.exe' if os.name == 'nt' else ''))
        exception_command = [part.replace('runtime_test.c', 'exceptions_runtime_test.c').replace(str(exe), str(exception_exe)) for part in command]
        result = run(exception_command)
        if result.returncode:
            raise RuntimeError(result.stdout.decode('utf-8', errors='replace') + result.stderr.decode('utf-8', errors='replace'))
        result = run([str(exception_exe)])
        assert result.returncode == 0, result.stderr
        assert result.stdout.replace(b'\r\n', b'\n') == b'Native exception checks passed.\n'
        result = run([str(exception_exe), 'uncaught'])
        assert result.returncode == 1 and not result.stdout
        assert result.stderr.startswith('भाष: exception-test:1:1: '.encode('utf-8'))
        print(f'{name}: protected callbacks, pending exceptions, root/scratch unwinding, identity and restart passed')

if __name__ == '__main__':
    main()
