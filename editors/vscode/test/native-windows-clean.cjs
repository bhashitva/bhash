// SPDX-License-Identifier: Apache-2.0
// Run inside Windows Server Core, not on a hosted runner with developer tools.
const fs=require('node:fs');const path=require('node:path');const os=require('node:os');
const {spawnSync}=require('node:child_process');const assert=require('node:assert/strict');
const repo=process.env.BHASH_TEST_REPO || 'C:\\repo';
const {windowsSdk:sdk,compilerInvocation}=require(path.join(repo,'editors/vscode/dist/native.js'));
const offline=process.argv.includes('--offline');
assert.equal(process.platform,'win32');assert.equal(process.arch,'x64');
const forbidden=['clang.exe','clang-18.exe','opt.exe','cl.exe','gcc.exe','link.exe','ld.lld.exe','llvm-readobj.exe','dotnet.exe'];
for(const tool of forbidden)assert.notEqual(spawnSync('where.exe',[tool]).status,0,`unexpected development/runtime tool: ${tool}`);
// Server Core ships the Windows WSL launcher stub even without a Linux environment.
// Check that no Linux command can run; the presence of that OS stub is not WSL setup.
const wsl=spawnSync('wsl.exe',['--exec','/bin/true'],{timeout:10000});
if(wsl.error)assert.equal(wsl.error.code,'ENOENT');
else assert.notEqual(wsl.status,0,'unexpected runnable WSL environment');

