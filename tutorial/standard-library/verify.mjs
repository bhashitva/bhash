// SPDX-License-Identifier: Apache-2.0
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import assert from 'node:assert/strict';
const directory=path.dirname(fileURLToPath(import.meta.url));
const root=path.resolve(directory,'../..');
const read=file=>fs.readFileSync(file,'utf8').replaceAll('\r\n','\n');
const catalog=read(path.join(root,'src/main/resources/dev/bhash/stdlib/api.txt')).split('\n').filter(line=>line&&!line.startsWith('#'));
const coverage=JSON.parse(read(path.join(directory,'coverage.json')));
assert.deepEqual(coverage.flatMap(item=>item.signatures).sort(),[...catalog].sort(),'Update the tutorial when a library signature changes.');
const names=new Set();
for(const item of coverage){
 const key=item.owner+'.'+item.name;
 assert.ok(!names.has(key),'Duplicate reference: '+key); names.add(key);
 const document=read(path.join(directory,item.document));
 const section=document.split(`<a id="${item.anchor}"></a>`)[1]?.split('<a id=')[0];
 assert.ok(section,'Missing section: '+key);
 assert.ok(section.includes(`## ${item.name} (`),'Put the English name at the beginning: '+key);
 const code=section.match(/```bhash\n([\s\S]*?)\n```/)?.[1];
 assert.ok(code?.includes(key+'('),'Missing invocation: '+key);
 const source=read(path.join(directory,item.example));
 assert.ok(source.includes(code.split('\n').map(line=>'        '+line).join('\n')),'Snippet differs from the runnable example: '+key);
 for(const signature of item.signatures){
  const [owner,name,,result,parameters]=signature.split('|');
  assert.ok(section.includes(`${owner}.${name}(${parameters}) → ${result}`),'Missing signature: '+signature);
 }
}
for(const folder of [path.join(root,'tutorial'),directory]){
 for(const name of fs.readdirSync(folder).filter(name=>name.endsWith('.md'))){
  for(const match of read(path.join(folder,name)).matchAll(/\]\(([^)]+)\)/g)){
   const target=match[1].split('#')[0];
   if(!target||/^[a-z]+:\/\//i.test(target))continue;
   assert.ok(fs.existsSync(path.resolve(folder,target)),`Broken link in ${name}: ${target}`);
  }
 }
}
const narrativePrograms=[];
for(const fixture of JSON.parse(read(path.join(directory,'full-programs.json')))){
 const programs=[...read(path.join(directory,fixture.document)).matchAll(/```bhash\n([\s\S]*?)\n```/g)].map(match=>match[1]);
 assert.equal(programs.length,fixture.outputs.length,'Update full-program fixtures for '+fixture.document);
 programs.forEach((source,index)=>narrativePrograms.push({name:fixture.document.replace('.md','')+'-'+(index+1),source,expected:fixture.outputs[index]}));
}
console.log(`PASS reference coverage: ${coverage.length} functions, ${catalog.length} signatures; snippets and local links match.`);
if(process.argv.includes('--coverage-only'))process.exit(0);
const targetAt=process.argv.indexOf('--target');
const target=targetAt<0?'jvm':process.argv[targetAt+1];
assert.ok(['jvm','clr'].includes(target),'Use --target jvm or --target clr.');
const compiler=path.resolve(process.env.BHASH_COMPILER||path.join(root,'target/bhash-compiler-0.1.0-SNAPSHOT.jar'));
assert.ok(fs.existsSync(compiler),'Build the compiler first or set BHASH_COMPILER.');
const java=process.env.BHASH_JAVA||'java';
fs.mkdirSync(path.join(root,'target'),{recursive:true});
const scratch=fs.mkdtempSync(path.join(root,'target','tutorial-check-'));
fs.mkdirSync(path.join(scratch,'target/tutorial-files'),{recursive:true});
function execute(command,args,cwd,timeout=180000){
 const result=spawnSync(command,args,{cwd,encoding:'utf8',timeout,windowsHide:true,maxBuffer:8*1024*1024});
 assert.ifError(result.error);
 assert.equal(result.status,0,`${command} ${args.join(' ')}\n${result.stdout}\n${result.stderr}`);
 return result.stdout.replaceAll('\r\n','\n');
}
const examples=fs.readdirSync(path.join(directory,'examples')).filter(name=>name.endsWith('.bhash')).sort();
for(const example of examples){
 const name=example.replace('.bhash','');
 const output=path.join(scratch,target==='jvm'?name+'.jar':name+'-clr');
 execute(java,['-jar',compiler,'compile',path.join(directory,'examples',example),'--target',target,'-o',output],root);
 const actual=target==='jvm'?execute(java,['-jar',output],scratch):execute(process.env.BHASH_DOTNET||'dotnet',[path.join(output,'BhashProgram.dll')],scratch);
 const expected=read(path.join(directory,'examples',name+'.expected.txt'));
 assert.equal(actual,expected,`${target}: ${example} output mismatch`);
 console.log(`PASS ${target}: ${example}`);
}
for(const program of narrativePrograms){
 const source=path.join(scratch,program.name+'.bhash');fs.writeFileSync(source,program.source+'\n');
 const output=path.join(scratch,target==='jvm'?program.name+'.jar':program.name+'-clr');
 execute(java,['-jar',compiler,'compile',source,'--target',target,'-o',output],root);
 const actual=target==='jvm'?execute(java,['-jar',output],scratch):execute(process.env.BHASH_DOTNET||'dotnet',[path.join(output,'BhashProgram.dll')],scratch);
 assert.equal(actual,program.expected,program.name+' output mismatch');console.log(`PASS ${target}: narrative ${program.name}`);
}
console.log(`All ${examples.length+narrativePrograms.length} tutorial programs passed on ${target}. Files stayed in ${path.relative(root,scratch)}.`);
