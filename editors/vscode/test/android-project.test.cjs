// SPDX-License-Identifier: Apache-2.0
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const {readFileSync} = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {test} = require('node:test');
const {transformSync} = require('esbuild');
const native = require('../dist/native.js');

const jar = path.resolve(__dirname, '../server/bhash-compiler.jar');
const source = readFileSync(path.resolve(__dirname, '../src/android.ts'), 'utf8');
const compiled = transformSync(source, {loader: 'ts', format: 'cjs', target: 'node20'}).code;

function wizard(parent, inputs) {
    const commands = new Map();
    const errors = [];
    const opened = [];
    const uri = fsPath => ({scheme: 'file', fsPath});
    const vscode = {
        window: {
            showQuickPick: async items => items.find(item => item.label === 'Hindi Offline Task List'),
            showOpenDialog: async options => {
                assert.equal(options.canSelectFolders, true);
                assert.equal(options.canSelectFiles, false);
                return [uri(parent)];
            },
            showInputBox: async options => {
                const next = inputs.shift();
                if (next !== undefined) assert.equal(options.validateInput(next), undefined);
                return next;
            },
            showErrorMessage: error => { errors.push(error); },
        },
        Uri: {joinPath: (base, name) => uri(path.join(base.fsPath, name))},
        tasks: {registerTaskProvider() {}},
        commands: {
            registerCommand: (id, handler) => { commands.set(id, handler); },
            executeCommand: async (id, destination, options) => {
                assert.equal(id, 'vscode.openFolder');
                assert.equal(options.forceNewWindow, true);
                opened.push(destination.fsPath);
            },
        },
    };
    const module = {exports: {}};
    new Function('require', 'module', 'exports', compiled)(name => {
        if (name === 'vscode') return vscode;
        if (name === './native') return native;
        return require(name);
    }, module, module.exports);
    let compilerCalls = 0;
    module.exports.registerAndroid({subscriptions: []}, {
        trusted: () => true,
        tools: (folder, compiler) => {
            assert.equal(folder, undefined);
            assert.equal(compiler, 'bundled');
            compilerCalls++;
            return {java: 'java', jar};
        },
    });
    return {run: commands.get('bhash.android.newProject'), errors, opened, compilerCalls: () => compilerCalls};
}

test('Android project command creates the Hindi starter with the bundled compiler and opens it', async () => {
    const parent = await fs.mkdtemp(path.join(os.tmpdir(), 'bhash-android-project-'));
    try {
        const name = 'मेरी कार्य सूची';
        const appId = 'org.example.hinditasks';
        const destination = path.join(parent, name);
        const creator = wizard(parent, [name, appId]);
        await creator.run();
        assert.deepEqual(creator.errors, []);
        assert.deepEqual(creator.opened, [destination]);
        assert.equal(creator.compilerCalls(), 1);
        await fs.access(path.join(destination, 'src/tasks.bhash'));
        await fs.access(path.join(destination, 'bhash.exports'));
        const properties = await fs.readFile(path.join(destination, 'gradle.properties'), 'utf8');
        assert.match(properties, /^bhashApplicationId=org\.example\.hinditasks$/m);
        assert.match(properties, /^bhashStarterTemplate=task-list$/m);
        assert.deepEqual(await fs.readFile(path.join(destination, 'bhash/compiler.jar')), await fs.readFile(jar));

        const duplicate = wizard(parent, [name, 'org.example.otherapp']);
        await duplicate.run();
        assert.match(duplicate.errors[0], /Already exists/);
        assert.deepEqual(duplicate.opened, []);
        assert.equal(await fs.readFile(path.join(destination, 'gradle.properties'), 'utf8'), properties);
    } finally {
        await fs.rm(parent, {recursive: true, force: true});
    }
});

test('cancelling the Android wizard leaves the compiler untouched', async () => {
    const creator = wizard(os.tmpdir(), ['hindi-tasks', undefined]);
    await creator.run();
    assert.equal(creator.compilerCalls(), 0);
    assert.deepEqual(creator.errors, []);
    assert.deepEqual(creator.opened, []);
});