for(const folder of ['C:\\Program Files\\LLVM','C:\\Program Files (x86)\\Windows Kits','C:\\Program Files\\Microsoft Visual Studio'])assert.ok(!fs.existsSync(folder),`unexpected development installation: ${folder}`);
const home=fs.mkdtempSync(path.join(os.tmpdir(),'bhash native भारत '));
const launcher=path.join(repo,'editors/vscode/dist/native-task.js');
const java=process.env.BHASH_TEST_JAVA || 'C:\\java\\bin\\java.exe';
const jar=path.join(home,'compiler.jar');fs.copyFileSync(path.join(repo,'target/bhash-compiler-0.1.0-SNAPSHOT.jar'),jar);
const request={java,jar,mode:'managed'};
function copyCacheContents(from,to){
    fs.mkdirSync(to,{recursive:true});
    for(const name of fs.readdirSync(from)){
        const source=path.join(from,name),destination=path.join(to,name);
        if(fs.statSync(source).isDirectory())copyCacheContents(source,destination);
        else fs.writeFileSync(destination,fs.readFileSync(source));
    }
}
const env={...process.env,BHASH_GC_STRESS:'1',BHASH_GC_STATS:'1'};
function invoke(executable,args,input,extra={},cwd=home){
    const r=spawnSync(executable,args,{cwd,env:{...env,...extra},input,encoding:'utf8',timeout:360000,maxBuffer:4*1024*1024});
    assert.ifError(r.error);assert.equal(r.status,0,`${r.stdout}\n${r.stderr}`);return r;
}
function cli(args){const request=compilerInvocation(jar,args);return invoke(java,request.args,undefined,{},request.cwd);}
function task(operation,input,stdin){return invoke(process.execPath,[launcher,JSON.stringify({...request,operation,input})],stdin);}
try {
    const active=path.join(process.env.BHASH_TOOLCHAIN_HOME,'active.json');
    const archive=process.env.BHASH_TEST_SDK_ARCHIVE;
    if(!offline){
        assert.ok(!fs.existsSync(active),'first setup must begin with an empty cache');
        if(archive)cli(['native','toolchain','install','--archive',archive,'--sha256',sdk.sha256,'--json']);
    }else assert.ok(fs.existsSync(active),'offline pass reuses the cache');
    const hello=path.join(home,'hello.bhash');fs.copyFileSync(path.join(repo,'examples/native/hello.bhash'),hello);
    const build=task('build',hello);console.log(build.stdout);
    assert.equal(build.stdout.includes('Downloading Bhash native SDK'),!offline&&!archive);
    const exe=path.join(home,'hello-native.exe');
    const result=invoke(exe,[]);assert.equal(result.stdout,'नमस्ते, दुनिया!\n१\nबीच ४\n९\n');assert.match(result.stderr,/live=0/);
    const report=JSON.parse(cli(['native','inspect',exe,'--json','--require-runtime-match']).stdout);
    assert.equal(report.target,'x86_64-w64-windows-gnu');
    assert.ok(report.libraries.every(x=>x==='kernel32.dll'||x==='shell32.dll'||x==='ucrtbase.dll'||x.startsWith('api-ms-win-crt-')));
    const consoleFile=path.join(home,'console.bhash');
    fs.writeFileSync(consoleFile,'कार्य मुख्य() -> शून्य { लिखो(पाठपढ़ो()); लिखो(पढ़ो()); }\n');
    assert.equal(task('run',consoleFile,'भारत😀\r\n+४२\r\n').stdout,'भारत😀\n४२\n');
    for(const directory of ["native-file-example","native-byte-example"])fs.mkdirSync(path.join(home,"target",directory),{recursive:true});
    // Exercise collector, callbacks/unwinding, object layout and platform file APIs.
    for(const name of ['heap-text','exceptions','generics','records','static-members','json-text','bytes','files','time','math-random']){
        const source=path.join(repo,'examples/native',name+'.bhash');if(!fs.existsSync(source))throw Error('Missing coverage example: '+source);
        const program=path.join(home,name+'.exe');cli(['compile',source,'--target','native','-o',program]);
        const run=invoke(program,[]);assert.match(run.stderr,/live=0/);assert.match(run.stderr,/allocations=[1-9]/);
    }
    const sqlSource=path.join(repo,'examples/sqlite/values.bhash'),sqlExe=path.join(home,'sqlite.exe');
    cli(['compile',sqlSource,'--target','native','-o',sqlExe]);
    const sqlRun=invoke(sqlExe,[],undefined,{PATH:path.join(process.env.SystemRoot,'System32')});
    assert.equal(sqlRun.stdout,'3.54.1\n६ ६ अंक\n४२ १.२५ सत्य भारत😀\nबाइटबफ़र ० सत्य\nरिक्त\n०\n');assert.match(sqlRun.stderr,/live=0/);
    const sqlReport=JSON.parse(cli(['native','inspect',sqlExe,'--json','--require-runtime-match']).stdout);
    assert.equal(sqlReport.buildInfo.sqlite_version,'3.54.1');assert.ok(!sqlReport.libraries.some(x=>/sqlite|jvm|clr/i.test(x)));
    console.log('PASS clean automatic setup, Unicode input/files, forced-GC programs and PE dependency audit.');
    const zip=path.join(home,'hello.zip');cli(['native','package',hello,'--name','hello','--version','1','--debug','-o',zip]);
    assert.ok(fs.statSync(zip).size>0);
    const relocated=path.join(home,'relocated source'),cache=path.join(home,'relocated SDK');
    fs.mkdirSync(relocated);const source2=path.join(relocated,'hello.bhash');fs.copyFileSync(hello,source2);
    const originalCache=env.BHASH_TOOLCHAIN_HOME;
    // Recreate regular files from bytes, without cloning Docker reparse-point metadata.
    const receiptPath=path.join('installations',JSON.parse(fs.readFileSync(active,'utf8')).directory,'.installed.json');
    assert.ok(fs.existsSync(path.join(originalCache,receiptPath)),'original cache must contain its installation receipt');
    copyCacheContents(originalCache,cache);
    assert.ok(fs.existsSync(path.join(cache,'active.json')),'relocated cache must contain its active receipt');
    assert.ok(fs.existsSync(path.join(cache,receiptPath)),'relocated cache must contain its installation receipt');
    env.BHASH_TOOLCHAIN_HOME=cache;
    const zip2=path.join(home,'hello-again.zip');cli(['native','package',source2,'--name','hello','--version','1','--debug','-o',zip2]);
    assert.deepEqual(fs.readFileSync(zip2),fs.readFileSync(zip),'debug release changes across source and SDK cache relocation');
    env.BHASH_TOOLCHAIN_HOME=originalCache;
    const before=fs.readFileSync(active,'utf8');
    const bad=spawnSync(java,['-jar',jar,'native','toolchain','install','--archive',archive||zip,'--sha256','0'.repeat(64)],{encoding:'utf8'});
    assert.notEqual(bad.status,0);assert.equal(fs.readFileSync(active,'utf8'),before);
    task('check');cli(['native','toolchain','verify','--json']);
    // Launch from a directory without Java, Node, the SDK or any compiler in PATH.
    const clean=invoke(exe,[],undefined,{PATH:path.join(process.env.SystemRoot,'System32')});assert.equal(clean.stdout,result.stdout);
    if(process.env.BHASH_TEST_DEPLOY_DIR){fs.mkdirSync(process.env.BHASH_TEST_DEPLOY_DIR,{recursive:true});fs.copyFileSync(exe,path.join(process.env.BHASH_TEST_DEPLOY_DIR,'hello.exe'));}
    console.log(`PASS Windows native: ${offline?'offline cache':'first setup'}, Unicode paths/input, forced GC, DLL audit, package, rollback and standalone execution. No WSL/LLVM/C development packages installed.`);
}finally{fs.rmSync(home,{recursive:true,force:true});}
