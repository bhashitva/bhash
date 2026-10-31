// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const os=require('node:os');
const fs=require('node:fs/promises');
const {requireDebugHost,gdbSetup,removeDebugBuild,prepareNativeDebug}=require('../dist/native-debug.js');

test('debugging supports Windows x64 and Linux/WSL with an explicit macOS diagnostic',()=>{
    requireDebugHost('linux','x64');requireDebugHost('win32','x64');
    for(const [platform,arch] of [['win32','arm64'],['darwin','arm64'],['linux','arm64']])
        assert.throws(()=>requireDebugHost(platform,arch),/Windows x64 or Linux/);
});
test('GDB setup preserves breakpoints and quotes paths while disabling inferior calls',()=>{
    const commands=gdbSetup('/space हिन्दी/quote"/bhash_gdb.py');
    assert.ok(commands.every(c=>c.ignoreFailures===false));
    assert.ok(commands.some(c=>c.text==='set may-call-functions off'));
    assert.ok(commands.some(c=>c.text==='-enable-pretty-printing'));
    assert.ok(commands.some(c=>c.text==='-enable-frame-filters'));
    assert.ok(!commands.some(c=>c.text==='set may-write-memory off'),'software breakpoints need memory writes');
    assert.ok(commands.some(c=>c.text.startsWith('python exec(compile(open("/space हिन्दी/quote\\\"/bhash_gdb.py"')));
    assert.throws(()=>gdbSetup('/tmp/file\ncall evil'),/newline/);
});
test('cleanup refuses workspace, temp root and arbitrary launch outputs',async()=>{
    for(const name of [process.cwd(),os.tmpdir(),path.join(os.tmpdir(),'other-program')])
        await assert.rejects(removeDebugBuild(name),/Refusing/);
});
test('debug build uses managed setup, source debug flags, and removes partial failed output',
    {skip:process.platform!=='linux'},async()=>{
    const calls=[];let program;
    const options={java:'java',jar:'/compiler path/compiler.jar',mode:'managed',gdb:'gdb',input:'/source हिन्दी/main.bhash',cwd:'/source हिन्दी',extensionRoot:'/extension path'};
    const dependencies={probe:async()=>{calls.push('gdb');},ensure:async()=>{calls.push('sdk');},run:async(command,args,env,signal,cwd)=>{
        calls.push(args);assert.equal(cwd,options.cwd);assert.equal(env.BHASH_NATIVE_TOOLCHAIN,'managed');
        assert.ok(args.includes('--debug'));assert.ok(args.includes('--target'));assert.ok(args.includes(options.input));
        program=args.at(-1);await fs.writeFile(program,'#!/bin/sh\nexit 0\n',{mode:0o700});return '';
    }};
    const result=await prepareNativeDebug(options,()=>{},undefined,dependencies);
    assert.deepEqual(calls.slice(0,2),['gdb','sdk']);assert.equal(result.program,program);
    await removeDebugBuild(result.directory);await assert.rejects(fs.stat(program));
    dependencies.run=async(command,args)=>{program=args.at(-1);await fs.writeFile(program,'partial');throw Error('compile failed');};
    await assert.rejects(prepareNativeDebug(options,()=>{},undefined,dependencies),/compile failed/);
    await assert.rejects(fs.stat(path.dirname(program)));
    const abort=new AbortController();abort.abort();calls.length=0;
    await assert.rejects(prepareNativeDebug(options,()=>{},abort.signal,dependencies));
    assert.deepEqual(calls,[],'cancelled launch must not probe, install an SDK or build');
});


test('Windows debug preparation passes Unicode argv through Java envelope and selects a fresh exe',
 {skip:process.platform!=='win32'},async()=>{
 const base=path.join(os.tmpdir(),'source हिन्दी');let built;
 const options={java:'java',jar:path.join(base,'compiler.jar'),mode:'managed',gdb:'',input:path.join(base,'main.bhash'),cwd:base,extensionRoot:base};
 const result=await prepareNativeDebug(options,()=>{},undefined,{
  debugger:async()=>path.join(base,'gdb.exe'),probe:async()=>{},ensure:async()=>{},
  run:async(command,args,env,signal,cwd)=>{
   assert.equal(cwd,base);
   const envelope=JSON.parse(Buffer.from(args[args.indexOf('--utf8-args')+1],'base64').toString('utf8'));
   assert.ok(envelope.args.includes(options.input));assert.ok(envelope.args.includes('--debug'));
   built=envelope.args.at(-1);assert.equal(path.extname(built),'.exe');await fs.writeFile(built,'fixture');return '';
  }
 });
 assert.equal(result.program,built);assert.equal(result.gdb,path.join(base,'gdb.exe'));
 await removeDebugBuild(result.directory);await assert.rejects(fs.stat(built));
});


test('GDB preflight tolerates a startup delayed beyond the old 15-second deadline',async()=>{
 const {probeGdb}=require('../dist/native-debug.js');const {execFile}=require('node:child_process');const {promisify}=require('node:util');
 const messages=[];
 await probeGdb(process.execPath,undefined,undefined,text=>messages.push(text),(_command,_args,options)=>
  promisify(execFile)(process.execPath,['-e',"setTimeout(()=>console.log('BHASH_GDB_READY:18.1:3'),16000)"],options));
 assert.ok(messages.some(text=>text.includes('Security software')));
});
test('GDB preflight cancellation stops a waiting process and invalid timeouts start nothing',async()=>{
 const {probeGdb}=require('../dist/native-debug.js');const {execFile}=require('node:child_process');const {promisify}=require('node:util');
 const abort=new AbortController();const timer=setTimeout(()=>abort.abort(),100);
 try {await assert.rejects(probeGdb(process.execPath,abort.signal,300,()=>{},(_command,_args,options)=>
  promisify(execFile)(process.execPath,['-e','setTimeout(()=>{},30000)'],options)),/cancelled/);}finally{clearTimeout(timer);}
 let called=false;
 await assert.rejects(probeGdb(process.execPath,undefined,601,()=>{},async()=>{called=true;return {stdout:''};}),/15 to 600/);
 assert.equal(called,false);
});
