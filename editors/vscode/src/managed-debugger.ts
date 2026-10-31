// SPDX-License-Identifier: Apache-2.0
import {NativeOptions, Runner, capture, compilerInvocation, nativeEnvironment} from './native';
import * as path from 'node:path';
import * as os from 'node:os';
// Release catalog is bundled, never read from workspace configuration.
import catalog from './windows-debugger.json';
export const windowsDebugger = Object.freeze(catalog);
export interface DebuggerOptions extends NativeOptions {debuggerStartupTimeoutSeconds?: number;}
export function debuggerStartupTimeout(seconds = 300): number {
    if (!Number.isInteger(seconds) || seconds < 15 || seconds > 600)
        throw Error('bhash.native.debuggerStartupTimeoutSeconds must be an integer from 15 to 600.');
    return seconds;
}

export async function ensureDebugger(options: DebuggerOptions, configured: string, log: (text:string)=>void,
    signal?: AbortSignal, run: Runner = capture, platform = process.platform): Promise<string> {
    signal?.throwIfAborted();
    if (platform !== 'win32') return configured || 'gdb';
    if (process.platform === 'win32' && Number(os.release().split('.')[2]) < 18362) throw Error('Windows native debugging requires Windows 10 version 1903 or later for Hindi/UTF-8 file paths.');
    if (configured) {
        if (!path.win32.isAbsolute(configured)) throw Error('bhash.native.gdbPath must be an absolute Windows executable path, or empty for automatic setup.');
        return configured;
    }
    const startupSeconds = debuggerStartupTimeout(options.debuggerStartupTimeoutSeconds);
    const invoke = async (args: string[]) => {
        const invocation = compilerInvocation(options.jar, ['native','debugger',...args,'--json'], platform);
        const env = {...nativeEnvironment(options), BHASH_DEBUGGER_STARTUP_TIMEOUT_SECONDS: String(startupSeconds)};
        // Keep the parent alive for the startup wait plus download/extraction time.
        return JSON.parse(await run(options.java, invocation.args, env, signal, invocation.cwd, (startupSeconds + 300) * 1000));
    };
    // Full per-file verification is performed by status; damaged caches are repaired.
    let status: {installed?:boolean; archiveSha256?:string; gdb?:string} | undefined;
    try {status = await invoke(['status']);} catch (error) {if (signal?.aborted) throw error; log('Repairing unavailable debugger cache...');}
    const ready = () => status?.installed === true && status.archiveSha256 === catalog.sha256 && typeof status.gdb === 'string' && path.win32.isAbsolute(status.gdb);
    if (ready()) return status!.gdb!;
    log('Setting up the Bhash Windows debugger (GDB and Python), verified by SHA-256. No administrator access is required.');
    log('First startup may take several minutes while security software reviews GDB. You can cancel.');
    const deadline = Date.now() + (startupSeconds + 300) * 1000;
    for (;;) {
        signal?.throwIfAborted();
        try {status = await invoke(['install','--url',catalog.url,'--sha256',catalog.sha256]); break;}
        catch (error) {
            if (!String(error).includes('Another debugger installation is in progress') || Date.now() > deadline) throw error;
            await new Promise<void>((resolve,reject) => {
                const abort = () => {clearTimeout(timer); signal?.removeEventListener('abort',abort); reject(Error('Debugger setup cancelled.'));};
                const timer = setTimeout(() => {signal?.removeEventListener('abort',abort); resolve();},1000);
                signal?.addEventListener('abort',abort,{once:true}); if(signal?.aborted)abort();
            });
            try {status = await invoke(['status']);} catch (error) {if(signal?.aborted)throw error;}
            if(ready())return status!.gdb!;
        }
    }
    if (!ready()) throw Error('Windows debugger activation could not be verified.');
    return status!.gdb!;
}
