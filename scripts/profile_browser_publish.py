# SPDX-License-Identifier: Apache-2.0
"""Same-host baseline publishing phases, one-time host preparation and edited-program builds."""
import hashlib, json, os, pathlib, platform, subprocess, time
root = pathlib.Path(__file__).resolve().parents[1]
out = root / 'target/browser-publish-profile'
out.mkdir(parents=True, exist_ok=True)
jar = root / 'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
host = out / 'host'
env = dict(os.environ)
env.pop('BHASH_BROWSER_HOST', None)
env.pop('BHASH_BROWSER_SHARED', None)
results = {'platform': platform.platform(), 'logicalCPUs':os.cpu_count(),
           'sdk':subprocess.check_output(['dotnet','--version'],text=True).strip(),
           'compilerSHA256':hashlib.sha256(jar.read_bytes()).hexdigest(), 'runs': []}
def run(label, command, extra):
    logs = out / label
    logs.mkdir(exist_ok=True)
    start = time.perf_counter()
    result = subprocess.run(command, cwd=root, env={**env, **extra, 'BHASH_WASM_PROFILE': str(logs)}, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    elapsed = time.perf_counter()-start
    (logs/'command.log').write_bytes(result.stdout)
    results['runs'].append({'label':label, 'seconds':elapsed, 'exitCode':result.returncode})
    (out/'results.json').write_text(json.dumps(results, indent=2))
    if result.returncode:
        raise RuntimeError(f'{label} failed: {result.stdout.decode(errors="replace")[-6000:]}')
source = out/'hello.bhash'
for i in range(3):
    source.write_text(f'कार्य मुख्य() -> शून्य {{ लिखो("नमस्ते {i}"); }}', encoding='utf-8')
    run(f'baseline-{i}', ['java','-jar',str(jar),'compile',str(source),'--target','wasm-browser','-o',str(out/f'baseline-web-{i}')], {})
run('prepare', ['java','-cp',str(jar),'dev.bhash.backend.wasm.PrebuiltBrowserHost',str(host)], {})
for i in range(3):
    source.write_text(f'कार्य मुख्य() -> शून्य {{ लिखो("नमस्ते {i}"); }}', encoding='utf-8')
    run(f'prebuilt-{i}', ['java','-jar',str(jar),'compile',str(source),'--target','wasm-browser','-o',str(out/f'prebuilt-web-{i}')], {'BHASH_BROWSER_HOST':str(host),'BHASH_BROWSER_SHARED':'true'})
print(json.dumps(results,indent=2))
