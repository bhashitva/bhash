// SPDX-License-Identifier: Apache-2.0
// Runs the same launcher shipped in the VSIX, first online and then in --network none.
const fs=require('node:fs');const path=require('node:path');const os=require('node:os');
const {spawnSync}=require('node:child_process');const assert=require('node:assert/strict');
const {sdk}=require('../dist/native.js');
const offline=process.argv.includes('--offline');
for(const tool of ['clang','clang-18','opt','opt-18','gcc','cc','ld','ld.lld','readelf','llvm-readelf'])assert.notEqual(spawnSync('sh',['-c','command -v "$1"','sh',tool]).status,0,`unexpected ${tool}`);
assert.ok(!fs.existsSync('/usr/include/stdio.h'));
for(const pkg of ['libc6-dev','libgcc-13-dev','linux-libc-dev','clang-18','llvm-18','binutils'])assert.ok(!spawnSync('dpkg-query',['-W','-f=${Status}',pkg],{encoding:'utf8'}).stdout.includes('install ok installed'),`unexpected ${pkg}`);
const home=fs.mkdtempSync(path.join(os.tmpdir(),'bhash native भारत '));
const launcher=path.resolve(__dirname,'../dist/native-task.js');
const request={java:'java',jar:'/repo/target/bhash-compiler-0.1.0-SNAPSHOT.jar',mode:'managed'};
const active=path.join(process.env.BHASH_TOOLCHAIN_HOME,'active.json');
function task(operation,input,stdin){
    const r=spawnSync(process.execPath,[launcher,JSON.stringify({...request,operation,input})],{cwd:home,input:stdin,encoding:'utf8',timeout:360000,maxBuffer:1024*1024});
    assert.ifError(r.error);assert.equal(r.status,0,`${r.stdout}\n${r.stderr}`);return r;
}
try {
    if(!offline)assert.ok(!fs.existsSync(active),'first setup must start without an SDK');
    else assert.ok(fs.existsSync(active),'offline test must reuse installed SDK');
    const hello=path.join(home,'hello.bhash');fs.copyFileSync('/repo/examples/native/hello.bhash',hello);
    const build=task('build',hello);console.log(build.stdout);
    assert.equal(build.stdout.includes('Downloading Bhash native SDK'),!offline);
    const exe=spawnSync(path.join(home,'hello-native'),[],{encoding:'utf8'});
    assert.equal(exe.status,0);assert.equal(exe.stdout,'नमस्ते, दुनिया!\n१\nबीच ४\n९\n');
    const consoleFile=path.join(home,'console.bhash');
    fs.writeFileSync(consoleFile,'कार्य मुख्य() -> शून्य { लिखो(पाठपढ़ो()); लिखो(पढ़ो()); }\n');
    const run=task('run',consoleFile,'भारत😀\n42\n');
    assert.equal(run.stdout,'भारत😀\n४२\n');
    const status=JSON.parse(spawnSync('java',['-jar',request.jar,'native','toolchain','status','--json'],{encoding:'utf8'}).stdout);
    assert.equal(status.archiveSha256,sdk.sha256);task('check');
    console.log(`PASS native VSIX launcher: ${offline?'offline cache reuse':'first-use permanent GitHub asset setup'}, executable build/run, Unicode paths and interactive text/integer stdin; no system LLVM or C development packages.`);
} finally {fs.rmSync(home,{recursive:true,force:true});}
