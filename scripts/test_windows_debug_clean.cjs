// SPDX-License-Identifier: Apache-2.0
// Executed inside Windows Server Core with only Java, Node and the test Python mounted.
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const {ensureNative,compilerInvocation,capture,nativeEnvironment}=require('../editors/vscode/dist/native.js');
const {ensureDebugger}=require('../editors/vscode/dist/native-debug.js');
(async()=>{
 assert.equal(process.platform,'win32');
 for(const tool of ['clang.exe','gcc.exe','cl.exe','gdb.exe'])assert.notEqual(spawnSync('where.exe',[tool],{windowsHide:true}).status,0,'unexpected development tool: '+tool);
 const options={java:path.join(process.env.JAVA_HOME,'bin/java.exe'),jar:path.resolve('target/bhash-compiler-0.1.0-SNAPSHOT.jar'),mode:'managed'};
 await ensureNative(options,console.log);
 const gdb=await ensureDebugger(options,'',console.log);
 const before=await fs.readFile(path.join(process.env.BHASH_DEBUGGER_HOME,'active.json'),'utf8');
 // No download on the second setup, and no ambient Python installation is required by GDB.
 assert.equal(await ensureDebugger(options,'',console.log),gdb);
 assert.equal(await fs.readFile(path.join(process.env.BHASH_DEBUGGER_HOME,'active.json'),'utf8'),before);
 for(const script of ['test_native_inspection.py','test_native_debugger.py']){
  const result=spawnSync(process.env.BHASH_TEST_PYTHON,[path.join('scripts',script)],{
   env:{...process.env,BHASH_GDB:gdb,PYTHONHOME:'',PYTHONPATH:''},encoding:'utf8',windowsHide:true,timeout:300000,maxBuffer:4*1024*1024});
  console.log(result.stdout,result.stderr);assert.equal(result.status,0,String(result.error||script));
 }
 // A failed repair must leave the previously active debugger usable.
 const broken=path.resolve('target/bad-debugger.zip');await fs.writeFile(broken,'not an archive');
 const invoke=compilerInvocation(options.jar,['native','debugger','install','--archive',broken,'--sha256','0'.repeat(64),'--json']);
 await assert.rejects(capture(options.java,invoke.args,nativeEnvironment(options),undefined,invoke.cwd),/SHA-256 mismatch/);
 assert.equal(await fs.readFile(path.join(process.env.BHASH_DEBUGGER_HOME,'active.json'),'utf8'),before);
 assert.equal(await ensureDebugger(options,'',console.log),gdb);
 console.log('PASS clean Windows without WSL/LLVM/C development tools: debugger setup, reuse, failed repair, source stepping and exact GC root safety.');
})().catch(error=>{console.error(error);process.exitCode=1;});
