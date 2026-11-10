// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'node:path';
import {capture, compilerInvocation} from './native';
import {ConsoleRequest} from './task-console';

const operations = {
    doctor: 'Check Setup',
    build: 'Build',
    run: 'Run',
    logcat: 'Logcat',
} as const;
type Operation = keyof typeof operations;
interface AndroidTask extends vscode.TaskDefinition {
    type: 'bhash-android';
    operation: Operation;
    project?: string;
    device?: string;
}
interface Project { folder: vscode.WorkspaceFolder; directory: string; }
interface Device { serial: string; state: string; }
interface Services {
    trusted(): boolean;
    tools(folder?: vscode.WorkspaceFolder, compiler?: 'configured' | 'bundled'): {java: string; jar: string};
    execution(request: ConsoleRequest, cwd: string): vscode.CustomExecution;
}

function inside(directory: string, file: string): boolean {
    const relative = path.relative(directory, file);
    return relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
}

async function isProject(directory: string): Promise<boolean> {
    for (const file of ['bhash.project', 'bhash.exports', 'settings.gradle.kts']) {
        try {
            const stat = await vscode.workspace.fs.stat(vscode.Uri.file(path.join(directory, file)));
            if (!(stat.type & vscode.FileType.File)) return false;
        } catch { return false; }
    }
    return true;
}

async function currentProject(): Promise<Project | undefined> {
    const uri = vscode.window.activeTextEditor?.document.uri;
    const folder = uri?.scheme === 'file' ? vscode.workspace.getWorkspaceFolder(uri) : undefined;
    if (folder && uri) {
        for (let directory = path.dirname(uri.fsPath); inside(folder.uri.fsPath, directory); directory = path.dirname(directory)) {
            if (await isProject(directory)) return {folder, directory};
            if (directory === folder.uri.fsPath || directory === path.dirname(directory)) break;
        }
    }
    const projects: Project[] = [];
    for (const candidate of vscode.workspace.workspaceFolders ?? []) {
        if (candidate.uri.scheme === 'file' && await isProject(candidate.uri.fsPath)) {
            projects.push({folder: candidate, directory: candidate.uri.fsPath});
        }
    }
    if (!projects.length) throw new Error('Open a folder created by Bhash android init, or open a file in that project.');
    if (projects.length === 1) return projects[0];
    const choice = await vscode.window.showQuickPick(projects.map(project => ({
        label: project.folder.name, description: project.directory, project,
    })), {title: 'Select a Bhash Android project'});
    return choice?.project;
}

async function saveProject(project: Project): Promise<void> {
    for (const document of vscode.workspace.textDocuments) {
        if (!document.isDirty || document.uri.scheme !== 'file' || !inside(project.directory, document.uri.fsPath)) continue;
        if (!/(?:\.(?:bhash|kt|java|xml|properties|gradle(?:\.kts)?)|(?:^|[\\/])bhash\.(?:project|exports|lock))$/i.test(document.uri.fsPath)) continue;
        if (!await document.save()) throw new Error('Save the Android project files before building or running.');
    }
}

async function devices(project: Project, services: Services): Promise<Device[]> {
    const {java, jar} = services.tools(project.folder);
    const invocation = compilerInvocation(jar, ['android', 'devices'], process.platform, project.directory);
    const result: unknown = JSON.parse(await capture(java, invocation.args, process.env, undefined,
        invocation.cwd ?? project.directory, 30_000));
    if (!Array.isArray(result) || result.some(device => !device || typeof device.serial !== 'string' || typeof device.state !== 'string')) {
        throw new Error('The compiler returned an invalid Android device list.');
    }
    return result as Device[];
}

async function newProject(services: Services): Promise<void> {
    const starter = await vscode.window.showQuickPick([
        {label: 'Hindi Offline Task List', description: 'मेरी कार्य सूची', template: 'task-list', folder: 'hindi-tasks', appId: 'org.example.hinditasks'},
        {label: 'Hello World', description: 'नमस्ते, दुनिया!', template: 'hello', folder: 'bhash-android-app', appId: 'org.example.bhashapp'},
    ], {title: 'Choose a Bhash Android starter'});
    if (!starter) return;
    const [parent] = await vscode.window.showOpenDialog({
        canSelectFiles: false, canSelectFolders: true, openLabel: 'Select parent folder',
        title: 'Choose where to create the Android project',
    }) ?? [];
    if (!parent) return;
    if (parent.scheme !== 'file') throw new Error('Choose a folder on the file system of the VS Code host.');
    const folder = await vscode.window.showInputBox({
        title: 'New Android project folder', value: starter.folder,
        validateInput: value => /^[\p{L}\p{M}\p{N}_-]+(?: [\p{L}\p{M}\p{N}_-]+)*$/u.test(value)
            ? undefined : 'Use letters, numbers, spaces, underscores, or hyphens for the new folder name.',
    });
    if (!folder) return;
    const appId = await vscode.window.showInputBox({
        title: 'Android application ID', value: starter.appId,
        validateInput: value => /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value)
            ? undefined : 'Enter a lowercase application ID such as org.example.hinditasks.',
    });
    if (!appId) return;
    const destination = vscode.Uri.joinPath(parent, folder);
    const {java, jar} = services.tools(undefined, 'bundled');
    const args = ['android', 'init', destination.fsPath, '--id', appId, '--template', starter.template];
    const invocation = compilerInvocation(jar, args, process.platform, parent.fsPath);
    await capture(java, invocation.args, process.env, undefined, invocation.cwd ?? parent.fsPath, 60_000);
    await vscode.commands.executeCommand('vscode.openFolder', destination, {forceNewWindow: true});
}

