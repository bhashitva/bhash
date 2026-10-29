#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Linux release gate: path-independent builds, static inspection, packaging and ABI guard."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'target/native-release'
JAR = ROOT / 'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
ENV = {**os.environ, 'SOURCE_DATE_EPOCH': '1700000000', 'LC_ALL': 'C.UTF-8'}


def run(args, *, env=None, ok=True):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, env=env or ENV,
                            capture_output=True, text=True, encoding='utf-8', timeout=180)
    message = result.stdout + result.stderr
    if (result.returncode == 0) != ok:
        raise AssertionError(f'{args}: exit={result.returncode}\n{message}')
    return message


def cli(*args, **kwargs):
    return run([JAVA, '-jar', JAR, *args], **kwargs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    doctor = json.loads(cli('native', 'doctor', '--json'))
    assert doctor['version'].startswith('18.') and doctor['runtimeAbi'] == 15
    (OUT/'doctor.json').write_text(json.dumps(doctor, indent=2)+'\n')
    roots = [OUT/'first workspace', OUT/'दूसरा बहुत अलग स्थान']
    for root in roots:
        root.mkdir(parents=True, exist_ok=True)
        (root/'hello.bhash').write_text('कार्य मुख्य() -> शून्य { लिखो("नमस्ते 😀"); }\n', encoding='utf-8')
    for debug in (False, True):
        binaries, archives = [], []
        for root in roots:
            binary = root / ('hello-debug' if debug else 'hello')
            flags = ['--debug'] if debug else []
            cli('compile', root/'hello.bhash', '--target', 'native', '--source-root', root, '-o', binary, *flags)
            binaries.append(binary)
            report = json.loads(cli('native', 'inspect', binary, '--json', '--require-runtime-match'))
            assert report['runtimeMatchesCompiler'] and report['buildInfo']['runtime_abi'] == 15
            assert json.loads(run([binary, '--bhash-build-info'])) == report['buildInfo']
            assert run([binary]) == 'नमस्ते 😀\n'
            archive = root / ('hello-debug.tar.gz' if debug else 'hello.tar.gz')
            cli('native', 'package', root/'hello.bhash', '-o', archive, '--name', 'hello', '--version', '1.0.0', *flags)
            archives.append(archive)
        assert digest(binaries[0]) == digest(binaries[1]), f'Non-reproducible binary (debug={debug})'
        assert digest(archives[0]) == digest(archives[1]), f'Non-reproducible archive (debug={debug})'
        print(f'Reproducible debug={debug}: ELF {digest(binaries[0])}; archive {digest(archives[0])}')
        if not debug:
            shutil.copyfile(archives[0], OUT/'hello-1.0.0-linux-x86_64.tar.gz')
            with tarfile.open(archives[0], 'r:gz') as tar:
                members = tar.getmembers()
                assert len(members) == 10, [m.name for m in members]
                for member in members:
                    assert member.isfile() and member.uid == member.gid == 0 and member.mtime == 1700000000
                    assert '..' not in Path(member.name).parts and not member.name.startswith('/')
                    assert member.mode == (0o755 if '/bin/' in member.name or member.name.endswith('.sh') else 0o644)
                tar.extractall(OUT/'unpacked', filter='data')
            bundle = OUT/'unpacked/hello-1.0.0-linux-x86_64'
            run(['sh', '-c', 'cd "$1" && sha256sum -c SHA256SUMS', 'verify', bundle])
            assert run([bundle/'run.sh']) == 'नमस्ते 😀\n'
            manifest = json.loads((bundle/'manifest.json').read_text())
            assert list(manifest['source_sha256']) == ['/bhash/source/hello.bhash']
            assert manifest['executable_sha256'] == digest(bundle/'bin/hello')
            rejected = OUT/'preserved.tar.gz'
            rejected.write_bytes(b'keep previous output')
            failure = cli('native', 'package', roots[0]/'hello.bhash', '-o', rejected, '--name', 'hello', '--version', '1', '--max-glibc', '2.1', ok=False)
            assert 'BH9106' in failure and rejected.read_bytes() == b'keep previous output'
            rejected.unlink()
    # Reject a non-ELF before any tool sees it.
    bad = OUT/'not-elf'; bad.write_text('bad')
    assert 'BH9106' in cli('native', 'inspect', bad, ok=False)
    # Check actionable setup errors with controlled, executable tool wrappers.
    wrapper = OUT/'fake-clang'; wrapper.write_text('#!/bin/sh\necho "clang version 19.1.0"\n');wrapper.chmod(0o755)
    assert 'BH9103' in cli('native', 'doctor', env={**ENV, 'BHASH_CLANG': str(wrapper)}, ok=False)
    wrapper.write_text('#!/bin/sh\necho "clang version 18.999.0"\n')
    assert 'versions differ' in cli('native', 'doctor', env={**ENV, 'BHASH_CLANG': str(wrapper)}, ok=False)
    assert 'BH9102' in cli('native', 'doctor', env={**ENV, 'BHASH_CLANG': str(OUT/'absent')}, ok=False)
    real_clang = shutil.which(os.environ.get('BHASH_CLANG', 'clang-18'))
    wrapper.write_text('#!/bin/sh\nif [ "$1" = "-dumpmachine" ]; then echo aarch64-linux-gnu; else exec "'+real_clang+'" "$@"; fi\n')
    assert 'BH9104' in cli('native', 'doctor', env={**ENV, 'BHASH_CLANG': str(wrapper)}, ok=False)
    wrapper.write_text('#!/bin/sh\ncase "$1" in --version|-dumpmachine|--print-prog-name=ld) exec "'+real_clang+'" "$@";; *) echo "test missing C headers" >&2; exit 1;; esac\n')
    assert 'BH9105' in cli('native', 'doctor', env={**ENV, 'BHASH_CLANG': str(wrapper)}, ok=False)
    # Deliberately mismatch the runtime's exported ABI. IR must fail at the linker.
    probe = OUT/'Emit.java'
    probe.write_text('''import java.nio.file.*;import java.util.*;import dev.bhash.backend.*;import dev.bhash.backend.llvm.*;import dev.bhash.frontend.*;
class Emit { public static void main(String[] args)throws Exception { Path p=Path.of(args[0]);Files.writeString(Path.of(args[1]),LlvmEmitter.emit(ManagedEmitter.core(List.of(new SourceFile(p.toString(),Files.readString(p)))))); }}
''', encoding='utf-8')
    ir = OUT/'guard.ll'
    run([JAVA, '-cp', JAR, probe, roots[0]/'hello.bhash', ir])
    runtime = OUT/'bhash_runtime.c';header = OUT/'bhash_runtime.h'
    runtime.write_bytes((ROOT/'src/main/resources/dev/bhash/backend/native/bhash_runtime.c').read_bytes().replace(b'bh_runtime_abi_v15', b'bh_runtime_abi_v14'))
    shutil.copyfile(ROOT/'src/main/resources/dev/bhash/backend/native/bhash_runtime.h', header)
    error = run([real_clang, ir, runtime, '-lm', '-o', OUT/'bad-abi'], ok=False)
    assert 'bh_runtime_abi_v15' in error
    print('Native release/toolchain/compatibility checks passed')


if __name__ == '__main__':
    main()
