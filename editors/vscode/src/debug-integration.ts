// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'node:path';
import {access} from 'node:fs/promises';
import {WindowsDebugAdapter} from './windows-debug-adapter';

const windowsDebugType = 'bhash-native-windows';
import {DebugOptions, prepareNativeDebug, removeDebugBuild, requireDebugHost} from './native-debug';
import {NativeOptions} from './native';

interface DebugServices {
    activeInput(): Promise<{input: string; folder?: vscode.WorkspaceFolder}>;
    save(input: string): Promise<void>;
    tools(folder?: vscode.WorkspaceFolder): NativeOptions;
    log(text: string): void;
}

export function registerNativeDebug(context: vscode.ExtensionContext, services: DebugServices) {
    const builds = new Set<string>();
    const clean = (directory: unknown) => {
        if (typeof directory !== 'string' || !builds.delete(directory)) return;
        void removeDebugBuild(directory).catch(error => services.log(String(error)));
    };
    const hasDebugger = async () => {
        if (vscode.extensions.getExtension('ms-vscode.cpptools')) return true;
        const choice = await vscode.window.showInformationMessage(
            'Bhash uses the Microsoft C/C++ extension’s GDB debugger. Install it on this extension host, then retry.', 'Open debugger in Extensions');
        if (choice) await vscode.commands.executeCommand('workbench.extensions.action.showExtensionsWithIds', ['ms-vscode.cpptools']);
        return false;
    };
    const provider: vscode.DebugConfigurationProvider = {
        async resolveDebugConfigurationWithSubstitutedVariables(folder, configuration, token) {
            if (configuration.bhashNative !== true && configuration.type !== windowsDebugType) return configuration; // Leave C/C++ sessions alone.
            let directory: string | undefined;
            try {
                if (!vscode.workspace.isTrusted) throw Error('Trust the workspace before building or debugging Bhash programs.');
                requireDebugHost();
                if (configuration.type === windowsDebugType && process.platform !== 'win32') throw Error('This debugger type requires Windows. Use cppdbg with bhashNative: true on Linux/WSL.');
                if (folder && folder.uri.scheme !== 'file') throw Error('Native debugging requires a folder on disk on the extension host.');
                if (!await hasDebugger()) return undefined;
                if (configuration.request !== 'launch' || configuration.MIMode && configuration.MIMode !== 'gdb')
                    throw Error('Bhash native debugging currently supports GDB launch sessions only.');
                let input = configuration.bhashInput;
                if (input === undefined) input = (await services.activeInput()).input;
                if (typeof input !== 'string' || !input.trim()) throw Error('bhashInput must name a .bhash file, project folder, or bhash.project.');
                const cwd = configuration.cwd || folder?.uri.fsPath || path.dirname(input);
                if (typeof cwd !== 'string' || !path.isAbsolute(cwd)) throw Error('Debug cwd must be an absolute directory on the extension host.');
                input = path.resolve(cwd, input);
                await services.save(input);
                const config = vscode.workspace.getConfiguration('bhash', folder?.uri);
                const gdb = config.get<string>('native.gdbPath', '');
                const abort = new AbortController();
                const cancellation = token?.onCancellationRequested(() => abort.abort());
                if (token?.isCancellationRequested) abort.abort();
                let result;
                try {
                    result = await vscode.window.withProgress({location: vscode.ProgressLocation.Notification,
                        title: 'Bhash: prepare native debugging', cancellable: true}, async (progress, progressToken) => {
                        const listener = progressToken.onCancellationRequested(() => abort.abort());
                        try {
                            const options: DebugOptions = {...services.tools(folder), gdb, input, cwd, extensionRoot: context.extensionPath,
                                debuggerStartupTimeoutSeconds: config.get<number>('native.debuggerStartupTimeoutSeconds',300)};
                            return await prepareNativeDebug(options, text=>{services.log(text);progress.report({message:text});}, abort.signal);
                        } finally {listener.dispose();}
                    });
                } finally {cancellation?.dispose();}
                directory = result.directory; builds.add(directory);
                abort.signal.throwIfAborted();
                return {...configuration, type: process.platform === 'win32' ? windowsDebugType : 'cppdbg', bhashNative: true, request: 'launch', MIMode: 'gdb',
                    program: result.program, cwd, miDebuggerPath: result.gdb,
                    // Load only our explicit printer, with user/system init and ELF auto-load disabled.
                    miDebuggerArgs: result.debuggerArgs.map(argument => JSON.stringify(argument)).join(' '),
                    setupCommands: [...result.setupCommands, ...(configuration.setupCommands || [])],
                    externalConsole: false, stopAtEntry: configuration.stopAtEntry ?? false,
                    bhashBuildDirectory: directory};
            } catch (error) {
                clean(directory); void vscode.window.showErrorMessage('Bhash native debug: ' + String(error)); return undefined;
            }
        }
    };
    context.subscriptions.push(vscode.debug.registerDebugConfigurationProvider('cppdbg', provider));
    context.subscriptions.push(vscode.debug.registerDebugConfigurationProvider(windowsDebugType, provider));
    context.subscriptions.push(vscode.debug.registerDebugAdapterDescriptorFactory(windowsDebugType, {
        async createDebugAdapterDescriptor(session) {
            try {
            requireDebugHost();
            if (process.platform !== 'win32') throw Error('The Windows Bhash adapter requires a Windows extension host.');
            const cpp = vscode.extensions.getExtension('ms-vscode.cpptools');
            if (!cpp) throw Error('Install Microsoft C/C++ on this extension host.');
            await cpp.activate();
            const adapter = path.join(cpp.extensionPath,'debugAdapters','bin','OpenDebugAD7.exe');
            await access(adapter); await access(path.join(path.dirname(adapter),'WindowsDebugLauncher.exe'));
            return new vscode.DebugAdapterInlineImplementation(new WindowsDebugAdapter(adapter, {
                cwd: session.configuration.cwd,
                terminal: options=>vscode.window.createTerminal(options),
                closed: listener=>vscode.window.onDidCloseTerminal(listener),
                log: text=>services.log(text)
            }));
            } catch(error) {clean(session.configuration.bhashBuildDirectory);throw error;}
        }
    }));
    context.subscriptions.push(vscode.debug.onDidTerminateDebugSession(session => clean(session.configuration.bhashBuildDirectory)));
    context.subscriptions.push(vscode.debug.registerDebugAdapterTrackerFactory('*', {
        createDebugAdapterTracker: session => session.configuration.bhashNative === true ? {
            onError: () => clean(session.configuration.bhashBuildDirectory),
            onExit: () => clean(session.configuration.bhashBuildDirectory)
        } : undefined
    }));
    context.subscriptions.push(vscode.commands.registerCommand('bhash.debugNative', async () => {
        try {
            if (!vscode.workspace.isTrusted) throw Error('Trust the workspace before debugging Bhash programs.');
            requireDebugHost();
            if (!await hasDebugger()) return;
            const {input, folder} = await services.activeInput();
            await vscode.debug.startDebugging(folder, {type: process.platform === 'win32' ? windowsDebugType : 'cppdbg', request: 'launch', name: 'Bhash: Native',
                bhashNative: true, bhashInput: input, cwd: folder?.uri.fsPath || path.dirname(input),
                stopAtEntry: false, args: []});
        } catch (error) {void vscode.window.showErrorMessage('Bhash native debug: ' + String(error));}
    }));
    context.subscriptions.push({dispose: () => {for (const directory of [...builds]) clean(directory);}});
}
