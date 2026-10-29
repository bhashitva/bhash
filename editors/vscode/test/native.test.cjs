// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {linuxSdk:sdk,windowsSdk,ensureNative:ensure,nativeEnvironment,requireNativeHost,capture}=require('../dist/native.js');
const ensureNative=(options,log,signal,repair,run)=>ensure(options,log,signal,repair,run,'linux');
const options={java:'java with spaces',jar:'/compiler हिन्दी.jar',mode:'managed'};
function fake({installed=false,libc='glibc 2.35',busy=false,fail=false,wrong=false}={}) {
    const calls=[];let active=installed;
    const run=async(command,args,env)=>{
        calls.push({command,args,env});
        if(command==='getconf')return libc;
        if(args.includes('status'))return JSON.stringify({installed:active,archiveSha256:active?(wrong?'0'.repeat(64):sdk.sha256):undefined});
        if(args.includes('install')) {
            if(fail)throw Error('SHA-256 mismatch');
            active=true;
            if(busy){busy=false;throw Error('[BH9110] Another toolchain installation is in progress');}
            return '{}';
        }
        return '{}';
    };
    return {calls,run};
}
test('Linux/WSL host supported, unsupported hosts give actionable guidance',()=>{
    requireNativeHost('linux','x64');requireNativeHost('win32','x64');
    for(const [platform,arch] of [['win32','arm64'],['darwin','arm64'],['linux','arm64']])assert.throws(()=>requireNativeHost(platform,arch),/WSL/);
});
test('managed mode ignores ambient tool overrides and reuses CLI cache environment',()=>{
    const base={PATH:'path',BHASH_CLANG:'bad',BHASH_LLVM_OPT:'bad',BHASH_READELF:'bad',BHASH_TOOLCHAIN_HOME:'cache'};
    assert.deepEqual(nativeEnvironment(options,base),{PATH:'path',BHASH_TOOLCHAIN_HOME:'cache',BHASH_NATIVE_TOOLCHAIN:'managed'});
    assert.equal(base.BHASH_CLANG,'bad');
    assert.equal(nativeEnvironment({...options,mode:'system'},base).BHASH_CLANG,'bad');
    assert.equal(nativeEnvironment({...options,home:path.resolve('cache')},base).BHASH_TOOLCHAIN_HOME,path.resolve('cache'));
    assert.throws(()=>nativeEnvironment({...options,home:'relative'}),/absolute/);
    assert.throws(()=>nativeEnvironment({...options,mode:'wrong'}),/managed or system/);
});
test('first use installs only trusted versioned HTTPS URL with bundled SHA',async()=>{
    const f=fake();await ensureNative(options,()=>{},undefined,false,f.run);
    const install=f.calls.find(c=>c.args.includes('install'));assert.ok(install);
    assert.deepEqual(install.args,['-Dfile.encoding=UTF-8','-jar',options.jar,'native','toolchain','install','--url',sdk.url,'--sha256',sdk.sha256,'--json']);
    assert.match(sdk.url,/^https:\/\/github.com\/bhashitva\/bhash\/releases\/download\/native-sdk-1.1.0\//);
    assert.equal(f.calls.filter(c=>c.args.includes('status')).length,2);
});
test('cached SDK avoids network and repair explicitly reinstalls',async()=>{
    const f=fake({installed:true});await ensureNative(options,()=>{},undefined,false,f.run);
    assert.ok(!f.calls.some(c=>c.args.includes('install')));
    await ensureNative(options,()=>{},undefined,true,f.run);
    assert.equal(f.calls.filter(c=>c.args.includes('install')).length,1);
});
test('different SDK version is upgraded and activated hash checked',async()=>{
    const f=fake({installed:true,wrong:true});await assert.rejects(ensureNative(options,()=>{},undefined,false,f.run),/activation/);
    assert.ok(f.calls.some(c=>c.args.includes('install')));
});
test('concurrent installation is reused after lock wait',async()=>{
    const f=fake({busy:true});await ensureNative(options,()=>{},undefined,false,f.run);
    assert.equal(f.calls.filter(c=>c.args.includes('install')).length,1);
});
test('download failure propagates without compile or hidden fallback',async()=>{
    const f=fake({fail:true});await assert.rejects(ensureNative(options,()=>{},undefined,false,f.run),/SHA-256/);
    assert.ok(!f.calls.some(c=>c.args.includes('doctor')));
});
test('Ubuntu 22.04 and newer hosts accept the managed SDK',async()=>{
    for(const libc of ['glibc 2.35','glibc 2.39','glibc 2.41']){
        const f=fake({libc});await ensureNative(options,()=>{},undefined,false,f.run);
        assert.equal(f.calls.filter(c=>c.args.includes('install')).length,1);
    }
});
test('older glibc and musl fail before download',async()=>{
    for(const libc of ['glibc 2.34','glibc 2.31','musl 1.2.5']){
        const f=fake({libc});await assert.rejects(ensureNative(options,()=>{},undefined,false,f.run),/glibc 2.35/);assert.equal(f.calls.length,1);
    }
});
test('system mode never downloads an SDK',async()=>{
    const f=fake();await ensureNative({...options,mode:'system'},()=>{},undefined,false,f.run);
    assert.equal(f.calls.length,1);assert.ok(f.calls[0].args.includes('doctor'));
});
test('unsupported custom compiler status is actionable',async()=>{
    await assert.rejects(ensureNative(options,()=>{},undefined,false,async(command)=>command==='getconf'?'glibc 2.39':'{}'),/bundled compiler/);
});
test('setup cancellation terminates and reaps child process',async()=>{
    const controller=new AbortController();const result=capture(process.execPath,['-e','setInterval(()=>{},1000)'],process.env,controller.signal);
    setTimeout(()=>controller.abort(),150);await assert.rejects(result,/cancelled/);
});
test('bounded process output prevents unbounded memory use',async()=>{
    await assert.rejects(capture(process.execPath,['-e','process.stdout.write("x".repeat(70000))'],process.env),/safety limit/);
});
test('compiler process preserves argument boundaries and Unicode',async()=>{
    const args=['space value','हिन्दी','$(not-a-shell)'];
    assert.deepEqual(JSON.parse(await capture(process.execPath,['-e','console.log(JSON.stringify(process.argv.slice(1)))',...args],process.env)),args);
});

test('Windows managed setup selects Windows asset and never invokes getconf',async()=>{
    const calls=[];let active=false;
    await ensure(options,()=>{},undefined,false,async(command,args)=>{
        calls.push({command,args});assert.notEqual(command,'getconf');
        if(args.includes('status'))return JSON.stringify({installed:active,archiveSha256:active?windowsSdk.sha256:undefined});
        if(args.includes('install')){assert.ok(args.includes(windowsSdk.url));assert.ok(args.includes(windowsSdk.sha256));active=true;}
        return '{}';
    },'win32');
    assert.equal(calls.filter(c=>c.args.includes('install')).length,1);
    assert.match(windowsSdk.sha256,/^[a-f0-9]{64}$/);
});
