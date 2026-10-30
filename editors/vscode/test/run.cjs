// SPDX-License-Identifier: Apache-2.0
const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');
const {runTests}=require('@vscode/test-electron');
(async()=>{
    const workspace=await fs.mkdtemp(path.join(os.tmpdir(),'bhash-vscode-'));
    try {
        await fs.mkdir(path.join(workspace,'src'));await fs.mkdir(path.join(workspace,'.vscode'));
        await fs.writeFile(path.join(workspace,'bhash.project'),'name=host-test\nsources=src\n');
        await fs.writeFile(path.join(workspace,'src','math.bhash'),'संकुल गणित;\nसार्वजनिक कार्य दोहरा(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }\n');
        await fs.writeFile(path.join(workspace,'src','main.bhash'),'आयात गणित.दोहरा;\nकार्य मुख्य() -> शून्य {\n    स्थिर क: पूर्णांक = दोहरा(३);\n    लिखो(क);\n}\n');
        await fs.writeFile(path.join(workspace,'.vscode','tasks.json'),JSON.stringify({version:'2.0.0',tasks:[{type:'bhash',label:'Integration build',command:'build',project:'bhash.project',target:'jvm'},...(['linux','darwin'].includes(process.platform)&&process.env.BHASH_TEST_NATIVE==='1'?[{type:'bhash',label:'Native integration build',command:'build',project:'bhash.project',target:'native'}]:[])]}));
        await runTests({version:process.env.VSCODE_VERSION||'1.95.3',extensionDevelopmentPath:path.resolve(__dirname,'..'),extensionTestsPath:path.join(__dirname,'host.cjs'),
            launchArgs:[workspace,'--disable-workspace-trust','--skip-welcome','--skip-release-notes','--no-sandbox','--disable-gpu'],extensionTestsEnv:{BHASH_TEST_WORKSPACE:workspace, BHASH_TEST_NATIVE:process.env.BHASH_TEST_NATIVE||'0', BHASH_TOOLCHAIN_HOME:path.join(workspace,'native-cache')}});
    } finally {await fs.rm(workspace,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
