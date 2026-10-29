// SPDX-License-Identifier: Apache-2.0
// A normal ProcessExecution launcher keeps Bhash console input connected to the terminal.
import {spawn} from 'node:child_process';
import {capture, ensureNative, nativeEnvironment, NativeOptions, requireNativeHost} from './native';
interface Request extends NativeOptions {operation: 'build' | 'run' | 'setup' | 'check' | 'repair'; input?: string;}
async function main() {
    const request: Request = JSON.parse(process.argv[2]);
    if (!['build','run','setup','check','repair'].includes(request.operation)) throw new Error('Invalid native task operation.');
    requireNativeHost();
    const abort = new AbortController();
    process.once('SIGTERM', () => abort.abort());
    process.once('SIGINT', () => abort.abort());
    if (request.operation !== 'check') await ensureNative(request, console.log, abort.signal, request.operation === 'repair');
    const env = nativeEnvironment(request);
    if (['setup','check','repair'].includes(request.operation)) {
        console.log(await capture(request.java, ['-Dfile.encoding=UTF-8','-jar',request.jar,'native','doctor','--json'], env, abort.signal));
        return;
    }
    if (!request.input) throw new Error('Native task input is missing.');
    abort.signal.throwIfAborted();
    const child = spawn(request.java, ['-Dfile.encoding=UTF-8','-jar',request.jar,request.operation === 'build' ? 'compile' : 'run',request.input,'--target','native'], {env, stdio:'inherit'});
    const stop = () => child.kill('SIGTERM');
    abort.signal.addEventListener('abort', stop, {once:true});
    await new Promise<void>((resolve, reject) => {
        child.once('error', reject);
        child.once('close', code => {abort.signal.removeEventListener('abort', stop); process.exitCode = code ?? 1; resolve();});
    });
}
main().catch(error => {console.error(`Bhash native: ${error instanceof Error ? error.message : String(error)}`);process.exitCode=1;});
