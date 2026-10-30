// SPDX-License-Identifier: Apache-2.0
const fs=require('node:fs/promises');
const path=require('node:path');
const {execFile}=require('node:child_process');
const {promisify}=require('node:util');
const {runTests,downloadAndUnzipVSCode,resolveCliArgsFromVSCodeExecutablePath}=require('@vscode/test-electron');
(async()=>{
    const root=path.resolve(__dirname,'../../..');
    const output=path.join(root,'target/vscode-debug');
    const workspace=path.join(output,'workspace हिन्दी');
    const extensions=path.join(output,'extensions');
    const userData=path.join(output,'user-data');
    await fs.mkdir(path.join(workspace,'.vscode'),{recursive:true});
    await fs.writeFile(path.join(workspace,'.vscode','settings.json'),JSON.stringify({'debug.allowBreakpointsEverywhere':false}));
    await fs.copyFile(path.join(root,'examples/native/debug-inspection.bhash'),path.join(workspace,'debug-inspection.bhash'));
    // Exercise the shipped package, including script loading from a quoted Unicode
    // extension path. No source-tree-only files may be required by the debugger.
    const development=path.join(output,process.platform==='win32'?"Bhash extension हिन्दी 'quotes'":'Bhash extension हिन्दी "quotes"');
    await promisify(execFile)(process.env.BHASH_TEST_PYTHON || (process.platform==='win32'?'python':'python3'),['-m','zipfile','-e',path.join(root,'target',`bhash-vscode-${require('../package.json').version}.vsix`),development]);
    const executable=await downloadAndUnzipVSCode('stable');
    const [cli,...prefix]=process.platform==='win32'
        ? [executable,path.join(path.dirname(executable),'resources','app','out','cli.js')]
        : resolveCliArgsFromVSCodeExecutablePath(executable,{reuseMachineInstall:true});
    const {stdout,stderr}=await promisify(execFile)(cli,[...prefix,'--extensions-dir',extensions,'--user-data-dir',userData,
        '--install-extension','ms-vscode.cpptools','--force'],{encoding:'utf8',timeout:180000,windowsHide:true,env:{...process.env,ELECTRON_RUN_AS_NODE:'1'}});
    console.log(stdout,stderr);
    await runTests({vscodeExecutablePath:executable,extensionDevelopmentPath:path.join(development,'extension'),
        extensionTestsPath:path.join(__dirname,'debug-host.cjs'),
        launchArgs:[workspace,'--extensions-dir',extensions,'--user-data-dir',userData,'--disable-workspace-trust',
            '--skip-welcome','--skip-release-notes',...(process.platform==='linux'?['--no-sandbox']:[]),'--disable-gpu'],
        extensionTestsEnv:{BHASH_DEBUG_WORKSPACE:workspace,BHASH_DEBUG_LOG:path.join(output,'dap.log'),BHASH_NATIVE_TOOLCHAIN:'managed',BHASH_TOOLCHAIN_HOME:process.env.BHASH_TOOLCHAIN_HOME}});
})().catch(error=>{console.error(error);process.exitCode=1;});
