#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Diagnostic experiment only: unchanged release builds, CPU samples and instruction profiles."""
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'benchmarks'))
from run import clean_env
from workloads import expected, generate, checksum
from sample_gdb import sample


def execute(cmd, cwd, env, log, timeout=240, required=True):
    start=time.perf_counter()
    p=subprocess.run(cmd,cwd=cwd,env=env,capture_output=True,encoding='utf-8',errors='replace',timeout=timeout)
    record={'command':cmd,'elapsed_s':time.perf_counter()-start,'returncode':p.returncode,'stdout':p.stdout,'stderr':p.stderr}
    log.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    if required and p.returncode: raise RuntimeError(f'{cmd[0]} failed: {log}: {p.stderr[-1500:]}')
    return record


def parse_callgrind(path):
    names={}; current=None; callee=None; edge=False; positions=1
    costs=Counter(); calls=Counter(); inclusive=Counter(); total=None
    def name(value):
        m=re.match(r'\((\d+)\)(?: (.*))?$',value)
        if not m:return value
        if m[2] is not None:names[m[1]]=m[2]
        return names.get(m[1],'id:'+m[1])
    for line in path.read_text().splitlines():
        if line.startswith('positions:'): positions=len(line.split())-1
        elif line.startswith(('fn=','cfn=')):
            field,value=line.split('=',1); resolved=name(value)
            if field=='fn':current=resolved
            else:callee=resolved
        elif line.startswith('calls='):
            calls[callee]+=int(line[6:].split()[0]); edge=True
        elif line.startswith(('totals:','summary:')): total=int(line.split()[1])
        elif line and (line[0].isdigit() or line[0] in '+-*'):
            parts=line.split()
            if len(parts)<=positions:continue
            if edge:inclusive[callee]+=int(parts[positions])
            elif current:costs[current]+=int(parts[positions])
            edge=False
    if total is None:raise RuntimeError('No Callgrind totals')
    if sum(costs.values())!=total:raise RuntimeError(f'Unbalanced instruction accounting: {sum(costs.values())}/{total}')
    return {'instructions':total,'self_instructions':dict(costs.most_common()),'inclusive_call_instructions':dict(inclusive.most_common()),'calls':dict(calls.most_common())}


