// SPDX-License-Identifier: Apache-2.0
// A normal ProcessExecution launcher keeps Bhash console input connected to the terminal.
import {spawn} from 'node:child_process';
import * as path from 'node:path';
import {capture, compilerInvocation, ensureNative, nativeEnvironment, NativeOptions, requireNativeHost, terminateTree} from './native';
interface Request extends NativeOptions {operation: 'build' | 'run' | 'setup' | 'check' | 'repair'; input?: string; target?: 'native' | 'jvm' | 'clr' | 'wasm-browser';}
async function main() {
    const request: Request = JSON.parse(process.argv[2] === '--request-env' ? process.env.BHASH_TASK_REQUEST_JSON! : process.argv[2] === '--request-base64'
        ? Buffer.from(process.argv[3], 'base64').toString('utf8') : process.argv[2]);
    if (!['build','run','setup','check','repair'].includes(request.operation)) throw new Error('Invalid task operation.');
    const target = request.target || 'native';
    if (!['native','jvm','clr','wasm-browser'].includes(target)) throw new Error('Invalid target.');
    if (target === 'native') requireNativeHost();
    const abort = new AbortController();
    process.once('SIGTERM', () => abort.abort());
    process.once('SIGINT', () => abort.abort());
    if (target === 'native' && request.operation !== 'check') await ensureNative(request, console.log, abort.signal, request.operation === 'repair');
    const env = target === 'native' ? nativeEnvironment(request) : {...process.env};
    if (['setup','check','repair'].includes(request.operation)) {
        const invocation=compilerInvocation(request.jar,['native','doctor','--json']);
        console.log(await capture(request.java,invocation.args,env,abort.signal,invocation.cwd));
        return;
    }
    if (!request.input) throw new Error('Task input is missing.');
    abort.signal.throwIfAborted();
    const invocation=compilerInvocation(request.jar,[request.operation === 'build' ? 'compile' : 'run',path.resolve(request.input),'--target',target]);
    const child = spawn(request.java, invocation.args, {env, cwd:invocation.cwd, windowsHide:true, stdio:'inherit', detached:process.platform === 'linux'});
    const stop = () => terminateTree(child);
    abort.signal.addEventListener('abort', stop, {once:true});
    await new Promise<void>((resolve, reject) => {
        child.once('error', reject);
        child.once('close', code => {abort.signal.removeEventListener('abort', stop); process.exitCode = code ?? 1; resolve();});
    });
}
main().catch(error => {console.error(`Bhash task: ${error instanceof Error ? error.message : String(error)}`);process.exitCode=1;});
