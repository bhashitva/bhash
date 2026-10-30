// SPDX-License-Identifier: Apache-2.0
// Actual shipped launcher on Apple Silicon, with only Apple OS tools on PATH.
const fs=require('node:fs');const path=require('node:path');const os=require('node:os');
const {spawnSync}=require('node:child_process');const assert=require('node:assert/strict');
assert.equal(process.platform,'darwin');assert.equal(process.arch,'arm64');
const home=fs.mkdtempSync(path.join(os.tmpdir(),'bhash native भारत '));
const launcher=path.resolve(__dirname,'../dist/native-task.js');
const java=path.join(process.env.JAVA_HOME,'bin/java');
const env={...process.env,PATH:'/usr/bin:/bin',BHASH_TOOLCHAIN_HOME:path.join(home,'SDK cache')};
delete env.BHASH_NATIVE_TOOLCHAIN;delete env.BHASH_CLANG;delete env.BHASH_LLVM_OPT;delete env.BHASH_READELF;
const request={java,jar:path.resolve(__dirname,'../server/bhash-compiler.jar'),mode:'managed'};
function task(operation,input,stdin){
    const r=spawnSync(process.execPath,[launcher,JSON.stringify({...request,operation,input})],
        {cwd:home,env,input:stdin,encoding:'utf8',timeout:480000,maxBuffer:1024*1024});
    assert.ifError(r.error);assert.equal(r.status,0,`${r.stdout}\n${r.stderr}`);return r;
}
try{
    assert.notEqual(spawnSync('clang-18',['--version'],{env}).status,0,'LLVM 18 must not be on PATH');
    const source=path.join(home,'नमस्ते.bhash');
    fs.writeFileSync(source,'कार्य मुख्य() -> शून्य { स्थिर नाम: पाठ = पाठपढ़ो(); स्थिर संख्या: पूर्णांक = पढ़ो(); लिखो(नाम, संख्या); }\n');
    const first=task('run',source,'भारत😀\n+४२\n');assert.match(first.stdout,/भारत😀 ४२/);
    const active=path.join(env.BHASH_TOOLCHAIN_HOME,'active.json');assert.ok(fs.existsSync(active));
    const receipt=fs.readFileSync(active,'utf8');
    const second=task('run',source,'फिर😀\n५\n');assert.match(second.stdout,/फिर😀 ५/);
    assert.ok(!second.stdout.includes('Downloading Bhash native SDK'),'second build must use cached SDK');
    assert.equal(fs.readFileSync(active,'utf8'),receipt);
    console.log('PASS Apple Silicon shipped launcher: verified first setup, cached reuse, Unicode paths and console input; no system LLVM 18. Apple Command Line Tools remain installed.');
}finally{fs.rmSync(home,{recursive:true,force:true});}
