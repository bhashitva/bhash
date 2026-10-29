// SPDX-License-Identifier: Apache-2.0
import {spawn} from 'node:child_process';
import * as path from 'node:path';

// Trusted catalog bundled with the extension; never taken from workspace content.
export const sdk = Object.freeze({
    id: 'bhash-linux-x86_64-llvm18-v1',
    url: 'https://github.com/bhashitva/bhash/releases/download/native-sdk-1.0.0/bhash-linux-x86_64-llvm18-v1.zip',
    sha256: '34ffca6772eb7747bbfcd85fc1675e6104daf26fc3ce39178202cd1cf39cc922'
});
export interface NativeOptions {java: string; jar: string; mode: 'managed' | 'system'; home?: string;}
export type Runner = (command: string, args: string[], env: NodeJS.ProcessEnv, signal?: AbortSignal) => Promise<string>;
export function requireNativeHost(platform = process.platform, arch = process.arch): void {
    if (platform !== 'linux' || arch !== 'x64') throw new Error('Native requires a Linux x86-64 extension host. On Windows, open this folder in WSL (Ubuntu 24.04+) and install Bhash in WSL. macOS, Windows and ARM native hosts are not supported yet.');
}
export function nativeEnvironment(options: NativeOptions, base = process.env): NodeJS.ProcessEnv {
    if (!['managed', 'system'].includes(options.mode)) throw new Error('bhash.native.toolchainMode must be managed or system.');
    if (options.home && !path.isAbsolute(options.home)) throw new Error('bhash.native.toolchainHome must be an absolute path on the Linux extension host.');
    const env: NodeJS.ProcessEnv = {...base, BHASH_NATIVE_TOOLCHAIN: options.mode};
    if (options.home) env.BHASH_TOOLCHAIN_HOME = options.home;
    if (options.mode === 'managed') {
        delete env.BHASH_CLANG; delete env.BHASH_LLVM_OPT; delete env.BHASH_READELF;
    }
    return env;
}
export const capture: Runner = (command, args, env, signal) => new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const child = spawn(command, args, {env, stdio: ['ignore', 'pipe', 'pipe'], detached: process.platform === 'linux'});
    let stdout = '', stderr = '', overflow = false, timedOut = false;
    let escalation: ReturnType<typeof setTimeout> | undefined;
    const terminate = () => {
        const kill = (signal: NodeJS.Signals) => {
            try {if (process.platform === 'linux' && child.pid) process.kill(-child.pid, signal); else child.kill(signal);} catch {}
        };
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
    signal?: AbortSignal, repair = false, run: Runner = capture): Promise<void> {
    const env = nativeEnvironment(options);
    const compiler = (args: string[]) => run(options.java, ['-Dfile.encoding=UTF-8', '-jar', options.jar, ...args], env, signal);
    if (options.mode === 'system') {await compiler(['native', 'doctor', '--json']); return;}
    const libc = (await run('getconf', ['GNU_LIBC_VERSION'], env, signal)).trim();
    const match = /^glibc (\d+)\.(\d+)$/.exec(libc);
    if (!match || Number(match[1]) < 2 || Number(match[1]) === 2 && Number(match[2]) < 39)
        throw new Error('The managed native SDK requires glibc 2.39+ (Ubuntu 24.04+). Upgrade the Linux/WSL distribution, or select bhash.native.toolchainMode = system and install a compatible LLVM 18 toolchain.');
    const status = async (): Promise<Status> => {
        const result = JSON.parse(await compiler(['native', 'toolchain', 'status', '--json']));
        if (typeof result.installed !== 'boolean') throw new Error('Unsupported compiler toolchain status. Update bhash.compilerPath or use the bundled compiler.');
        return result;
    };
    // Repair must also work when the old installation receipt or manifest is corrupt.
    let current = repair ? {installed: false} : await status();
    if (!repair && current.installed && current.archiveSha256 === sdk.sha256) return;
    log(`Downloading Bhash native SDK (115 MiB) from GitHub Releases. SHA-256: ${sdk.sha256}`);
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
