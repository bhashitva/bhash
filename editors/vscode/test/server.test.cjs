// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {spawn}=require('node:child_process');
const {EventEmitter}=require('node:events');
const jar=path.resolve(__dirname,'../../../target/bhash-compiler-0.1.0-SNAPSHOT.jar');
function transport(){
    const process=spawn('java',['-Dfile.encoding=UTF-8','-jar',jar,'language-server'],{stdio:['pipe','pipe','pipe']});
    const events=new EventEmitter(),pending=new Map(),messages=[];let bytes=Buffer.alloc(0),id=0,stderr='';
    process.stderr.on('data',d=>stderr+=d);
    process.stdout.on('data',data=>{bytes=Buffer.concat([bytes,data]);for(;;){let header=bytes.indexOf('\r\n\r\n');if(header<0)return;let match=/Content-Length: (\d+)/i.exec(bytes.subarray(0,header).toString());assert.ok(match,'LSP stdout must contain only protocol frames');let length=Number(match[1]);if(bytes.length<header+4+length)return;let message=JSON.parse(bytes.subarray(header+4,header+4+length));bytes=bytes.subarray(header+4+length);if(message.id!==undefined){let callback=pending.get(message.id);pending.delete(message.id);callback?.(message);}else{messages.push(message);events.emit('message',message);}}});
    function send(method,params,requestId){const body=Buffer.from(JSON.stringify({jsonrpc:'2.0',method,params,...(requestId===undefined?{}:{id:requestId})}));process.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);process.stdin.write(body);}
    function request(method,params){return new Promise((resolve,reject)=>{const requestId=++id;const timer=setTimeout(()=>{pending.delete(requestId);reject(new Error(`Timeout: ${method}; stderr=${stderr}`));},20000);pending.set(requestId,message=>{clearTimeout(timer);message.error?reject(new Error(JSON.stringify(message.error))):resolve(message.result);});send(method,params,requestId);});}
    function wait(predicate){let cached=messages.find(predicate);if(cached)return Promise.resolve(cached);return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{events.off('message',listener);reject(new Error(`Notification timeout: ${JSON.stringify(messages.slice(-4))}; stderr=${stderr}`));},20000);function listener(message){if(predicate(message)){clearTimeout(timer);events.off('message',listener);resolve(message);}}events.on('message',listener);});}
    return {process,request,send,wait,messages};
}
function position(text,needle){const offset=text.indexOf(needle);assert.ok(offset>=0);const prefix=text.slice(0,offset);return {line:prefix.split('\n').length-1,character:offset-prefix.lastIndexOf('\n')-1};}
test('stdio LSP: unsaved cross-file analysis, alias navigation, Unicode positions and project isolation', {timeout:90000},async t=>{
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'bhash-lsp-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const a=path.join(root,'मेरा project'),b=path.join(root,'other');
    for(const directory of [a,b]){await fs.mkdir(path.join(directory,'src'),{recursive:true});await fs.writeFile(path.join(directory,'bhash.project'),'name=demo\nsources=src\n');}
    const lib=path.join(a,'src','math.bhash'),main=path.join(a,'src','main.bhash');
    const library='संकुल गणित;\nसार्वजनिक अभिलेख जोड़ा(अ: पूर्णांक, ब: पूर्णांक);\nसार्वजनिक कार्य दुगुना(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }\n';
    const program='संकुल उपयोग;\nआयात गणित.दुगुना उपनाम दो;\nआयात गणित.जोड़ा;\nकार्य मुख्य() -> शून्य {\n    स्थिर क: पूर्णांक = दो(३);\n    लिखो(क);\n}\n';
    await fs.writeFile(lib,library);await fs.writeFile(main,program);await fs.writeFile(path.join(b,'src','other.bhash'),library);
    const lsp=transport();t.after(()=>lsp.process.kill());
    const uri=file=>pathToFileURL(file).href, document={uri:uri(main)};
    const initialized=await lsp.request('initialize',{processId:process.pid,workspaceFolders:[{uri:uri(a),name:'a'},{uri:uri(b),name:'b'}],capabilities:{}});
    assert.equal(initialized.capabilities.definitionProvider,true);lsp.send('initialized',{});
    lsp.send('textDocument/didOpen',{textDocument:{...document,languageId:'bhash',version:1,text:program}});
    const diagnostic=async(file,version,predicate)=>lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&m.params.uri===uri(file)&&(version===undefined||m.params.version===version)&&predicate(m.params.diagnostics));
    await diagnostic(main,1,ds=>ds.length===0);
    assert.equal(await lsp.request('bhash/projectInput',document),path.join(a,'bhash.project'));
    const outside=path.join(a,'outside.bhash');await fs.writeFile(outside,'वर्ग अलग {}');
    assert.equal(await lsp.request('bhash/projectInput',{uri:uri(outside)}),outside);
    const defs=await lsp.request('textDocument/definition',{textDocument:document,position:position(program,'दो(३)')});
    assert.equal(defs.length,1);assert.equal(defs[0].uri,uri(lib));assert.equal(defs[0].range.start.line,2);
    const completion=await lsp.request('textDocument/completion',{textDocument:document,position:position(program,'दो(३)')});
    assert.ok(completion.some(item=>item.label.startsWith('स्थिर')));assert.ok(completion.some(item=>item.label==='दो'));assert.ok(completion.some(item=>item.label==='जोड़ा'));
    const symbols=await lsp.request('textDocument/documentSymbol',{textDocument:document});assert.equal(symbols[0].name,'मुख्य');
    assert.ok((await lsp.request('workspace/symbol',{query:'दुगुना'})).length===2,'multi-root workspace indexes independent projects');
    const hover=await lsp.request('textDocument/hover',{textDocument:document,position:position(program,'दो(३)')});assert.ok(hover.contents.value.includes('पूर्णांक'));
    lsp.send('textDocument/didOpen',{textDocument:{uri:uri(lib),languageId:'bhash',version:1,text:library}});
    const changed=library.replace('-> पूर्णांक { लौटाओ अंक * २; }','-> पाठ { लौटाओ "बदल गया"; }');
    lsp.send('textDocument/didChange',{textDocument:{uri:uri(lib),version:2},contentChanges:[{text:changed}]});
    await diagnostic(main,1,ds=>ds.some(d=>/Cannot|Expected|assign|type/i.test(d.message)));
    assert.equal(await fs.readFile(lib,'utf8'),library,'analysis must not save editor buffers');
    lsp.send('textDocument/didChange',{textDocument:{uri:uri(lib),version:3},contentChanges:[{text:library}]});
    const unicode='कार्य मुख्य() -> शून्य { लिखो("😀"); लिखो(अज्ञात); }';
    lsp.send('textDocument/didChange',{textDocument:{...document,version:2},contentChanges:[{text:unicode}]});
    const found=await diagnostic(main,2,ds=>ds.some(d=>d.message.includes('अज्ञात')));
    assert.deepEqual(found.params.diagnostics.find(d=>d.message.includes('अज्ञात')).range.start,position(unicode,'अज्ञात'));
    lsp.send('textDocument/didChange',{textDocument:{...document,version:4},contentChanges:[{text:program}]});
    lsp.send('textDocument/didChange',{textDocument:{...document,version:3},contentChanges:[{text:unicode}]});
    await diagnostic(main,4,ds=>ds.length===0);
    const extra=path.join(a,'src','new.bhash');
    lsp.send('textDocument/didOpen',{textDocument:{uri:uri(extra),languageId:'bhash',version:1,text:'संकुल गणित; सार्वजनिक वर्ग नया नाम {}'}});
    await diagnostic(extra,1,ds=>ds.length>0);
    lsp.send('textDocument/didChange',{textDocument:{uri:uri(extra),version:2},contentChanges:[{text:'संकुल गणित; सार्वजनिक वर्ग अतिरिक्त {}'}]});
    await diagnostic(extra,2,ds=>ds.length===0);
    assert.equal((await lsp.request('workspace/symbol',{query:'अतिरिक्त'})).length,1);
    lsp.send('textDocument/didClose',{textDocument:{uri:uri(extra)}});
    await lsp.request('textDocument/completion',{textDocument:document,position:{line:0,character:0}});
    assert.equal((await lsp.request('workspace/symbol',{query:'अतिरिक्त'})).length,0);
    // No मुख्य is required for a library-only project; ordinary builds still require it.
    assert.ok(lsp.messages.filter(m=>m.params?.uri===uri(path.join(b,'src','other.bhash'))).every(m=>m.params.diagnostics.length===0));
    await lsp.request('shutdown',null);lsp.send('exit');lsp.process.stdin.end();
});
