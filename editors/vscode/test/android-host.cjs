// SPDX-License-Identifier: Apache-2.0
const vscode = require('vscode');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {execFile, spawn} = require('node:child_process');
const {promisify} = require('node:util');
const delay = require('node:timers/promises').setTimeout;

function observeCompletion(operation, processExit = true) {
    let subscription;
    let timer;
    const result = new Promise((resolve, reject) => {
        const subscribe = processExit ? vscode.tasks.onDidEndTaskProcess : vscode.tasks.onDidEndTask;
        subscription = subscribe(({execution, exitCode}) => {
            if (execution.task.definition.type === 'bhash-android' && execution.task.definition.operation === operation)
                resolve(exitCode);
        });
        timer = setTimeout(() => reject(Error('Android ' + operation + ' task did not finish.')), 9 * 60_000);
    });
    return {result, dispose() { clearTimeout(timer); subscription.dispose(); }};
}

async function executeTask(operation) {
    const ended = observeCompletion(operation);
    try {
        const execution = await vscode.commands.executeCommand('bhash.android.' + operation);
        assert.equal(execution?.task.definition.operation, operation);
        assert.equal(await ended.result, 0, operation + ' task should succeed');
        return execution;
    } finally {
        ended.dispose();
    }
}

async function waitForLog(jar, project, serial) {
    const child = spawn('java', ['-jar', jar, 'android', 'logcat', '--device', serial],
        {cwd: project, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true});
    let output = '';
    let timer;
    try {
        await new Promise((resolve, reject) => {
            timer = setTimeout(() => reject(Error('App Logcat marker missing:\n' + output)), 15_000);
            child.once('error', reject);
            child.once('exit', code => reject(Error('Logcat exited ' + code + ':\n' + output)));
            for (const stream of [child.stdout, child.stderr]) stream.setEncoding('utf8').on('data', text => {
                output += text;
                if (output.includes('BhashAndroidSmoke') && output.includes('भाष Android smoke')) resolve();
            });
        });
        assert.match(output, /Logcat for org\.bhashitva\.vscodeandroid \(PID \d+\) on /);
    } finally {
        clearTimeout(timer);
        child.kill();
    }
}

exports.run = async () => {
    const project = process.env.BHASH_ANDROID_TEST_PROJECT;
    const extension = vscode.extensions.getExtension('bhashitva.bhash');
    assert.ok(extension);
    await extension.activate();
    const jar = path.join(extension.extensionPath, 'server/bhash-compiler.jar');
    const serial = process.env.ANDROID_SERIAL || 'emulator-5554';
    const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
    const {stdout} = await promisify(execFile)(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk']);
    assert.equal(stdout.trim(), '29');
    const document = await vscode.workspace.openTextDocument(path.join(project, 'src/greeting.bhash'));
    const editor = await vscode.window.showTextDocument(document);

    await executeTask('doctor');
    // Confirm the command discovers devices and accepts the visible Quick Pick selection.
    let done = false;
    const selection = vscode.commands.executeCommand('bhash.android.selectDevice').then(device => {done = true; return device;});
    for (let i = 0; i < 45 && !done; i++) {
        await delay(500);
        await vscode.commands.executeCommand('workbench.action.acceptSelectedQuickOpenItem');
    }
    assert.ok(done, 'Android device selection did not complete');
    assert.equal(await selection, serial);

    await editor.edit(edit => edit.insert(document.positionAt(document.getText().length), '\n// Android editor smoke\n'));
    assert.ok(document.isDirty);
    await executeTask('build');
    assert.ok(!document.isDirty, 'build saved the edited Bhash source');
    await fs.access(path.join(project, 'app/build/outputs/apk/debug/app-debug.apk'));
    const run = await executeTask('run');
    assert.equal(run.task.definition.device, serial);
    if (process.env.BHASH_ANDROID_TIMEOUT_MARKER) {
        await fs.access(process.env.BHASH_ANDROID_TIMEOUT_MARKER);
        console.log('PASS VS Code Android Run recovered from am start -W reporting timeout on API 29.');
    }
    const logcat = await vscode.commands.executeCommand('bhash.android.logcat');
    assert.equal(logcat?.task.definition.device, serial);
    try {
        await waitForLog(jar, project, serial);
        assert.ok(vscode.tasks.taskExecutions.some(task => task.task.definition.operation === 'logcat'), 'Logcat is streaming');
    } finally {
        if (logcat) {
            const ended = observeCompletion('logcat', false);
            try {
                logcat.terminate();
                await ended.result;
            } finally {
                ended.dispose();
            }
        }
    }
    console.log('PASS VS Code Android setup, device selection, build, run and app-scoped Logcat on API 29.');
};
