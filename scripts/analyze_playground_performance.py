# SPDX-License-Identifier: Apache-2.0
"""Turn raw publish and browser measurements into a reproducible comparison report."""
import json, pathlib, re, statistics
root=pathlib.Path(__file__).resolve().parents[1]
publish=root/'target/browser-publish-profile'
folder=root/'target/playground-performance'
data=json.loads((folder/'measurements.json').read_text())
profile=json.loads((publish/'results.json').read_text())
median=statistics.median
rows=[]
for sample in data['runs']:
    ready=next(item for item in sample['api'] if item['job']['status']=='ready')
    messages={item['type']:item['t'] for item in sample['messages']}
    job=ready['job']
    rows.append({'kind':sample['kind'],'label':sample['label'],'seconds':(sample['output']-sample['click'])/1000,
                 'readySeconds':(ready['t']-sample['click'])/1000,'queueMs':job['timings']['queueMs'],
                 'compileMs':job['timings']['compileMs'],
                 'frameSeconds':(messages['ready']-ready['t'])/1000,
                 'workerSeconds':(messages['running']-messages['ready'])/1000,
                 'executionSeconds':(sample['output']-messages['running'])/1000})
    resources=json.loads((folder/(sample['kind']+'-resources.json')).read_text())
    start=sample['timeOrigin']+sample['click'];end=sample['timeOrigin']+sample['output']
    assets=[entry for entry in resources if start<=entry['timing']['startTime']<=end]
    if not assets:raise RuntimeError('No artifact resource events for '+sample['label'])
    rows[-1]['assetReportedResponseBytes']=(sum(max(0,entry['sizes']['responseBodySize'])+max(0,entry['sizes']['responseHeadersSize']) for entry in assets)
                                             if all(entry['sizes'] is not None for entry in assets) else None)
    completed=[entry for entry in assets if entry['timing']['responseEnd']>=0]
    rows[-1]['assetRequestEnvelopeSeconds']=(max(entry['timing']['startTime']+entry['timing']['responseEnd'] for entry in completed)
                                            -min(entry['timing']['startTime'] for entry in completed))/1000 if completed else None

def selected(kind,prefix):return [row for row in rows if row['kind']==kind and row['label'].startswith(prefix)]
before=median(row['seconds'] for row in selected('baseline','hello-cold'))
after=median(row['seconds'] for row in selected('prebuilt','hello-cold'))
text=['# Prebuilt browser host: controlled before/after comparison','',
      f'Changed Hello programs: median Run-to-output **{before:.3f} s → {after:.3f} s** '
      f'(**{100*(1-after/before):.1f}% lower**, {before/after:.1f}× as fast in this test).','',
      data['environment']+'. Each path uses a fresh browser context and a fresh service cache. '
      'All twenty runs check the expected output. A cached run still starts a new runtime and worker.','',
      '**These are controlled loopback measurements, not a new public Edge/WAN measurement.** '
      'The live baseline remains in `benchmarks/playground-profiling/report.md`. '
      'No network compression or throttling is applied here. Baseline runs precede prebuilt runs; '
      'warming and hosted-runner variability limit extrapolation to other machines.','',
      '## Publishing profile','',
      f"SDK: `{profile['sdk']}`; platform: `{profile['platform']}`; logical CPUs: {profile['logicalCPUs']}. ",
      f"Compiler SHA-256: `{profile['compilerSHA256']}`.",'',
      '| Operation | Elapsed seconds |','| --- | ---: |']
for item in profile['runs']:text.append(f"| {item['label']} | {item['seconds']:.3f} |")
text+=['','Per-publish SDK task times (milliseconds); nested targets overlap and are not added to these task durations.','',
       '| Sample | Restore | C# compile | Trimming | Brotli | Gzip | WebCIL |','| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
for index in range(3):
    log=(publish/f'baseline-{index}'/'publish.log').read_text()
    tasks=log.split('Task Performance Summary:')[-1]
    timing={name.strip():float(ms) for ms,name in re.findall(r'([0-9.]+) ms\s+(.+?)\s+\d+ calls',tasks)}
    text.append('| baseline-'+str(index)+' | '+' | '.join(f'{timing.get(name,0):.0f}' for name in ['RestoreTask','Csc','ILLink','BrotliCompress','GZipCompress','ConvertDllsToWebCil'])+' |')
text+=['','Host preparation happens once per image. Program-only builds skip MSBuild, restore, runtime publishing, trimming, '
       'WebCIL conversion and compression. Queue/build durations below are monotonic server timings; cached samples '
       'report the original build duration, not another compilation.','',
       '## Run-to-output samples','',
       '| Path | Sample | Total s | Ready API s | Queue ms | Build ms | Frame s | Worker initialization s | Running → output s | Asset request span s | Reported HTTP response bytes |',
       '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
for row in rows:
    span=f"{row['assetRequestEnvelopeSeconds']:.3f}" if row['assetRequestEnvelopeSeconds'] is not None else 'not exposed'
    text.append(f"| {row['kind']} | {row['label']} | {row['seconds']:.3f} | {row['readySeconds']:.3f} | {row['queueMs']:.2f} | {row['compileMs']:.2f} | {row['frameSeconds']:.3f} | {row['workerSeconds']:.3f} | {row['executionSeconds']:.3f} | {span} | {row['assetReportedResponseBytes'] if row['assetReportedResponseBytes'] is not None else 'not exposed'} |")
text+=['','The ready API interval includes the existing one-second polling delay. Frame and worker intervals '
       'follow readiness; worker initialization includes program download/loading and runtime startup. Asset downloads '
       'overlap initialization, so they are not added as another elapsed phase. Running → output includes buffered program '
       'execution and result delivery. Response-size counters are not physical wire-byte measurements; use network-panel '
       'cache flags and transfer sizes for public-WAN bandwidth claims. Raw resource events, API events and timing data '
       'remain alongside this report.','',
       '## Download and compatibility tradeoff','',
       'The prepared host retains library code for future programs by disabling managed trimming. Its first download can '
       'be larger than the source-specific trimmed application. Versioned runtime URLs permit reuse across different '
       'programs; runtime state itself is never reused. The original standalone publisher remains available.','',
       'Full browser semantic tests exercise both paths, including collections, generics, records, exceptions, conversions '
       'and the standard library. Playground tests cover source diagnostics, cached static-state isolation, execution-origin '
       'asset isolation, Stop during download/execution, output limits and recovery. The container check uses a non-root, '
       'read-only container with outbound networking disabled.','']
(folder/'comparison.md').write_text('\n'.join(text),encoding='utf-8')
(folder/'summary.json').write_text(json.dumps({'helloColdMedianBeforeSeconds':before,'helloColdMedianAfterSeconds':after,'rows':rows},indent=2),encoding='utf-8')
print('\n'.join(text[:5]))
