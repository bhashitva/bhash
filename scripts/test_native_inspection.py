#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real GDB inspection before/after stress GC; no mocked debugger or heap."""
import base64
import json
import os
from pathlib import Path
import subprocess
from native_debug_support import compiler, gdb, GDB_ARGUMENTS

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'target/native-inspection'
SOURCE = ROOT / 'examples/native/debug-inspection.bhash'
PRINTER = ROOT / 'editors/vscode/debug/bhash_gdb.py'


def run(args, **kwargs):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=180, **kwargs)
    if result.returncode:
        raise AssertionError(result.stdout + result.stderr)
    return result.stdout + result.stderr


def python_command(code):
    encoded = base64.b64encode(code.encode()).decode()
    return 'python import base64; exec(base64.b64decode("' + encoded + '"))\n'


INSPECT = r'''
frame = gdb.selected_frame()
assert frame.name() == 'मुख्य', frame.name()
def local(name):
    return frame.read_var(name)
def rendered(name):
    printer = lookup(local(name))
    assert printer is not None, (name, str(local(name).type))
    return printer.to_string()
def children(name):
    return dict(lookup(local(name)).children())
def roots_snapshot():
    result = []
    root = gdb.parse_and_eval('roots')
    while int(root):
        count = int(root['count'])
        assert count < 100000
        result.append((int(root), tuple(int(root['slots'][i]) for i in range(count))))
        root = root['previous']
    return result
before_counts = tuple(int(gdb.parse_and_eval(n)) for n in ('allocations', 'collections', 'live_objects'))
before_roots = roots_snapshot()
for repetition in range(20):
    assert 'भारत😀' in rendered('पाठ्य') and r'\u0000' in rendered('पाठ्य'), rendered('पाठ्य')
    assert rendered('रिक्तमान') == 'रिक्त'
    obj = children('वस्तु')
    assert int(obj['अंक']) == 42
    assert int(obj['विरासत']) == 9
    assert int(obj['अगली']) == int(local('वस्तु')), 'cycle changed'
    assert 'भारत😀' in lookup(obj['नाम']).to_string()
    assert [int(v) for v in children('अंक').values()] == [7, 42]
    assert [int(v) for v in children('सरणी').values()] == [9, 11]
    mapping = children('शब्द')
    assert 'साझा' in lookup(mapping['[0].कुंजी']).to_string()
    assert int(mapping['[0].मान']) == int(local('वस्तु'))
    assert int(children('डिब्बा')['मूल्य']) == 42
    assert [int(v) for v in children('बफ़र').values()] == [65, 66]
    assert bool(local('सच्चा')) and float(local('भिन्न')) == 3.5
    record = children('अभिलेखमान')
    assert int(record['व']) == 55 and 'अभिलेख😀' in lookup(record['नाम']).to_string()
assert before_counts == tuple(int(gdb.parse_and_eval(n)) for n in ('allocations', 'collections', 'live_objects')), 'inspection allocated or collected'
assert before_roots == roots_snapshot(), 'inspection changed precise roots'
if 'previous_collections' in globals():
    assert before_counts[1] > previous_collections + 200, before_counts
previous_collections = before_counts[1]
print('PASS inspection, immutable roots, no allocations, UTF-8/NUL, cycles, collections and boxing')
'''


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    program = OUT / ('inspection.exe' if os.name == 'nt' else 'inspection')
    run(compiler(ROOT, ['compile', SOURCE,
         '--target', 'native', '--debug', '-o', program]))
    markers = {name: next(i for i, line in enumerate(SOURCE.read_text(encoding='utf-8').splitlines(), 1)
                          if name in line) for name in ('DEBUG_BEFORE', 'DEBUG_AFTER', 'DEBUG_INNER')}
    commands = 'set pagination off\nset confirm off\nset disable-randomization off\nset charset UTF-8\nset language c\n'
    commands += 'set may-call-functions off\n' + python_command('exec(compile(open(' + repr(str(PRINTER)) + ', "rb").read(), ' + repr(str(PRINTER)) + ', "exec"))')
    commands += 'skip -rfu ^bh_\nskip -rfu ^bhash_\n'
    commands += 'break ' + json.dumps(SOURCE.as_posix()) + ':' + str(markers['DEBUG_BEFORE']) + '\n'
    commands += 'break ' + json.dumps(SOURCE.as_posix()) + ':' + str(markers['DEBUG_AFTER']) + '\nrun\n'
    commands += python_command(INSPECT)
    commands += 'step\ninfo frame\ninfo line *$pc\nx/8i $pc\nmaintenance info line-table debug-inspection.bhash\n' + python_command("assert gdb.selected_frame().name() == 'दुगुना'; assert int(gdb.selected_frame().read_var('मूल')) == 21; assert int(gdb.parse_and_eval('roots')) != before_roots[0][0], 'callee roots not registered at source stop'")
    commands += 'next\nfinish\ncontinue\n' + python_command(INSPECT)
    # Function evaluation must be rejected, rather than introducing a GC safepoint.
    commands += python_command("\ntry:\n gdb.parse_and_eval('bh_heap_objects()')\nexcept gdb.error:\n print('PASS inferior calls disabled')\nelse:\n raise AssertionError('inferior call unexpectedly permitted')\n")
    commands += 'continue\n'
    script = OUT/'inspection.gdb'
    script.write_text(commands, encoding='utf-8')
    transcript = run([gdb(), *GDB_ARGUMENTS, '--batch', '-x', script, program],
                     env={**os.environ, 'LC_ALL': 'C.UTF-8', 'BHASH_GC_STRESS': '1', 'BHASH_GC_STATS': '1'})
    (OUT/'gdb.log').write_text(transcript, encoding='utf-8')
    assert transcript.count('PASS inspection,') == 2, transcript
    assert 'PASS inferior calls disabled' in transcript and 'live=0' in transcript, transcript
    assert 'exited normally' in transcript, transcript
    print('PASS real GDB breakpoints, stepping, heap inspection across forced GC, and clean shutdown')


if __name__ == '__main__':
    main()
