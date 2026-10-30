// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {NativeOptions, Runner, capture, compilerInvocation, ensureNative, nativeEnvironment} from './native';

export interface DebugOptions extends NativeOptions {gdb: string; input: string; cwd: string; extensionRoot: string;}
export function requireDebugHost(platform = process.platform, arch = process.arch): void {
    if (platform !== 'linux' || arch !== 'x64') throw Error('Bhash native debugging currently requires Linux x86-64 or VS Code connected to WSL. Open the folder in WSL to debug; ordinary Windows/macOS native build and run remain available.');
}
export function gdbSetup(script: string) {
    if (/[\r\n]/.test(script)) throw Error('The debugger script path cannot contain a newline.');
    return [
        {text: 'set charset UTF-8'},
        {text: 'set pagination off'},
        {text: 'set print elements 100'},
        // Watch must not call Bhash functions, which can allocate/collect.
        // Do not globally disable memory writes: GDB needs them for breakpoints.
        {text: 'set may-call-functions off'},
        // GDB's `source` treats surrounding quotes as part of the filename.
        // A Python literal handles spaces, quotes and Devanagari paths instead.
        {text: 'python exec(compile(open(' + JSON.stringify(script) + ', "rb").read(), ' + JSON.stringify(script) + ', "exec"))'},
        {text: '-enable-pretty-printing'},
        {text: '-enable-frame-filters'},
        {text: 'skip -rfu ^bh_'},
        {text: 'skip -rfu ^bhash_'},
    ].map(command => ({...command, ignoreFailures: false}));
}
export async function probeGdb(command: string, signal?: AbortSignal): Promise<void> {
    try {
        const {stdout} = await promisify(execFile)(command, ['--nx', '--nh', '--batch',
            '-iex', 'set auto-load off', '-ex', 'python import gdb, sys; print("BHASH_GDB_READY:" + gdb.VERSION + ":" + str(sys.version_info[0]))'],
            {encoding: 'utf8', timeout: 15000, maxBuffer: 65536, signal});
        const match = /BHASH_GDB_READY:(\d+)[^\r\n]*:3(?:\r?\n|$)/.exec(stdout);
        if (!match || Number(match[1]) < 12) throw Error('GDB 12+ with Python 3 support is required.');
    } catch (error) {
        if (signal?.aborted) throw Error('Native debugging cancelled.');
        throw Error('Install GDB 12+ with Python 3 support on the Linux/WSL extension host (Ubuntu: sudo apt install gdb), or set bhash.native.gdbPath. ' + String(error));
    }
}
export async function removeDebugBuild(directory: string): Promise<void> {
    // Delete only directories created by this module, never a user launch path.
    const resolved = path.resolve(directory);
    if (path.dirname(resolved) !== path.resolve(os.tmpdir()) || !path.basename(resolved).startsWith('bhash-debug-'))
        throw Error('Refusing to remove a directory outside the Bhash debug temporary area.');
    await fs.rm(resolved, {recursive: true, force: true});
}
export async function prepareNativeDebug(options: DebugOptions, log: (text: string) => void, signal?: AbortSignal,
    dependencies = {probe: probeGdb, ensure: ensureNative, run: capture as Runner}): Promise<{program: string; directory: string; setupCommands: ReturnType<typeof gdbSetup>}> {
    requireDebugHost();
    if (!path.isAbsolute(options.input) || !path.isAbsolute(options.cwd)) throw Error('Debug input and working directory must be absolute paths on Linux/WSL.');
    await dependencies.probe(options.gdb, signal);
    signal?.throwIfAborted();
    await dependencies.ensure(options, log, signal);
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'bhash-debug-'));
    try {
        const program = path.join(directory, 'program');
        const invocation = compilerInvocation(options.jar, ['compile', options.input, '--target', 'native', '--debug', '-o', program]);
        log('Building Bhash native executable with source debug information…');
        await dependencies.run(options.java, invocation.args, nativeEnvironment(options), signal, options.cwd);
        signal?.throwIfAborted();
        await fs.access(program, fs.constants.X_OK);
        return {program, directory, setupCommands: gdbSetup(path.join(options.extensionRoot, 'debug', 'bhash_gdb.py'))};
    } catch (error) {await removeDebugBuild(directory); throw error;}
}