export function registerAndroid(context: vscode.ExtensionContext, services: Services): void {
    const selected = new Map<string, string>();
    async function selectDevice(project: Project, alwaysAsk: boolean): Promise<string | undefined> {
        const list = await devices(project, services);
        const ready = list.filter(device => device.state === 'device');
        if (!ready.length) {
            const states = list.map(device => `${device.serial} (${device.state})`).join(', ');
            throw new Error('No ready Android device. Start an emulator or connect and authorize a phone.' + (states ? ' Detected ' + states + '.' : ''));
        }
        const previous = selected.get(project.directory);
        if (!alwaysAsk && ready.some(device => device.serial === previous)) return previous;
        const device = !alwaysAsk && ready.length === 1 ? ready[0].serial : (await vscode.window.showQuickPick(
            ready.map(device => ({label: device.serial, description: device.serial === previous ? 'Current device' : undefined})),
            {title: 'Select an Android device'}))?.label;
        if (device) selected.set(project.directory, device);
        return device;
    }

    function taskFor(definition: AndroidTask, project: Project): vscode.Task {
        const {operation, device} = definition;
        const args: string[] = [operation];
        if (device) args.push('--device', device);
        const execution = services.execution({...services.tools(project.folder), androidArgs: args}, project.directory);
        const name = 'Android: ' + operations[operation] + (device ? ' (' + device + ')' : '');
        const task = new vscode.Task(definition, project.folder, name, 'Bhash', execution,
            operation === 'build' || operation === 'run' ? ['$bhash'] : []);
        task.presentationOptions = {echo: false, reveal: vscode.TaskRevealKind.Always, panel: vscode.TaskPanelKind.Dedicated, clear: true};
        return task;
    }

    async function run(operation: Operation | 'selectDevice'): Promise<vscode.TaskExecution | string | undefined> {
        if (!services.trusted()) return;
        const project = await currentProject();
        if (!project) return;
        if (operation === 'selectDevice') return selectDevice(project, true);
        let device: string | undefined;
        if (operation === 'run' || operation === 'logcat') {
            device = await selectDevice(project, false);
            if (!device) return;
        }
        if (operation === 'build' || operation === 'run') await saveProject(project);
        if (operation === 'logcat') {
            // Keep one Logcat stream per project and device.
            const existing = vscode.tasks.taskExecutions.find(({task}) =>
                task.definition.type === 'bhash-android' && task.definition.operation === 'logcat' &&
                task.definition.device === device && task.definition.project === path.relative(project.folder.uri.fsPath, project.directory) &&
                typeof task.scope === 'object' && task.scope.uri.toString() === project.folder.uri.toString());
            if (existing) return existing;
        }
        const definition: AndroidTask = {type: 'bhash-android', operation, project: path.relative(project.folder.uri.fsPath, project.directory)};
        if (device) definition.device = device;
        return vscode.tasks.executeTask(taskFor(definition, project));
    }

    for (const operation of ['newProject', 'doctor', 'selectDevice', 'build', 'run', 'logcat'] as const) {
        context.subscriptions.push(vscode.commands.registerCommand('bhash.android.' + operation, async () => {
            try {
                if (operation !== 'newProject') return await run(operation);
                if (services.trusted()) await newProject(services);
            }
            catch (error) {
                void vscode.window.showErrorMessage('Bhash Android: ' + (error instanceof Error ? error.message : String(error)));
            }
        }));
    }
    context.subscriptions.push(vscode.tasks.registerTaskProvider('bhash-android', {
        provideTasks: () => [],
        resolveTask: async task => {
            if (!vscode.workspace.isTrusted || typeof task.scope !== 'object' || task.scope.uri.scheme !== 'file') return;
            const definition = task.definition as AndroidTask;
            if (!Object.hasOwn(operations, definition.operation)) return;
            const directory = path.resolve(task.scope.uri.fsPath, definition.project || '.');
            if (inside(task.scope.uri.fsPath, directory) && await isProject(directory)) {
                return taskFor(definition, {folder: task.scope, directory});
            }
        },
    }));
}
