// SPDX-License-Identifier: Apache-2.0
const fs=require('node:fs/promises');
const path=require('node:path');
const {execFile}=require('node:child_process');
const {promisify}=require('node:util');
const {runTests,downloadAndUnzipVSCode,resolveCliArgsFromVSCodeExecutablePath}=require('@vscode/test-electron');
(async()=>{
    const root=path.resolve(__dirname,'../../..');
    const output=path.join(root,'target/vscode-debug');
    const workspace=path.join(output,'workspace');
    const extensions=path.join(output,'extensions');
    const userData=path.join(output,'user-data');
    await fs.mkdir(workspace,{recursive:true});
    await fs.copyFile(path.join(root,'examples/native/debug-inspection.bhash'),path.join(workspace,'debug-inspection.bhash'));
    const executable=await downloadAndUnzipVSCode('stable');
    const [cli,...prefix]=resolveCliArgsFromVSCodeExecutablePath(executable,{reuseMachineInstall:true});
    const {stdout,stderr}=await promisify(execFile)(cli,[...prefix,'--extensions-dir',extensions,'--user-data-dir',userData,
        '--install-extension','ms-vscode.cpptools','--force'],{encoding:'utf8',timeout:180000,env:{...process.env,ELECTRON_RUN_AS_NODE:'1'}});
    console.log(stdout,stderr);
    await runTests({vscodeExecutablePath:executable,extensionDevelopmentPath:path.resolve(__dirname,'..'),
        extensionTestsPath:path.join(__dirname,'debug-host.cjs'),
        launchArgs:[workspace,'--extensions-dir',extensions,'--user-data-dir',userData,'--disable-workspace-trust',
            '--skip-welcome','--skip-release-notes','--no-sandbox','--disable-gpu'],
        extensionTestsEnv:{BHASH_DEBUG_WORKSPACE:workspace,BHASH_DEBUG_LOG:path.join(output,'dap.log'),BHASH_NATIVE_TOOLCHAIN:'managed',BHASH_TOOLCHAIN_HOME:process.env.BHASH_TOOLCHAIN_HOME}});
})().catch(error=>{console.error(error);process.exitCode=1;});
