#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Apple Silicon release gate: relocation, reproducibility, symbols and runtime independence."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'target/macos-release'
JAVA = Path(os.environ['JAVA_HOME'])/'bin/java'
JAR = ROOT/'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
ENV = {**os.environ, 'SOURCE_DATE_EPOCH': '1700000000'}


def run(args, *, env=ENV, ok=True, cwd=ROOT):
    result = subprocess.run([str(a) for a in args], cwd=cwd, env=env, capture_output=True,
                            text=True, encoding='utf-8', timeout=180)
    if (result.returncode == 0) != ok:
        raise AssertionError(f'{args}: exit={result.returncode}\n{result.stdout}{result.stderr}')
    return result.stdout+result.stderr


def cli(*args, **kwargs):
    return run([JAVA, '-jar', JAR, *args], **kwargs)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert run(['uname', '-m']).strip() == 'arm64'
    OUT.mkdir(parents=True, exist_ok=True)
    doctor = json.loads(cli('native', 'doctor', '--json'))
    assert doctor['target'] == 'arm64-apple-macosx14.0.0' and doctor['version'] == '18.1.8'
    (OUT/'doctor.json').write_text(json.dumps(doctor, indent=2)+'\n')
    roots = [OUT/'first workspace', OUT/'दूसरा स्थान']
    for root in roots:
        root.mkdir(parents=True, exist_ok=True)
        (root/'hello.bhash').write_text('कार्य मुख्य() -> शून्य { लिखो("नमस्ते 😀"); }\n', encoding='utf-8')
    for debug in (False, True):
        binaries, archives = [], []
        for root in roots:
            flags = ['--debug'] if debug else []
            binary = root/('hello-debug' if debug else 'hello')
            cli('compile', root/'hello.bhash', '--target', 'native', '--source-root', root, '-o', binary, *flags)
            report = json.loads(cli('native', 'inspect', binary, '--json', '--require-runtime-match'))
            assert report['minimumMacos'] == '14.0.0' and report['runtimeMatchesCompiler']
            assert report['libraries'] == ['/usr/lib/libSystem.B.dylib']
            run(['/usr/bin/codesign', '--verify', '--strict', binary])
            assert run([binary]) == 'नमस्ते 😀\n'
            assert json.loads(run([binary, '--bhash-build-info'])) == report['buildInfo']
            archive = root/('hello-debug.tar.gz' if debug else 'hello.tar.gz')
            cli('native', 'package', root/'hello.bhash', '-o', archive, '--name', 'hello', '--version', '1.0.0', *flags)
            binaries.append(binary); archives.append(archive)
            if debug:
                symbols = binary.with_name(binary.name+'.dwarf')
                assert symbols.is_file()
                transcript = run(['/usr/bin/xcrun', 'lldb', '--batch', '-o', f'target create "{binary}"',
                                  '-o', f'target symbols add "{symbols}"', '-o', 'image lookup -n मुख्य'])
                assert 'मुख्य' in transcript and 'hello.bhash' in transcript, transcript
        assert sha(binaries[0]) == sha(binaries[1]), f'Non-reproducible executable: debug={debug}'
        assert sha(archives[0]) == sha(archives[1]), f'Non-reproducible archive: debug={debug}'
        print(f'Reproducible macOS release debug={debug}: {sha(archives[0])}')
        if debug:
            continue
        shutil.copyfile(archives[0], OUT/'hello-1.0.0-macos-arm64.tar.gz')
        unpacked = OUT/'relocated'
        with tarfile.open(archives[0]) as tar:
            for member in tar.getmembers():
                assert member.isfile() and member.uid == member.gid == 0 and member.mtime == 1700000000
            tar.extractall(unpacked, filter='data')
        bundle = unpacked/'hello-1.0.0-macos-arm64'
        # Only standard OS utilities are visible. No compiler, SDK, Java or .NET environment.
        clean = {'PATH': '/usr/bin:/bin', 'HOME': str(OUT), 'LANG': 'en_US.UTF-8'}
        run(['/usr/bin/shasum', '-a', '256', '-c', 'SHA256SUMS'], cwd=bundle, env=clean)
        assert run([bundle/'run.sh'], env=clean) == 'नमस्ते 😀\n'
        manifest = json.loads((bundle/'manifest.json').read_text())
        assert manifest['minimum_macos'] == '14.0.0' and manifest['c_runtime'] == 'libSystem'
        assert list(manifest['source_sha256']) == ['/bhash/source/hello.bhash']
        assert manifest['executable_sha256'] == sha(bundle/'bin/hello')
    corrupt = OUT/'not-an-executable'; corrupt.write_bytes(b'not Mach-O')
    assert 'BH9106' in cli('native', 'inspect', corrupt, ok=False)
    # A failed package request must preserve the previous archive.
    preserved = OUT/'preserved.tar.gz'; preserved.write_bytes(b'previous release')
    assert '--max-glibc applies only to Linux' in cli('native', 'package', roots[0]/'hello.bhash',
        '-o', preserved, '--name', 'hello', '--version', '1', '--max-glibc', '2.39', ok=False)
    assert preserved.read_bytes() == b'previous release'
    print('Mach-O inspection, signatures, relocation, symbols, checksums and standalone execution passed.')


if __name__ == '__main__':
    main()
