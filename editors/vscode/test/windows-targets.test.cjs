// SPDX-License-Identifier: Apache-2.0
// Opt-in desktop Windows regression: use an installed JDK, .NET SDK and cached native SDK.
const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const os=require('node:os');
const {spawn}=require('node:child_process');const {TaskConsole}=require('../dist/task-console.js');
for(const target of ['jvm','clr','native'])test(`Windows direct terminal: ${target} UTF-8 console I/O and compiler errors`,
    {skip:process.platform!=='win32'||process.env.BHASH_TEST_WINDOWS_TARGETS!=='1',timeout:240000},async()=>{
    const folder=fs.mkdtempSync(path.join(os.tmpdir(),'bhash-target-भारत-'));
    try {
        const source=path.join(folder,'console.bhash');
        fs.writeFileSync(source,'कार्य मुख्य() -> शून्य { लिखो("तैयार😀"); लिखो(पाठपढ़ो()); लिखो(पढ़ो()); }\n');
        const request={java:process.env.BHASH_TEST_JAVA||'java',jar:path.resolve(__dirname,'../server/bhash-compiler.jar'),
            mode:'managed',home:process.env.BHASH_TEST_TOOLCHAIN_HOME||'',operation:'run',target,input:source};
        if(target==='native')assert.ok(fs.existsSync(path.join(request.home,'active.json')),'use an existing SDK; this test must not install one');
        const invoke=async()=>{
            let output='',code,typed=false;
            const terminal=new TaskConsole(request,folder,text=>{
                output+=text;
                if(!typed&&output.includes('तैयार😀\r\n')){typed=true;terminal.handleInput('भारत😀\r४२\r');}
            },exit=>{code=exit;},(command,args,options)=>{
                assert.equal(command,request.java);assert.equal(options.windowsHide,true);assert.equal(options.stdio,'pipe');
                return spawn(command,args,options);
            });
            await terminal.start();return {status:code,output};
        };
        let result=await invoke();assert.equal(result.status,0,JSON.stringify(result));
        assert.equal(result.output,'तैयार😀\r\nभारत😀\r\n४२\r\nभारत😀\r\n४२\r\n');
        fs.writeFileSync(source,'कार्य मुख्य() -> शून्य { लिखो(अनजान); }\n');
        result=await invoke();assert.equal(result.status,1,JSON.stringify(result));
        assert.match(result.output,/अनजान/);assert.doesNotMatch(result.output,/�/);
    } finally {fs.rmSync(folder,{recursive:true,force:true});}
});
