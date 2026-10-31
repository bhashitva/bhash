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
    const incompatibleMarker=path.join(output,'incompatible-shell-used');
    const settings=path.join(userData,'User','settings.json');
    if(process.platform==='win32'){
        await fs.mkdir(path.dirname(settings),{recursive:true});
        await fs.rm(incompatibleMarker,{force:true});
        const profile={path:process.execPath,args:['-e',`require('fs').writeFileSync(${JSON.stringify(incompatibleMarker)},'wrong shell');process.exit(42)`]};
        await fs.writeFile(settings,JSON.stringify({
            'terminal.integrated.profiles.windows':{'Incompatible WSL-like shell':profile},
            'terminal.integrated.defaultProfile.windows':'Incompatible WSL-like shell',
            'terminal.integrated.automationProfile.windows':profile}));
    }
    await fs.writeFile(path.join(workspace,'.vscode','settings.json'),JSON.stringify({'debug.allowBreakpointsEverywhere':false}));
    await fs.copyFile(path.join(root,'examples/native/debug-inspection.bhash'),path.join(workspace,'debug-inspection.bhash'));
    // Exercise the shipped package, including script loading from a quoted Unicode
    // extension path. No source-tree-only files may be required by the debugger.
    const development=path.join(output,process.platform==='win32'?"Bhash extension हिन्दी 'quotes'":'Bhash extension हिन्दी "quotes"');
    await promisify(execFile)(process.env.BHASH_TEST_PYTHON || (process.platform==='win32'?'python':'python3'),['-m','zipfile','-e',path.join(root,'target',`bhash-vscode-${require('../package.json').version}.vsix`),development]);
    await fs.writeFile(path.join(workspace,'console-debug.bhash'),'कार्य मुख्य() -> शून्य {\n लिखो("तैयार😀"); // IO_BEFORE\n स्थिर नाम: पाठ = पाठपढ़ो();\n लिखो(नाम); // IO_AFTER\n}\n');
    const executable=await downloadAndUnzipVSCode('stable');
    const [cli,...prefix]=resolveCliArgsFromVSCodeExecutablePath(executable,{reuseMachineInstall:true});
    const installArgs=[...prefix,'--extensions-dir',extensions,'--user-data-dir',userData,
        '--install-extension','ms-vscode.cpptools','--force'];
    // Use the vendor's Windows launcher rather than assuming its internal JS layout.
    // cmd needs one outer quoted command; reject metacharacters in these CI-only paths.
    if(process.platform==='win32' && [cli,...installArgs].some(value=>/["%\r\n&|<>^]/.test(value)))throw Error('Unsupported character in Windows test launcher path');
    const command=process.platform==='win32'?process.env.ComSpec || 'C:\\Windows\\System32\\cmd.exe':cli;
    const args=process.platform==='win32'?['/d','/s','/c','"'+[cli,...installArgs].map(value=>'"'+value+'"').join(' ')+'"']:installArgs;
    const {stdout,stderr}=await promisify(execFile)(command,args,{encoding:'utf8',timeout:180000,windowsHide:true,
        windowsVerbatimArguments:process.platform==='win32'});
    console.log(stdout,stderr);
    // test-electron launches through cmd on Windows, so its path options need shell quotes.
    const testPath=value=>process.platform==='win32'?'"'+value+'"':value;
    await runTests({vscodeExecutablePath:executable,extensionDevelopmentPath:testPath(path.join(development,'extension')),
        extensionTestsPath:testPath(path.join(__dirname,'debug-host.cjs')),
        launchArgs:[testPath(workspace),'--extensions-dir',testPath(extensions),'--user-data-dir',testPath(userData),'--disable-workspace-trust',
            '--skip-welcome','--skip-release-notes',...(process.platform==='linux'?['--no-sandbox']:[]),'--disable-gpu'],
        extensionTestsEnv:{BHASH_DEBUG_WORKSPACE:workspace,BHASH_DEBUG_SETTINGS:settings,BHASH_DEBUG_SHELL_MARKER:incompatibleMarker,BHASH_DEBUG_LOG:path.join(output,'dap.log'),BHASH_NATIVE_TOOLCHAIN:'managed',BHASH_TOOLCHAIN_HOME:process.env.BHASH_TOOLCHAIN_HOME}});
})().catch(error=>{console.error(error);process.exitCode=1;});