def fixed_sources(out):
    sources=out/'fixed-sources'; sources.mkdir()
    cases=[]
    for kind,sizes in (('array-sort',(64,256,1024)),('objects',(64,512,4096))):
        for n in sizes:
            calls=max(2, 100000//(n*n) if kind=='array-sort' else 1000000//n)
            if kind=='array-sort':calls=max(2,1000000//(n*n))
            cases.append((f'{kind}-{n}',kind,n,calls,(ROOT/'benchmarks/workloads'/f'{kind}.bhash').read_text(encoding='utf-8'),'', 'काम(न)',expected(kind,n),expected(kind,n+1)))
    for kind,n,count in (('integer',10000,100),('decimal',10000,100),('text',32,100),('json',16,100),('list-map',128,1000)):
        cases.append((kind+'-medium',kind,n,count,(ROOT/'benchmarks/workloads'/f'{kind}.bhash').read_text(encoding='utf-8'),'','काम(न)',expected(kind,n),expected(kind,n+1)))
    n=256
    for operation in ('read','write'):
        body=('कार्य काम(अ: पूर्णांक[], न: पूर्णांक) -> पूर्णांक { मान इ: पूर्णांक=०; मान योग: पूर्णांक=०; जबतक (इ<न) { '
              + ('योग=योग+अ[इ];' if operation=='read' else 'अ[इ]=इ+१; योग=योग+१;')+' इ=इ+१; } लौटाओ योग; }')
        init=f'स्थिर अ: पूर्णांक[]=नया पूर्णांक[{n+1}]; मान ज: पूर्णांक=०; जबतक (ज<{n+1}) {{ अ[ज]=ज+१; ज=ज+१; }}'
        a=n*(n+1)//2 if operation=='read' else n
        b=(n+1)*(n+2)//2 if operation=='read' else n+1
        cases.append((f'array-{operation}',f'array-{operation}',n,4000,body,init,'काम(अ,न)',a,b))
    decl='वर्ग कड़ी { सार्वजनिक मान अगली: कड़ी; सार्वजनिक मान अंक: पूर्णांक; }\n'
    alloc=decl+'कार्य काम(न: पूर्णांक)->पूर्णांक { मान इ: पूर्णांक=०; मान योग: पूर्णांक=०; जबतक (इ<न) { स्थिर क:कड़ी=नया कड़ी(); क.अंक=इ; योग=योग+क.अंक; इ=इ+१; } लौटाओ योग; }'
    cases.append(('object-alloc','object-alloc',512,1953,alloc,'','काम(न)',512*511//2,513*512//2))
    traverse=decl+'कार्य काम(सिर:कड़ी,न:पूर्णांक)->पूर्णांक { मान क:कड़ी=सिर; मान इ:पूर्णांक=०; मान योग:पूर्णांक=०; जबतक(इ<न) { योग=योग+क.अंक; क=क.अगली; इ=इ+१; } लौटाओ योग; }'
    init='मान सिर:कड़ी=रिक्त; मान ज:पूर्णांक=०; जबतक(ज<५१३) { स्थिर क:कड़ी=नया कड़ी(); क.अंक=ज; क.अगली=सिर; सिर=क; ज=ज+१; }'
    cases.append(('object-traverse','object-traverse',512,2000,traverse,init,'काम(सिर,न)',131328,131328))
    stores=decl+'कार्य काम(क:कड़ी,द:कड़ी,न:पूर्णांक)->पूर्णांक { मान इ:पूर्णांक=०; जबतक(इ<न) { क.अगली=द; इ=इ+१; } यदि(क.अगली!=द) { त्रुटि("store"); } लौटाओ इ; }'
    cases.append(('object-reference-store','object-reference-store',512,2000,stores,'स्थिर क:कड़ी=नया कड़ी(); स्थिर द:कड़ी=नया कड़ी();','काम(क,द,न)',512,513))
    result=[]
    for key,kind,n,count,body,init,call,a,b in cases:
        main=f'''कार्य मुख्य()->शून्य {{ {init}
मान इ:पूर्णांक=०; मान योग:पूर्णांक=०;
जबतक(इ<{count}) {{ स्थिर न:पूर्णांक={n}+इ%२; स्थिर मूल्य:पूर्णांक={call}; मान सही:पूर्णांक={a}; यदि(इ%२!=०) {{ सही={b}; }} यदि(मूल्य!=सही) {{ त्रुटि("checksum"); }} योग=(योग+मूल्य)%१००००००००७; इ=इ+१; }}
लिखो("BHASH_FIXED",इ,योग); }}'''
        source=sources/(key+'.bhash');source.write_text(body+'\n'+main,encoding='utf-8')
        result.append({'key':key,'kind':kind,'n':n,'count':count,'checksum':(((count+1)//2)*a+(count//2)*b)%1000000007,'source':source})
    return result


def validate_fixed(case,text):
    fields=text.split()
    if len(fields)!=3 or fields[0]!='BHASH_FIXED' or int(fields[1])!=case['count'] or int(fields[2])!=case['checksum']:raise RuntimeError('Fixed-work checksum failed: '+case['key'])


def validate_duration(case,text):
    lines=text.splitlines()
    for line in lines:
        if line.startswith('BHASH_RESULT'):
            _,calls,value=line.split()
            if int(calls)<1 or int(value)!=checksum(case['name'],case['n'],int(calls)):raise RuntimeError('Duration checksum')
    if not lines or lines[0]!='BHASH_READY' or sum(s.startswith('BHASH_RESULT') for s in lines)!=6:raise RuntimeError('Duration protocol incomplete')


def main():
    out=ROOT/'target/native-profiles'; out.mkdir(parents=True,exist_ok=False)
    env=clean_env(); java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
    compiler=ROOT/'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
    wrapper=Path(__file__).with_name('capture_clang.py');wrapper.chmod(0o755)
    env.update(BHASH_NATIVE_TOOLCHAIN='system',BHASH_CLANG=str(wrapper),BHASH_LLVM_OPT='opt-18')
    metadata={'platform':platform.platform(),'cpu':Path('/proc/cpuinfo').read_text(),'compiler_sha256':hashlib.sha256(compiler.read_bytes()).hexdigest(),
              'git_sha':os.environ.get('GITHUB_SHA'),'runtime_sha256':hashlib.sha256((ROOT/'src/main/resources/dev/bhash/backend/native/bhash_runtime.c').read_bytes()).hexdigest(),
              'tools':{}}
    for tool in ('clang-18','opt-18','valgrind','gdb'):
        metadata['tools'][tool]=execute([tool,'--version'],ROOT,env,out/(tool+'-version.json'))['stdout']
    (out/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')

    def build(source,key):
        directory=out/key;directory.mkdir();capture=directory/'captured'
        local_env=env|{'BHASH_PROFILE_CAPTURE':str(capture)}
        executable=directory/'app'
        execute([java,'-jar',str(compiler),'compile',str(source),'--target','native','-o',str(executable)],ROOT,local_env,directory/'compile.json')
        if (capture/'bhash_runtime.c').read_bytes()!=(ROOT/'src/main/resources/dev/bhash/backend/native/bhash_runtime.c').read_bytes():raise RuntimeError('Runtime source changed')
        execute(['objdump','-d','-Mintel',str(executable)],ROOT,env,directory/'disassembly.json')
        execute(['nm','-S','--defined-only',str(executable)],ROOT,env,directory/'symbols.json')
        return directory,executable

    fixed=fixed_sources(out)
    results=[]
    for case in fixed:
        directory,executable=build(case['source'],case['key'])
        native=execute([str(executable)],directory,env,directory/'native.json');validate_fixed(case,native['stdout'])
        stats=execute([str(executable)],directory,env|{'BHASH_GC_STATS':'1'},directory/'gc.json');validate_fixed(case,stats['stdout'])
        if 'live=0' not in stats['stderr']:raise RuntimeError('GC cleanup failed')
        profile=directory/'callgrind.out'
        recorded=execute(['valgrind','--tool=callgrind','--callgrind-out-file='+str(profile),'--dump-instr=no',str(executable)],directory,env,directory/'callgrind-run.json',timeout=300)
        validate_fixed(case,recorded['stdout'])
        execute(['callgrind_annotate','--inclusive=no','--threshold=100','--auto=no',str(profile)],directory,env,directory/'callgrind-self.json')
        execute(['callgrind_annotate','--inclusive=yes','--tree=both','--threshold=99','--auto=no',str(profile)],directory,env,directory/'callgrind-tree.json')
        counts=parse_callgrind(profile)
        (directory/'instruction-counts.json').write_text(json.dumps(counts,indent=2),encoding='utf-8')
        row={**case,'source':str(case['source'].relative_to(out)),'gc':stats['stderr'],'counts':counts}
        results.append(row)
        print('PROFILED',case['key'],counts['instructions'],flush=True)
    # Original duration-based mixed workloads, unchanged operations, longer batches
    # for sampling. Profiled elapsed/throughput is never treated as baseline timing.
    duration=generate(out/'sample-sources',['medium'],1500,1000,5,['array-sort','objects','decimal'])
    sampling=[]
    (out/'results.json').write_text(json.dumps({'environment':metadata,'fixed':results,'sampling':sampling},ensure_ascii=False,indent=2),encoding='utf-8')
    for case in duration:
        if case['name'] not in ('array-sort','objects','decimal'):continue
        key=case['name']+'-samples';directory,executable=build(out/'sample-sources'/case['source'],key)
        native=execute([str(executable)],directory,env,directory/'native.json');validate_duration(case,native['stdout'])
        perf=shutil.which('perf')
        status={'key':key,'method':'none'}
        if perf:
            recorded=execute([perf,'record','-q','-e','cpu-clock:u','-F','199','-o',str(directory/'perf.data'),'--',str(executable)],directory,env,directory/'perf-record.json',timeout=30,required=False)
            status['perf_returncode']=recorded['returncode'];status['perf_stderr']=recorded['stderr']
            if recorded['returncode']==0:
                validate_duration(case,recorded['stdout'])
                execute([perf,'report','--stdio','--no-children','--percent-limit','0.5','-i',str(directory/'perf.data')],directory,env,directory/'perf-report.json')
                status['method']='perf cpu-clock user-space samples'
        # Independent, portable user-space stack sampling is retained even when
        # perf succeeds. No sysctl changes, no sudo, no target calls or writes.
        gdb=sample(executable,directory,env,directory);validate_duration(case,gdb['stdout'])
        status['gdb_samples']=len(gdb['samples']);status['gdb_flat']=gdb['flat']
        sampling.append(status)
        (out/'results.json').write_text(json.dumps({'environment':metadata,'fixed':results,'sampling':sampling},ensure_ascii=False,indent=2),encoding='utf-8')
        print('SAMPLED',key,status['gdb_samples'],flush=True)
    (out/'results.json').write_text(json.dumps({'environment':metadata,'fixed':results,'sampling':sampling},ensure_ascii=False,indent=2),encoding='utf-8')
    print('All profile checksums passed; compiler and runtime sources unchanged.',flush=True)


if __name__=='__main__':main()
