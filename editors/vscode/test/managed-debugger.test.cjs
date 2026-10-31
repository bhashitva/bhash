// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {ensureDebugger,windowsDebugger}=require('../dist/native-debug.js');
const options={java:'java',jar:require('node:path').resolve('compiler.jar'),mode:'managed'};
const ready={installed:true,archiveSha256:windowsDebugger.sha256,gdb:'C:\\Bhash हिन्दी\\debugger\\bin\\gdb.exe'};
function decode(args){return JSON.parse(Buffer.from(args[args.indexOf('--utf8-args')+1],'base64').toString('utf8')).args;}

test('Windows automatic debugger setup verifies and reuses an offline cache',async()=>{
 const calls=[];const run=async(command,args)=>{calls.push(decode(args));return JSON.stringify(ready);};
 assert.equal(await ensureDebugger(options,'',()=>{},undefined,run,'win32'),ready.gdb);
 assert.equal(calls.length,1);assert.deepEqual(calls[0],['native','debugger','status','--json']);
});
test('missing or damaged debugger installs only the pinned catalog and verifies activation',async()=>{
 for(const broken of [false,true]){
  const calls=[];const run=async(command,args)=>{const request=decode(args);calls.push(request);if(request[2]==='status'){if(broken)throw Error('checksum mismatch');return '{"installed":false}';}assert.ok(request.includes(windowsDebugger.url));assert.ok(request.includes(windowsDebugger.sha256));return JSON.stringify(ready);};
  assert.equal(await ensureDebugger(options,'',()=>{},undefined,run,'win32'),ready.gdb);assert.equal(calls.length,2);
 }
});
test('download errors and invalid activation fail without a fallback debugger',async()=>{
 let calls=0;const run=async()=>{if(++calls===1)return '{"installed":false}';throw Error('download failed');};
 await assert.rejects(ensureDebugger(options,'',()=>{},undefined,run,'win32'),/download failed/);
 await assert.rejects(ensureDebugger(options,'',()=>{},undefined,async()=>'{"installed":false}','win32'),/activation/);
});
test('configured debugger bypasses managed download; cancelled setup performs no work',async()=>{
 const fail=async()=>{throw Error('must not run');};
 assert.equal(await ensureDebugger(options,'C:\\tools\\gdb.exe',()=>{},undefined,fail,'win32'),'C:\\tools\\gdb.exe');
 assert.equal(await ensureDebugger(options,'',()=>{},undefined,fail,'linux'),'gdb');
 await assert.rejects(ensureDebugger(options,'gdb',()=>{},undefined,fail,'win32'),/absolute Windows/);
 const abort=new AbortController();abort.abort();await assert.rejects(ensureDebugger(options,'',()=>{},abort.signal,fail,'win32'));
});


test('slow-start setting reaches the installer and its parent deadline allows setup overhead',async()=>{
 const custom={...options,debuggerStartupTimeoutSeconds:600};const requests=[];
 const run=async(command,args,env,signal,cwd,timeout)=>{
  const request=decode(args);requests.push(request);
  assert.equal(env.BHASH_DEBUGGER_STARTUP_TIMEOUT_SECONDS,'600');assert.ok(timeout>=900000);
  return request[2]==='status'?'{"installed":false}':JSON.stringify(ready);
 };
 assert.equal(await ensureDebugger(custom,'',()=>{},undefined,run,'win32'),ready.gdb);assert.equal(requests.length,2);
});
