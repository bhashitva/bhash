// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {ensureDebugger, DebuggerOptions, debuggerStartupTimeout} from './managed-debugger';
export {ensureDebugger, windowsDebugger} from './managed-debugger';
import {NativeOptions, Runner, capture, compilerInvocation, ensureNative, nativeEnvironment} from './native';

export interface DebugOptions extends DebuggerOptions {gdb: string; input: string; cwd: string; extensionRoot: string;}
export function requireDebugHost(platform = process.platform, arch = process.arch): void {
    if (!(['linux','win32'].includes(platform) && arch === 'x64')) throw Error('Bhash native debugging requires Windows x64 or Linux/WSL x86-64. macOS debugger integration is not yet available.');
}
export function gdbSetup(script: string) {
    if (/[\r\n]/.test(script)) throw Error('The debugger script path cannot contain a newline.');
    return [
        {text: 'set charset UTF-8'},
        {text: 'set language c'},
        {text: 'set pagination off'},
        {text: 'set print elements 100'},
        // Watch must not call Bhash functions, which can allocate/collect.
        // Do not globally disable memory writes: GDB needs them for breakpoints.
        {text: 'set may-call-functions off'},
        // GDB's `source` treats surrounding quotes as part of the filename.
        // A Python literal handles spaces, quotes and Devanagari paths instead.
        ...[path.join(path.dirname(script),'bhash_watch.py'), script].map(file=>({text:
            'python exec(compile(open(' + JSON.stringify(file) + ', "rb").read(), ' + JSON.stringify(file) + ', "exec"))'})),
        {text: '-enable-pretty-printing'},
        {text: '-enable-frame-filters'},
        {text: 'skip -rfu ^bh_'},
        {text: 'skip -rfu ^bhash_'},
    ].map(command => ({...command, ignoreFailures: false}));
}
export const gdbArguments = ['--nx', '--nh', '-eiex', 'set python ignore-environment on', '-eiex', 'set python dont-write-bytecode on', '-iex', 'set auto-load off'];
export async function debuggerArguments(command: string): Promise<string[]> {
    const result = [...gdbArguments];
    if (process.platform === 'win32' && path.isAbsolute(command)) {
        const data = path.resolve(path.dirname(command), '..', 'share', 'gdb');
        try {await fs.access(path.join(data, 'python', 'gdb', '__init__.py')); result.unshift('--data-directory=' + data.replaceAll('\\','/'));} catch {}
    }
    return result;
}
type ProbeExecutor = (command: string, args: string[], options: {encoding: 'utf8'; timeout: number; maxBuffer: number; signal?: AbortSignal; windowsHide: boolean}) => Promise<{stdout: string}>;
export async function probeGdb(command: string, signal?: AbortSignal, timeoutSeconds?: number, log: (message:string)=>void = ()=>{},
    execute: ProbeExecutor = (command,args,options)=>promisify(execFile)(command,args,options)): Promise<void> {
    signal?.throwIfAborted();
    const seconds = debuggerStartupTimeout(timeoutSeconds);
    const waiting = setTimeout(()=>log('Waiting for GDB to start. Security software may be reviewing it; you can cancel this launch.'),5000);
    try {
        const python = 'import gdb, sys; from gdb.FrameDecorator import FrameDecorator; print("BHASH_GDB_READY:" + gdb.VERSION + ":" + str(sys.version_info[0]))';
        const probe = "python import base64; exec(base64.b64decode('" + Buffer.from(python,'utf8').toString('base64') + "'))";
        const {stdout} = await execute(command, [...await debuggerArguments(command), '--batch', '-ex', probe],
            {encoding: 'utf8', timeout: seconds * 1000, maxBuffer: 65536, signal, windowsHide: true});
        const match = /BHASH_GDB_READY:(\d+)[^\r\n]*:3(?:\r?\n|$)/.exec(stdout);
        if (!match || Number(match[1]) < 12) throw Error('GDB 12+ with Python 3 support is required.');
    } catch (error) {
        if (signal?.aborted) throw Error('Native debugging cancelled.');
        if ((error as {killed?:boolean;code?:unknown})?.killed && typeof (error as {code?:unknown}).code !== 'string') throw Error(`GDB did not become ready within ${seconds} seconds. Security software may still be reviewing it. Let that review finish, then retry, or increase bhash.native.debuggerStartupTimeoutSeconds. ${String(error)}`);
        throw Error('Install GDB 12+ with Python 3 support on the extension host (Ubuntu: sudo apt install gdb), or set bhash.native.gdbPath. Windows automatic debugger setup may need repair. ' + String(error));
    } finally {clearTimeout(waiting);}
}
export async function removeDebugBuild(directory: string): Promise<void> {
    // Delete only directories created by this module, never a user launch path.
    const resolved = path.resolve(directory);
    if (path.dirname(resolved) !== path.resolve(os.tmpdir()) || !path.basename(resolved).startsWith('bhash-debug-'))
        throw Error('Refusing to remove a directory outside the Bhash debug temporary area.');
    await fs.rm(resolved, {recursive: true, force: true, maxRetries: 10, retryDelay: 100});
}
export async function prepareNativeDebug(options: DebugOptions, log: (text: string) => void, signal?: AbortSignal,
    dependencies: {probe: typeof probeGdb; ensure: typeof ensureNative; run: Runner; debugger?: typeof ensureDebugger} = {probe: probeGdb, ensure: ensureNative, run: capture, debugger: ensureDebugger}): Promise<{program: string; directory: string; gdb: string; debuggerArgs: string[]; setupCommands: ReturnType<typeof gdbSetup>}> {
    requireDebugHost();
    if (!path.isAbsolute(options.input) || !path.isAbsolute(options.cwd)) throw Error('Debug input and working directory must be absolute paths on the extension host.');
    const gdb = await (dependencies.debugger || ensureDebugger)(options, options.gdb, log, signal);
    await dependencies.probe(gdb, signal, options.debuggerStartupTimeoutSeconds, log);
    signal?.throwIfAborted();
    await dependencies.ensure(options, log, signal);
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'bhash-debug-'));
    try {
        const program = path.join(directory, process.platform === 'win32' ? 'program.exe' : 'program');
        const invocation = compilerInvocation(options.jar, ['compile', options.input, '--target', 'native', '--debug', '-o', program]);
        log('Building Bhash native executable with source debug information…');
        await dependencies.run(options.java, invocation.args, nativeEnvironment(options), signal, invocation.cwd || options.cwd);
        signal?.throwIfAborted();
        await fs.access(program, fs.constants.X_OK);
        return {program, directory, gdb, debuggerArgs: await debuggerArguments(gdb), setupCommands: gdbSetup(path.join(options.extensionRoot, 'debug', 'bhash_gdb.py'))};
    } catch (error) {await removeDebugBuild(directory); throw error;}
}
