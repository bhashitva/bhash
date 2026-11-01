#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Same-host randomized before/after experiment using the baseline observer."""
import argparse
import json
import os
from pathlib import Path
import platform
import random
import statistics
import subprocess
import sys
from run import clean_env, measure, validate, summary, sha
from workloads import generate


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before',type=Path,required=True)
    parser.add_argument('--after',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=Path('target/array-access-benchmarks'))
    args=parser.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    env=clean_env();java=str(Path(env['JAVA_HOME'])/'bin/java');dotnet=env.get('BHASH_DOTNET','dotnet')
    logs=out/'logs';logs.mkdir()
    cases=generate(out/'sources',['medium'],1500,300,5)
    extra=generate(out/'array-sizes',['small','large'],1500,300,5,['array-sort'])
    for case in extra:
        if case['name']=='array-sort':
            case['source']='../array-sizes/'+case['source'];cases.append(case)
    compilers={'before':args.before.resolve(),'after':args.after.resolve()}
    def version(command):
        r=subprocess.run(command,env=env,capture_output=True,text=True,timeout=60,check=True)
        return r.stdout+r.stderr
    data={'configuration':{'trials':7,'compile_trials':3,'warmup_ms':1500,'batch_ms':300,'batches':5,'seed':1729},
          'environment':{'platform':platform.platform(),'cpu':next(x.split(':',1)[1].strip() for x in Path('/proc/cpuinfo').read_text().splitlines() if x.startswith('model name')),
          'logical_cpus':os.cpu_count(),'java':version([java,'-version']),'dotnet':version([dotnet,'--info']),
          'native_doctor':version([java,'-jar',str(compilers['after']),'native','doctor','--json']),
          'observer_launch_wall_s':[measure(['/bin/true'],out,env,10,logs/f'observer-{i}.json')['wall_s'] for i in range(7)],
          'run_id':os.environ.get('GITHUB_RUN_ID'),'git_sha':os.environ.get('GITHUB_SHA')},
          'compiler_sha256':{k:sha(v) for k,v in compilers.items()},
          'source_sha256':{str(p.relative_to(out)):sha(p) for directory in ['sources','array-sizes'] for p in (out/directory).rglob('*') if p.is_file()},'results':[]}
    entries=[dict(case,variant=v,target=t,compile=[],runtime=[]) for case in cases for v in compilers for t in ['jvm','clr','native']]
    rng=random.Random(1729)
    def key(e):return e['variant']+'-'+e['name']+'-'+e['size']+'-'+e['target']
    for trial in range(3):
        order=entries.copy();rng.shuffle(order)
        for e in order:
            directory=out/'build'/key(e)/str(trial);directory.mkdir(parents=True)
            app=directory/('app.jar' if e['target']=='jvm' else 'app')
            record=measure([java,'-jar',str(compilers[e['variant']]),'compile',str(out/'sources'/e['source']),'--target',e['target'],'-o',str(app)],out,env,300,logs/f'{key(e)}-compile-{trial}.json')
            if record['exit_code']!=0:raise RuntimeError('Compilation failed: '+key(e))
            e['compile'].append({k:record[k] for k in ['wall_s','cpu_s','peak_rss_bytes']});e['artifact']=app
            e['application_bytes']=sum(p.stat().st_size for p in directory.rglob('*') if p.is_file())
        print('Compilation trial',trial+1,'passed',flush=True)
    for trial in range(7):
        order=entries.copy();rng.shuffle(order)
        for e in order:
            directory=out/'runs'/key(e)/str(trial);directory.mkdir(parents=True)
            app=str(e['artifact']);command=[java,'-jar',app] if e['target']=='jvm' else [dotnet,str(e['artifact']/'BhashProgram.dll')] if e['target']=='clr' else [app]
            record=measure(command,directory,env,180,logs/f'{key(e)}-run-{trial}.json');metrics=validate(e,record,5)
            e['runtime'].append({k:record[k] for k in ['wall_s','cpu_s','peak_rss_bytes']}|metrics)
        print('Runtime trial',trial+1,'all checksums passed',flush=True)
    for e in entries:
        e['summary']={k:summary([r.get(k) for r in e['runtime']]) for k in ['wall_s','cpu_s','peak_rss_bytes','ready_s','calls_per_s','measured_cpu_s_per_call','measured_rss_median_bytes']}
        e['summary'].update({'compile_'+k:summary([r[k] for r in e['compile']]) for k in ['wall_s','cpu_s','peak_rss_bytes']})
        if e['target']=='native' and 'expected' not in e:
            record=measure([str(e['artifact'])],out/'runs'/key(e)/'0',env|{'BHASH_GC_STATS':'1'},180,logs/f'{key(e)}-gc.json');validate(e,record,5)
            if 'live=0' not in record['stderr']:raise RuntimeError('GC cleanup failed')
            e['native_gc_stats']=record['stderr']
        e['artifact']=str(e['artifact'].relative_to(out));data['results'].append(e)
    (out/'results.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    rows=['# Primitive array access: randomized same-host comparison','','Seven launches per variant/target/case; three fresh builds; 1.5 s warmup + five 300 ms measured batches. Original benchmark sources and independent checksums. Before/after and targets interleaved with fixed seed 1729. Profiles/forced GC excluded from timing.','','| Case | Target | Before calls/s | After calls/s | Throughput ratio | Before CPU us/call | After CPU us/call | Before/after RSS MiB |','| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    def metric(e,k):return e['summary'][k]['median']
    for case in cases:
        for t in ['jvm','clr','native']:
            b=next(e for e in entries if e['variant']=='before' and e['name']==case['name'] and e['size']==case['size'] and e['target']==t)
            a=next(e for e in entries if e['variant']=='after' and e['name']==case['name'] and e['size']==case['size'] and e['target']==t)
            if 'expected' in case:continue
            bv=metric(b,'calls_per_s');av=metric(a,'calls_per_s')
            rows.append(f"| {case['name']}/{case['size']} | {t} | {bv:.2f} | {av:.2f} | {av/bv:.3f}x | {metric(b,'measured_cpu_s_per_call')*1e6:.3f} | {metric(a,'measured_cpu_s_per_call')*1e6:.3f} | {metric(b,'measured_rss_median_bytes')/1048576:.3f} / {metric(a,'measured_rss_median_bytes')/1048576:.3f} |")
    rows+=['','Ratios are after/before throughput. Raw results retain per-trial data, variability, startup, build CPU/wall/RSS, artifact sizes and untimed GC diagnostics. This Linux hosted-runner experiment does not establish Windows/macOS speedups. No hard CI performance threshold.']
    (out/'comparison.md').write_text('\n'.join(rows)+'\n',encoding='utf-8')
    print('Validated',len(entries),'case/target/variant combinations',flush=True)

if __name__=='__main__':main()
