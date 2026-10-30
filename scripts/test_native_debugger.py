#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise native --debug builds in GDB, not just debug-section presence."""
import os
from pathlib import Path
import subprocess
from native_debug_support import compiler, gdb, gdb_arguments
import sys

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'target/native-debugger'

def run(args, **kwargs):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=180, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr

def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    executable = OUTPUT / ('debugging.exe' if os.name == 'nt' else 'debugging')
    run(compiler(ROOT, ['compile',
         ROOT/'examples/native/debugging.bhash', '--target', 'native', '--debug', '-o', executable]))
    commands = OUTPUT / 'commands.gdb'
    commands.write_text('set pagination off\nset confirm off\nset disable-randomization off\nskip -rfu ^bh_\nbreak debugging.bhash:9\nrun\nstep\nframe\nbreak debugging.bhash:4\ncontinue\ninfo args\ninfo locals\nnext\nframe\nfinish\nframe\nnext\ninfo locals\ncontinue\n', encoding='utf-8')
    transcript = run([gdb(), *gdb_arguments(), '--batch', '-x', commands, executable], env={**os.environ, 'LC_ALL':'C.UTF-8'})
    (OUTPUT/'gdb.log').write_text(transcript, encoding='utf-8')
    for expected in ('दुगुना', 'मुख्य', 'debugging.bhash:4', 'उत्तर = 42', 'मूल = 21', 'फल = 42', 'exited normally'):
        if expected not in transcript:
            raise AssertionError(expected + '\n' + transcript)
    if 'bfn0' in transcript or 'bfn1' in transcript:
        raise AssertionError('GDB displayed internal function names\n' + transcript)
    print('Native GDB source breakpoints, step/next/finish, Hindi function names and scalar variables passed.')

if __name__ == '__main__':
    main()
