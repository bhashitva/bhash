# SPDX-License-Identifier: Apache-2.0
"""Host-native debugger test invocations, including Windows Java Unicode argv."""
import base64
import json
import os
from pathlib import Path


GDB_ARGUMENTS = ['--nx', '--nh', '-eiex', 'set python ignore-environment on',
                 '-eiex', 'set python dont-write-bytecode on', '-iex', 'set auto-load off']


def compiler(root, arguments):
    java = Path(os.environ['JAVA_HOME']) / ('bin/java.exe' if os.name == 'nt' else 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    jar = root / 'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
    command = [java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', jar]
    if os.name == 'nt':
        envelope = json.dumps({'args': [str(a) for a in arguments], 'cwd': str(root)}, ensure_ascii=False).encode()
        return command + ['--utf8-args', base64.b64encode(envelope).decode()]
    return command + list(arguments)


def gdb():
    return os.environ.get('BHASH_GDB', 'gdb')
