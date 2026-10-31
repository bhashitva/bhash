# SPDX-License-Identifier: Apache-2.0
"""Interrupt/continue sampling of an unchanged release executable, without inferior calls."""
import ast
from collections import Counter
import json
import os
import queue
import random
import re
import subprocess
import threading
import time


def sample(executable, cwd, env, output):
    import pty
    master, slave = pty.openpty()
    p = subprocess.Popen(['gdb', '--nx', '--nh', '--quiet', '--interpreter=mi2', str(executable)],
                         cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, encoding='utf-8', bufsize=1)
    lines, q, inferior = [], queue.Queue(), bytearray()

    def reader():
        for s in p.stdout:
            lines.append(s.rstrip()); q.put(s.rstrip())
        q.put('EOF')

    def console():
        while True:
            try: block = os.read(master, 65536)
            except OSError: break
            if not block: break
            inferior.extend(block)

    t = threading.Thread(target=reader); t.start()
    token, exited, pending_stops = 0, False, []

    def command(text):
        nonlocal token, exited
        token += 1; wanted = str(token)
        p.stdin.write(wanted + text + '\n'); p.stdin.flush()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            line = q.get(timeout=10)
            if line == 'EOF': raise RuntimeError('GDB exited unexpectedly')
            if line.startswith('*stopped'):
                pending_stops.append(line)
                if 'exited' in line: exited = True
            if line.startswith(wanted + '^'): return line
        raise RuntimeError('GDB command timeout')

    def stopped():
        nonlocal exited
        if pending_stops: return pending_stops.pop(0)
        while True:
            line = q.get(timeout=10)
            if line == 'EOF': raise RuntimeError('Unexpected GDB EOF')
            if line.startswith('*stopped'):
                if 'exited' in line: exited = True
                return line

    snapshots = []
    try:
        for cmd in ('-gdb-set pagination off', '-gdb-set confirm off', '-gdb-set mi-async on',
                    '-gdb-set disable-randomization off', '-inferior-tty-set ' + json.dumps(os.ttyname(slave))):
            if '^error' in command(cmd): raise RuntimeError('GDB setup failed: ' + cmd)
        os.close(slave); slave = None
        c = threading.Thread(target=console); c.start()
        command('-exec-run')
        start = time.monotonic(); time.sleep(1.6)
        rng = random.Random(1729)
        while time.monotonic() - start < 12 and not exited:
            response = command('-exec-interrupt')
            if '^error' in response:
                if exited: break
                event = stopped()
                if exited: break
                raise RuntimeError(response + event)
            event = stopped()
            if exited: break
            stack = command('-stack-list-frames 0 16')
            names = [ast.literal_eval('"' + m + '"') for m in re.findall(r'func="((?:\\.|[^"\\])*)"', stack)]
            snapshots.append({'at_s': time.monotonic()-start, 'functions': names, 'stop': event})
            command('-exec-continue')
            time.sleep(rng.uniform(.011, .019))
        if not exited:
            # Wait for the timed workload to finish, without mutating its data.
            deadline = time.monotonic()+20
            while not exited and time.monotonic()<deadline:
                line = q.get(timeout=20)
                if line.startswith('*stopped') and 'exited' in line: exited=True
        command('-gdb-exit')
        p.wait(timeout=10); t.join(timeout=5); c.join(timeout=5)
    finally:
        if p.poll() is None: p.kill(); p.wait()
        if slave is not None: os.close(slave)
        os.close(master)
        (output / 'gdb-mi.log').write_text('\n'.join(lines),encoding='utf-8')
    counts = Counter(s['functions'][0] if s['functions'] else 'unknown' for s in snapshots)
    result = {'method': 'jittered interrupt/continue stack snapshots; not hardware CPU-cycle samples',
              'exited': exited, 'samples': snapshots, 'flat': dict(counts.most_common()),
              'stdout': inferior.decode('utf-8','replace')}
    (output/'stack-samples.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    if not exited or len(snapshots)<100: raise RuntimeError('Insufficient GDB samples')
    return result
