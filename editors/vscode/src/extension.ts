// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'node:path';
import * as fs from 'node:fs/promises';
import {NativeOptions, requireNativeHost} from './native';
import {TaskConsole} from './task-console';
import {registerNativeDebug} from './debug-integration';
import {LanguageClient, LanguageClientOptions, ServerOptions} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let context: vscode.ExtensionContext;
let output: vscode.OutputChannel;
let serverWatcher: vscode.FileSystemWatcher | undefined;
const schemes = [{scheme: 'file', language: 'bhash'}, {scheme: 'file', language: 'bhash-project'}];
const targets = ['jvm', 'clr', 'wasm-browser', 'native'] as const;
type Target = typeof targets[number];
interface NativeToolsTask extends vscode.TaskDefinition {type: 'bhash-native-tools'; operation: 'setup' | 'check' | 'repair';}
interface BhashTask extends vscode.TaskDefinition {type: 'bhash'; command: 'build' | 'run'; project?: string; target?: Target;}

function trusted(): boolean {
    if (vscode.workspace.isTrusted) return true;
    void vscode.window.showWarningMessage('Trust this workspace to start Bhash analysis or execute compiler tasks. Highlighting and snippets remain available.');
    return false;
}
function tools() {
    const config = vscode.workspace.getConfiguration('bhash');
    const java = config.get<string>('javaPath', 'java');
    const configured = config.get<string>('compilerPath', '');
    if (configured && !path.isAbsolute(configured)) throw new Error('bhash.compilerPath must be an absolute JAR path.');
    return {java, jar: configured || context.asAbsolutePath('server/bhash-compiler.jar')};
}
async function startServer() {
    if (!vscode.workspace.isTrusted || client || vscode.workspace.workspaceFolders?.some(f => f.uri.scheme !== 'file')) return;
    let java: string, jar: string;
    try {({java,jar} = tools());} catch (error) {output.appendLine(String(error));void vscode.window.showErrorMessage(String(error));return;}
    // Windows Java 17 can lose Unicode in the JAR argument; cwd uses the wide process API.
    const windows = process.platform === 'win32';
    const server: ServerOptions = {command: java, args: ['-Dfile.encoding=UTF-8', '-Xmx768m', '-jar', windows ? path.basename(jar) : jar, 'language-server'],
        options: windows ? {cwd: path.dirname(jar)} : undefined};
    serverWatcher?.dispose();
    const watcher = vscode.workspace.createFileSystemWatcher('**/*.{bhash,project,lock}');serverWatcher=watcher;
    const options: LanguageClientOptions = {documentSelector: schemes, outputChannel: output,
        synchronize: {fileEvents: watcher}, markdown: {isTrusted: false}};
    const next = new LanguageClient('bhash', 'Bhash', server, options);
    client = next;
    context.subscriptions.push(watcher);
    try {await next.start();}
    catch (error) {
        if (client === next) client = undefined;
        output.appendLine(String(error));
        void vscode.window.showErrorMessage('Bhash analysis could not start. Install Java 17+ on the extension host, or set bhash.javaPath. See Output → Bhash.', 'Show Output').then(action => {if (action) output.show();});
    }
}
async function restart() {if (client) {const previous = client; client = undefined; await previous.stop();} await startServer();}
async function exists(uri: vscode.Uri): Promise<boolean> {try {await vscode.workspace.fs.stat(uri); return true;} catch {return false;}}
async function activeInput(): Promise<{input: string; folder?: vscode.WorkspaceFolder}> {
    const document = vscode.window.activeTextEditor?.document;
    if (!document || document.uri.scheme !== 'file' || !['bhash', 'bhash-project'].includes(document.languageId)) throw new Error('Open a .bhash file or bhash.project first.');
    const folder = vscode.workspace.getWorkspaceFolder(document.uri);
    if (client) {const input = await client.sendRequest<string>('bhash/projectInput', {uri: document.uri.toString()});return {input, folder};}
    const boundary = folder?.uri.fsPath || path.dirname(document.uri.fsPath);
    if (path.basename(document.uri.fsPath) === 'bhash.project') return {input: document.uri.fsPath, folder};
    for (let directory = path.dirname(document.uri.fsPath); directory === boundary || directory.startsWith(boundary + path.sep); directory = path.dirname(directory)) {
        const manifest = vscode.Uri.file(path.join(directory, 'bhash.project'));
        if (await exists(manifest)) return {input: manifest.fsPath, folder};
        if (directory === path.dirname(directory)) break;
    }
    return {input: document.uri.fsPath, folder};
}
function taskFor(definition: BhashTask, folder: vscode.WorkspaceFolder | undefined, input: string): vscode.Task {
    if (!['build','run'].includes(definition.command)) throw new Error('Bhash task command must be build or run.');
    const target = definition.target || vscode.workspace.getConfiguration('bhash', folder?.uri).get<Target>('target', 'jvm');
    if (!targets.includes(target) || definition.command === 'run' && target === 'wasm-browser') throw new Error('Use Build for wasm-browser, then serve the output directory over HTTP. Run supports JVM, CLR and Native.');
    const {java,jar} = tools();
    const execution = target === 'native' || process.platform === 'win32' ? compilerExecution(definition.command, input, folder, target) : new vscode.ProcessExecution(java, ['-Dfile.encoding=UTF-8', '-jar', jar, definition.command === 'build' ? 'compile' : 'run', input, '--target', target], {cwd: folder?.uri.fsPath || path.dirname(input)});
    const task = new vscode.Task(definition, folder || vscode.TaskScope.Global, `${definition.command} ${path.basename(input)} (${target})`, 'Bhash', execution, ['$bhash']);
    task.group = definition.command === 'build' ? vscode.TaskGroup.Build : undefined;
    task.presentationOptions = {echo: false, reveal: vscode.TaskRevealKind.Always, focus: definition.command === 'run', panel: vscode.TaskPanelKind.Dedicated, clear: true};
    return task;
}
function compilerExecution(operation: 'build' | 'run' | 'setup' | 'check' | 'repair', input?: string, folder?: vscode.WorkspaceFolder, target: Target = 'native'): vscode.ProcessExecution | vscode.CustomExecution {
    if (target === 'native') requireNativeHost();
    const config = vscode.workspace.getConfiguration('bhash', folder?.uri);
    const {java, jar} = tools();
    const options: NativeOptions = {java, jar, mode: config.get('native.toolchainMode', 'managed'), home: config.get('native.toolchainHome', '')};
    const cwd = folder?.uri.fsPath || (input ? path.dirname(input) : path.dirname(jar));
    if (process.platform === 'win32') return new vscode.CustomExecution(async () => {
        const written = new vscode.EventEmitter<string>();
        const closed = new vscode.EventEmitter<number>();
        const terminal = new TaskConsole({...options, operation, input, target}, cwd,
            text => written.fire(text), code => closed.fire(code));
        return {onDidWrite: written.event, onDidClose: closed.event,
            open: () => {void terminal.start();}, close: () => {terminal.close(); written.dispose(); closed.dispose();},
            handleInput: (data: string) => terminal.handleInput(data)};
    });
    return new vscode.ProcessExecution(process.execPath, [context.asAbsolutePath('dist/native-task.js'), JSON.stringify({...options, operation, input, target})],
        {cwd, env: {ELECTRON_RUN_AS_NODE:'1'}});
}
function nativeToolsTask(definition: NativeToolsTask, folder?: vscode.WorkspaceFolder): vscode.Task {
    if (!['setup','check','repair'].includes(definition.operation)) throw new Error('Bhash native tools operation must be setup, check or repair.');
    const task = new vscode.Task(definition, folder || vscode.TaskScope.Global,
        `${definition.operation} native tools`, 'Bhash', compilerExecution(definition.operation, undefined, folder));
    task.presentationOptions = {echo: false, reveal:vscode.TaskRevealKind.Always, panel:vscode.TaskPanelKind.Dedicated, clear:true};
    return task;
}
async function nativeTools(operation: NativeToolsTask['operation']) {
    if (!trusted()) return;
    const folder = vscode.workspace.workspaceFolders?.find(f => f.uri.scheme === 'file');
    await vscode.tasks.executeTask(nativeToolsTask({type:'bhash-native-tools', operation},folder));
}
async function execute(command: 'build' | 'run') {
    if (!trusted()) return;
    const {input,folder} = await activeInput();
    await saveInput(input);
    const available: Target[] = command === 'run' ? targets.filter(t => t !== 'wasm-browser') : [...targets];
    const target = await vscode.window.showQuickPick(available, {title: `Bhash: ${command}`, placeHolder: 'Select the target platform'});
    if (target) await vscode.tasks.executeTask(taskFor({type: 'bhash', command, target: target as Target}, folder, input));
}
async function saveInput(input: string) {
    // Build/run/debug use disk contents. Never silently compile stale editor buffers.
    for (const document of vscode.workspace.textDocuments) {
        if (!document.isDirty || document.uri.scheme !== 'file' || !['bhash', 'bhash-project'].includes(document.languageId)) continue;
        const owner = client ? await client.sendRequest<string>('bhash/projectInput', {uri: document.uri.toString()}) : document.uri.fsPath;
        if (owner === input && !await document.save()) throw new Error('Save the Bhash project files before building or running.');
    }
}
async function dependencies(action: 'lock' | 'restore' | 'verify') {
    if (!trusted()) return;
    const {input,folder} = await activeInput();
    if (path.basename(input) !== 'bhash.project') throw new Error('Open a file in a Bhash project before managing dependencies.');
    for (const document of vscode.workspace.textDocuments) {
        if (document.isDirty && document.uri.scheme === 'file' && ['bhash','bhash-project'].includes(document.languageId)
            && !await document.save()) throw new Error('Save Bhash source and project files before managing dependencies.');
    }
    const {java,jar} = tools();
    const task = new vscode.Task({type: 'bhash-dependencies', action}, folder || vscode.TaskScope.Global,
        `${action} dependencies`, 'Bhash', new vscode.ProcessExecution(java,
            ['-Dfile.encoding=UTF-8','-jar',jar,'deps',action,input], {cwd: path.dirname(input)}));
    task.presentationOptions = {echo: false, reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Dedicated, clear: true};
    const execution = await vscode.tasks.executeTask(task);
    const listener = vscode.tasks.onDidEndTaskProcess(event => {
        if (event.execution !== execution) return;
        listener.dispose();
        if (event.exitCode === 0) void restart().catch(error => output.appendLine(String(error)));
    });
    context.subscriptions.push(listener);
}
async function newProject() {
    if (!trusted()) return;
    const folders = vscode.workspace.workspaceFolders?.filter(f => f.uri.scheme === 'file') || [];
    let parent = folders.length === 1 ? folders[0].uri : undefined;
    if (!parent) parent = (await vscode.window.showOpenDialog({canSelectFiles: false, canSelectFolders: true, canSelectMany: false, openLabel: 'Create Bhash project here'}))?.[0];
    if (!parent || parent.scheme !== 'file') return;
    const name = await vscode.window.showInputBox({title: 'Bhash project name', value: 'bhash-app', validateInput: value => /^[\p{L}\p{N}_-]+$/u.test(value) ? undefined : 'Use letters, numbers, underscores, or hyphens.'});
    if (!name) return;
    const directory = path.join(parent.fsPath, name);
    await fs.mkdir(directory); // Existing directories are never overwritten.
    await fs.mkdir(path.join(directory, 'src'));
    await fs.writeFile(path.join(directory, 'bhash.project'), `name=${name}\nsources=src\n`, {encoding: 'utf8', flag: 'wx'});
    const source = path.join(directory, 'src', 'main.bhash');
    await fs.writeFile(source, 'कार्य मुख्य() -> शून्य {\n    लिखो("नमस्ते, दुनिया!");\n}\n', {encoding: 'utf8', flag: 'wx'});
    await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(source));
}
export function activate(extensionContext: vscode.ExtensionContext) {
    context = extensionContext;
    output = vscode.window.createOutputChannel('Bhash');context.subscriptions.push(output);
    registerNativeDebug(context, {activeInput, save: saveInput, log: text => output.appendLine(text), tools: folder => {
        const config = vscode.workspace.getConfiguration('bhash', folder?.uri);
        return {...tools(), mode: config.get('native.toolchainMode', 'managed'), home: config.get('native.toolchainHome', '')};
    }});
    const command = (id: string, action: () => unknown) => context.subscriptions.push(vscode.commands.registerCommand(id, async () => {try {await action();}catch(error){void vscode.window.showErrorMessage(`Bhash: ${error instanceof Error ? error.message : String(error)}`);}}));
    command('bhash.build', () => execute('build'));command('bhash.run', () => execute('run'));command('bhash.newProject',newProject);
    command('bhash.setupNativeTools',()=>nativeTools('setup'));
    command('bhash.checkNativeSetup',()=>nativeTools('check'));
    command('bhash.repairNativeTools',()=>nativeTools('repair'));
    command('bhash.lockDependencies',()=>dependencies('lock'));
    command('bhash.restoreDependencies',()=>dependencies('restore'));
    command('bhash.verifyDependencies',()=>dependencies('verify'));
    command('bhash.openCommunity',()=>vscode.env.openExternal(vscode.Uri.parse('https://discord.gg/qC5zdJy6Qw')));
    command('bhash.restartServer',restart);command('bhash.openPlayground',()=>vscode.env.openExternal(vscode.Uri.parse('https://play.bhashitva.org/')));
    context.subscriptions.push(vscode.workspace.onDidGrantWorkspaceTrust(() => {void startServer();}));
    context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(event => {if(event.affectsConfiguration('bhash.javaPath') || event.affectsConfiguration('bhash.compilerPath')) void restart().catch(error => output.appendLine(String(error)));}));
    context.subscriptions.push(vscode.tasks.registerTaskProvider('bhash', {
        provideTasks: () => [],
        resolveTask: task => {
            if (!vscode.workspace.isTrusted || typeof task.scope !== 'object' || task.scope.uri.scheme !== 'file') return undefined;
            const definition = task.definition as BhashTask;
            const input = path.resolve(task.scope.uri.fsPath, definition.project || '.');
            return taskFor(definition,task.scope,input);
        }
    }));
    // CustomExecution tasks need their own registered provider on Windows,
    // even when the command supplies the execution callback programmatically.
    context.subscriptions.push(vscode.tasks.registerTaskProvider('bhash-native-tools', {
        provideTasks: () => [],
        resolveTask: task => {
            if (!vscode.workspace.isTrusted) return undefined;
            const folder = typeof task.scope === 'object' ? task.scope : undefined;
            if (folder ? folder.uri.scheme !== 'file' : task.scope !== vscode.TaskScope.Global) return undefined;
            const definition = task.definition as NativeToolsTask;
            if (!['setup','check','repair'].includes(definition.operation)) return undefined;
            // Task identity must use the original definition object.
            return nativeToolsTask(definition,folder);
        }
    }));
    void startServer();
}
export async function deactivate() {serverWatcher?.dispose();await client?.stop();client=undefined;}
