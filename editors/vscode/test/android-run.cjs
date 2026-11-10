// SPDX-License-Identifier: Apache-2.0
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {runTests} = require('@vscode/test-electron');

(async () => {
    const directory = await fs.realpath(await fs.mkdtemp(path.join(os.tmpdir(), 'bhash-vscode-android-')));
    try {
        const extension = path.resolve(__dirname, '..');
        const project = path.join(directory, 'Hindi Android App');
        await promisify(execFile)('java', ['-jar', path.join(extension, 'server/bhash-compiler.jar'),
            'android', 'init', project, '--id', 'org.bhashitva.vscodeandroid'], {timeout: 30_000});
        const activity = path.join(project, 'app/src/main/java/org/bhashitva/androidstarter/MainActivity.kt');
        const source = await fs.readFile(activity, 'utf8');
        const marker = 'super.onCreate(savedInstanceState)';
        if (!source.includes(marker)) throw Error('Expected the starter Activity entry point.');
        await fs.writeFile(activity, source.replace(marker, marker + '\n        android.util.Log.i("BhashAndroidSmoke", "भाष Android smoke")'));
        await runTests({
            version: process.env.VSCODE_VERSION || '1.95.3',
            vscodeExecutablePath: process.env.BHASH_VSCODE_EXECUTABLE || undefined,
            extensionDevelopmentPath: extension,
            extensionTestsPath: path.join(__dirname, 'android-host.cjs'),
            launchArgs: [project, '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--no-sandbox', '--disable-gpu',
                '--user-data-dir', path.join(directory, 'user'), '--extensions-dir', path.join(directory, 'extensions')],
            extensionTestsEnv: {BHASH_ANDROID_TEST_PROJECT: project},
        });
    } finally {
        await fs.rm(directory, {recursive: true, force: true});
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
