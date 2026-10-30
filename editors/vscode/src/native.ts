// SPDX-License-Identifier: Apache-2.0
import {spawn, ChildProcess} from 'node:child_process';
import * as path from 'node:path';

// Trusted catalog bundled with the extension; never taken from workspace content.
export const linuxSdk = Object.freeze({
    id: 'bhash-linux-x86_64-llvm18-v1',
    url: 'https://github.com/bhashitva/bhash/releases/download/native-sdk-1.1.0/bhash-linux-x86_64-llvm18-v1-jammy.zip',
    sha256: '52e8a1c7bd1b71082c0c9d7364776a53f5d7a3b8b87d6501f1cf0894ed09e844'
});
export const windowsSdk = Object.freeze({
    id: 'bhash-windows-x86_64-llvm18-v1',
    url: 'https://github.com/bhashitva/bhash/releases/download/native-sdk-windows-1.0.0/bhash-windows-x86_64-llvm18-v1.zip',
    sha256: 'b0d5934ea134e500ff598f737c40b3076400e85e5760a22bfcc28f7aaf321f3f'
});
export function sdkForHost(platform = process.platform) {return platform === 'win32' ? windowsSdk : linuxSdk;}
export const sdk = sdkForHost();
export interface NativeOptions {java: string; jar: string; mode: 'managed' | 'system'; home?: string;}
export type Runner = (command: string, args: string[], env: NodeJS.ProcessEnv, signal?: AbortSignal, cwd?: string) => Promise<string>;
export function compilerInvocation(jar: string, args: string[], platform = process.platform, callerDirectory = process.cwd()): {args:string[]; cwd?:string} {
    if (platform === 'win32') return {args:['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-jar',path.basename(jar),'--utf8-args',Buffer.from(JSON.stringify({args,cwd:callerDirectory}),'utf8').toString('base64')],cwd:path.dirname(path.resolve(jar))};
    return {args:['-Dfile.encoding=UTF-8','-jar',jar,...args]};
}
export function requireNativeHost(platform = process.platform, arch = process.arch): void {
    if (!['linux', 'win32'].includes(platform) || arch !== 'x64') throw new Error('Native requires a Windows x64 or Linux x86-64 extension host (including WSL Ubuntu 22.04+). macOS and ARM hosts are not supported yet.');
}
export function nativeEnvironment(options: NativeOptions, base = process.env): NodeJS.ProcessEnv {
    if (!['managed', 'system'].includes(options.mode)) throw new Error('bhash.native.toolchainMode must be managed or system.');
    if (options.home && !path.isAbsolute(options.home)) throw new Error('bhash.native.toolchainHome must be an absolute path on the extension host.');
    const env: NodeJS.ProcessEnv = {...base, BHASH_NATIVE_TOOLCHAIN: options.mode};
    if (options.home) env.BHASH_TOOLCHAIN_HOME = options.home;
    if (options.mode === 'managed') {
        delete env.BHASH_CLANG; delete env.BHASH_LLVM_OPT; delete env.BHASH_READELF;
    }
    return env;
}
export function terminateTree(child: ChildProcess, signal: NodeJS.Signals = 'SIGTERM'): void {
    try {
        if (process.platform === 'linux' && child.pid) process.kill(-child.pid, signal);
        else if (process.platform === 'win32' && child.pid) {
            const killer = spawn(path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'taskkill.exe'), ['/PID', String(child.pid), '/T', '/F'], {windowsHide:true, stdio:'ignore'});
            killer.once('error',()=>child.kill(signal));
            killer.once('close',code=>{if(code !== 0)child.kill(signal);});
        } else child.kill(signal);
    } catch {}
}
export const capture: Runner = (command, args, env, signal, cwd) => new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const child = spawn(command, args, {env, cwd, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], detached: process.platform === 'linux'});
    let stdout = '', stderr = '', overflow = false, timedOut = false;
    let escalation: ReturnType<typeof setTimeout> | undefined;
    const terminate = () => {
        const kill = (signal: NodeJS.Signals) => terminateTree(child, signal);
        kill('SIGTERM'); escalation ??= setTimeout(() => kill('SIGKILL'), 2000);
    };
    const timer = setTimeout(() => {timedOut = true; terminate();}, 10 * 60 * 1000);
    signal?.addEventListener('abort', terminate, {once: true});
    const append = (text: string, error: boolean) => {
        if (stdout.length + stderr.length + text.length > 65536) {overflow = true; terminate(); return;}
        if (error) stderr += text; else stdout += text;
    };
    child.stdout.setEncoding('utf8').on('data', (text: string) => append(text, false));
    child.stderr.setEncoding('utf8').on('data', (text: string) => append(text, true));
    const cleanup = () => {clearTimeout(timer); if (escalation) clearTimeout(escalation); signal?.removeEventListener('abort', terminate);};
    child.on('error', error => {cleanup(); reject(new Error(`Could not start ${command}: ${error.message}. Install Java 17+ on the extension host or configure bhash.javaPath.`));});
    child.on('close', code => {
        cleanup();
        if (signal?.aborted) {reject(new Error('Native setup cancelled. The previous active SDK is preserved.')); return;}
        if (timedOut || overflow) {reject(new Error(timedOut ? 'Native tool setup timed out.' : 'Native tool output exceeded the safety limit.')); return;}
        if (code !== 0) reject(new Error((stderr || stdout || `Process exited ${code}`).trim())); else resolve(stdout);
    });
    if (signal?.aborted) terminate();
});
interface Status {installed: boolean; archiveSha256?: string;}
export async function ensureNative(options: NativeOptions, log: (message: string) => void,
    signal?: AbortSignal, repair = false, run: Runner = capture, platform: NodeJS.Platform = process.platform): Promise<void> {
    requireNativeHost(platform);
    const sdk = sdkForHost(platform);
    const env = nativeEnvironment(options);
    const compiler = (args: string[]) => {const invocation=compilerInvocation(options.jar,args,platform);return run(options.java,invocation.args,env,signal,invocation.cwd);};
    if (options.mode === 'system') {await compiler(['native', 'doctor', '--json']); return;}
    if (platform === 'linux') {
    const libc = (await run('getconf', ['GNU_LIBC_VERSION'], env, signal)).trim();
    const match = /^glibc (\d+)\.(\d+)$/.exec(libc);
    if (!match || Number(match[1]) < 2 || Number(match[1]) === 2 && Number(match[2]) < 35)
        throw new Error(`Your host reports ${libc}. The managed native SDK requires glibc 2.35+ (Ubuntu 22.04+). Upgrade the Linux/WSL distribution, or select bhash.native.toolchainMode = system and install a compatible LLVM 18 toolchain.`);
    }
    const status = async (): Promise<Status> => {
        const result = JSON.parse(await compiler(['native', 'toolchain', 'status', '--json']));
        if (typeof result.installed !== 'boolean') throw new Error('Unsupported compiler toolchain status. Update bhash.compilerPath or use the bundled compiler.');
        return result;
    };
    // Repair must also work when the old installation receipt or manifest is corrupt.
    let current = repair ? {installed: false} : await status();
    if (!repair && current.installed && current.archiveSha256 === sdk.sha256) return;
    log(`Downloading Bhash native SDK from GitHub Releases. SHA-256: ${sdk.sha256}`);
    log('Setup includes LLVM, the linker, and C runtime headers/libraries. No administrator access is required.');
    const deadline = Date.now() + 6 * 60 * 1000;
    for (;;) {
        signal?.throwIfAborted();
        try {
            await compiler(['native', 'toolchain', 'install', '--url', sdk.url, '--sha256', sdk.sha256, '--json']);
            break;
        } catch (error) {
            if (!String(error).includes('Another toolchain installation is in progress') || Date.now() >= deadline) throw error;
            log('Waiting for another Bhash SDK installation to finish…');
            await new Promise<void>((resolve, reject) => {
                const abort = () => {clearTimeout(timer); signal?.removeEventListener('abort', abort); reject(new Error('Native setup cancelled.'));};
                const timer = setTimeout(() => {signal?.removeEventListener('abort', abort); resolve();}, 1000);
                signal?.addEventListener('abort', abort, {once:true});
                if (signal?.aborted) abort();
            });
            current = await status();
            if (!repair && current.installed && current.archiveSha256 === sdk.sha256) return;
        }
    }
    current = await status();
    if (!current.installed || current.archiveSha256 !== sdk.sha256) throw new Error('Native SDK activation could not be verified.');
    log('Bhash native SDK is ready. Future builds use the cached installation.');
}
