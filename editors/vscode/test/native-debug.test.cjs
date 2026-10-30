// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const os=require('node:os');
const fs=require('node:fs/promises');
const {requireDebugHost,gdbSetup,removeDebugBuild,prepareNativeDebug}=require('../dist/native-debug.js');

test('debugging is Linux/WSL-only, independent of ordinary native host support',()=>{
    requireDebugHost('linux','x64');
    for(const [platform,arch] of [['win32','x64'],['darwin','arm64'],['linux','arm64']])
        assert.throws(()=>requireDebugHost(platform,arch),/Linux x86-64.*WSL/);
});
test('GDB setup preserves breakpoints and quotes paths while disabling inferior calls',()=>{
    const commands=gdbSetup('/space हिन्दी/quote"/bhash_gdb.py');
    assert.ok(commands.every(c=>c.ignoreFailures===false));
    assert.ok(commands.some(c=>c.text==='set may-call-functions off'));
    assert.ok(commands.some(c=>c.text==='-enable-pretty-printing'));
    assert.ok(commands.some(c=>c.text==='-enable-frame-filters'));
    assert.ok(!commands.some(c=>c.text==='set may-write-memory off'),'software breakpoints need memory writes');
    assert.ok(commands.some(c=>c.text==='source "/space हिन्दी/quote\\\"/bhash_gdb.py"'));
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
    assert.deepEqual(calls,['gdb'],'cancelled launch must not install an SDK or build');
});
