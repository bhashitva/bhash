// SPDX-License-Identifier: Apache-2.0
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {runTests} = require('@vscode/test-electron');

async function launchTimeoutEnvironment(directory) {
    if (process.platform !== 'linux') return {};
    const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    const overlay = path.join(directory, 'sdk');
    const tools = 'platform-tools';
    const marker = path.join(directory, 'launch-timeout');
    await fs.mkdir(path.join(overlay, tools), {recursive: true});
    for (const entry of await fs.readdir(sdk)) {
        if (entry !== tools) await fs.symlink(path.join(sdk, entry), path.join(overlay, entry));
    }
    for (const entry of await fs.readdir(path.join(sdk, tools))) {
        if (entry !== 'adb') await fs.symlink(path.join(sdk, tools, entry), path.join(overlay, tools, entry));
    }
    // Launch on the real emulator, then exercise the timeout path with its real window state.
    await fs.writeFile(path.join(overlay, tools, 'adb'), `#!/bin/sh
if [ "$1" = -s ] && [ "$3" = shell ] && [ "$4" = am ] && [ "$5" = start ] && [ "$6" = -W ]; then
    output=$("$BHASH_ANDROID_REAL_ADB" "$@")
    status=$?
    if [ "$status" -eq 0 ] && printf '%s\\n' "$output" | grep -qxE 'Status: (ok|timeout)[[:space:]]*'; then
        printf '%s\\n' "$output" | sed 's/^Status: ok[[:space:]]*$/Status: timeout/'
        touch "$BHASH_ANDROID_TIMEOUT_MARKER"
    else
        printf '%s\\n' "$output"
    fi
    exit "$status"
fi
exec "$BHASH_ANDROID_REAL_ADB" "$@"
`, {mode: 0o755});
    return {ANDROID_HOME: overlay, ANDROID_SDK_ROOT: overlay,
        BHASH_ANDROID_REAL_ADB: path.join(sdk, tools, 'adb'), BHASH_ANDROID_TIMEOUT_MARKER: marker};
}

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
            extensionTestsEnv: {BHASH_ANDROID_TEST_PROJECT: project, ...await launchTimeoutEnvironment(directory)},
        });
    } finally {
        await fs.rm(directory, {recursive: true, force: true});
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
