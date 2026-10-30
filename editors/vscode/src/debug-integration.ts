// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'node:path';
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
            'Bhash uses the Microsoft C/C++ extension’s GDB debugger. Install it on this Linux/WSL extension host, then retry.', 'Open debugger in Extensions');
        if (choice) await vscode.commands.executeCommand('workbench.extensions.action.showExtensionsWithIds', ['ms-vscode.cpptools']);
        return false;
    };
    const provider: vscode.DebugConfigurationProvider = {
        async resolveDebugConfigurationWithSubstitutedVariables(folder, configuration, token) {
            if (configuration.bhashNative !== true) return configuration; // Leave C/C++ sessions alone.
            let directory: string | undefined;
            try {
                if (!vscode.workspace.isTrusted) throw Error('Trust the workspace before building or debugging Bhash programs.');
                requireDebugHost();
                if (folder && folder.uri.scheme !== 'file') throw Error('Native debugging requires a folder on disk in Linux/WSL.');
                if (!await hasDebugger()) return undefined;
                if (configuration.request !== 'launch' || configuration.MIMode && configuration.MIMode !== 'gdb')
                    throw Error('Bhash native debugging currently supports GDB launch sessions only.');
                let input = configuration.bhashInput;
                if (input === undefined) input = (await services.activeInput()).input;
                if (typeof input !== 'string' || !input.trim()) throw Error('bhashInput must name a .bhash file, project folder, or bhash.project.');
                const cwd = configuration.cwd || folder?.uri.fsPath || path.dirname(input);
                if (typeof cwd !== 'string' || !path.isAbsolute(cwd)) throw Error('Debug cwd must be an absolute Linux/WSL directory.');
                input = path.resolve(cwd, input);
                await services.save(input);
                const config = vscode.workspace.getConfiguration('bhash', folder?.uri);
                const gdb = config.get<string>('native.gdbPath', 'gdb');
                const abort = new AbortController();
                const cancellation = token?.onCancellationRequested(() => abort.abort());
                if (token?.isCancellationRequested) abort.abort();
                let result;
                try {
                    result = await vscode.window.withProgress({location: vscode.ProgressLocation.Notification,
                        title: 'Bhash: prepare native debugging', cancellable: true}, async (_, progressToken) => {
                        const listener = progressToken.onCancellationRequested(() => abort.abort());
                        try {
                            const options: DebugOptions = {...services.tools(folder), gdb, input, cwd, extensionRoot: context.extensionPath};
                            return await prepareNativeDebug(options, services.log, abort.signal);
                        } finally {listener.dispose();}
                    });
                } finally {cancellation?.dispose();}
                directory = result.directory; builds.add(directory);
                return {...configuration, type: 'cppdbg', request: 'launch', MIMode: 'gdb',
                    program: result.program, cwd, miDebuggerPath: gdb,
                    // Load only our explicit printer, with user/system init and ELF auto-load disabled.
                    miDebuggerArgs: '--nx --nh -iex "set auto-load off"',
                    setupCommands: [...result.setupCommands, ...(configuration.setupCommands || [])],
                    externalConsole: false, stopAtEntry: configuration.stopAtEntry ?? false,
                    bhashBuildDirectory: directory};
            } catch (error) {
                clean(directory); void vscode.window.showErrorMessage('Bhash native debug: ' + String(error)); return undefined;
            }
        }
    };
    context.subscriptions.push(vscode.debug.registerDebugConfigurationProvider('cppdbg', provider));
    context.subscriptions.push(vscode.debug.onDidTerminateDebugSession(session => clean(session.configuration.bhashBuildDirectory)));
    context.subscriptions.push(vscode.debug.registerDebugAdapterTrackerFactory('cppdbg', {
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
            await vscode.debug.startDebugging(folder, {type: 'cppdbg', request: 'launch', name: 'Bhash: Native (Linux/WSL)',
                bhashNative: true, bhashInput: input, cwd: folder?.uri.fsPath || path.dirname(input),
                stopAtEntry: false, args: []});
        } catch (error) {void vscode.window.showErrorMessage('Bhash native debug: ' + String(error));}
    }));
    context.subscriptions.push({dispose: () => {for (const directory of [...builds]) clean(directory);}});
}
