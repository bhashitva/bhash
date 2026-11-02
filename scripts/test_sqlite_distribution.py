#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CI-only native SQLite release/relocation validation. Requires target toolchain."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'target/sqlite-distribution'
JAVA=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
JAR=ROOT/'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
EXPECTED='3.54.1\n६ ६ अंक\n४२ १.२५ सत्य भारत😀\nबाइटबफ़र ० सत्य\nरिक्त\n०\n'

def run(command, cwd=ROOT, env=None):
    result=subprocess.run([str(x) for x in command],cwd=cwd,env=env or os.environ,capture_output=True,text=True,encoding='utf-8',timeout=180)
    assert result.returncode==0,result.stdout+result.stderr
    return result

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    for debug in (False,True):
        binaries=[]
        for name in ('first workspace','दूसरा स्थान'):
            work=OUT/name;work.mkdir(exist_ok=True)
            source=work/'database.bhash';source.write_bytes((ROOT/'examples/sqlite/values.bhash').read_bytes())
            executable=work/('database-debug' if debug else 'database')
            flags=['--debug'] if debug else []
            run([JAVA,'-jar',JAR,'compile',source,'--target','native','--source-root',work,'-o',executable,*flags])
            report=json.loads(run([executable,'--bhash-build-info']).stdout)
            assert report['sqlite'] and report['sqlite_version']=='3.54.1'
            assert len(report['sqlite_source_sha3_256'])==64
            inspected=json.loads(run([JAVA,'-jar',JAR,'native','inspect',executable,'--json','--require-runtime-match']).stdout)
            assert not any('sqlite' in x.lower() for x in inspected['libraries'])
            result=run([executable],cwd=work,env={**os.environ,'BHASH_GC_STRESS':'1','BHASH_GC_STATS':'1','PATH':'/usr/bin:/bin'})
            assert result.stdout==EXPECTED,result.stdout
            assert 'live=0' in result.stderr,result.stderr
            binaries.append(hashlib.sha256(executable.read_bytes()).hexdigest())
        assert binaries[0]==binaries[1],f'Non-reproducible SQLite executable, debug={debug}'
    print('PASS standalone statically linked SQLite, forced GC, metadata and relocated reproducible debug/release builds')

if __name__=='__main__':main()
