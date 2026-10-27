// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {pathToFileURL,fileURLToPath}=require('node:url');
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
function sameUri(left,right){const normalize=value=>{const file=path.resolve(fileURLToPath(value));return process.platform==='win32'?file.toLowerCase():file;};return normalize(left)===normalize(right);}
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
    const diagnostic=async(file,version,predicate)=>lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri(file))&&(version===undefined||m.params.version===version)&&predicate(m.params.diagnostics));
    await diagnostic(main,1,ds=>ds.length===0);
    assert.equal(await lsp.request('bhash/projectInput',document),path.join(a,'bhash.project'));
    const outside=path.join(a,'outside.bhash');await fs.writeFile(outside,'वर्ग अलग {}');
    assert.equal(await lsp.request('bhash/projectInput',{uri:uri(outside)}),outside);
    const defs=await lsp.request('textDocument/definition',{textDocument:document,position:position(program,'दो(३)')});
    assert.equal(defs.length,1);assert.ok(sameUri(defs[0].uri,uri(lib)),`${defs[0].uri} != ${uri(lib)}`);assert.equal(defs[0].range.start.line,2);
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
    assert.ok(lsp.messages.filter(m=>m.params?.uri && sameUri(m.params.uri,uri(path.join(b,'src','other.bhash')))).every(m=>m.params.diagnostics.length===0));
    await lsp.request('shutdown',null);lsp.send('exit');lsp.process.stdin.end();
});

test('stdio LSP: member types, signatures, automatic import edits and coded multi-error diagnostics', {timeout:90000},async t=>{
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'bhash-intelligence-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    await fs.mkdir(path.join(root,'src'));
    await fs.writeFile(path.join(root,'bhash.project'),'name=intelligence\nsources=src\n');
    await fs.writeFile(path.join(root,'src/lib.bhash'),'संकुल गणित; सार्वजनिक अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक); सार्वजनिक कार्य जोड़(अ: पूर्णांक, ब: पूर्णांक) -> पूर्णांक { लौटाओ अ + ब; }');
    const main=path.join(root,'src/main.bhash'),uri=pathToFileURL(main).href,document={uri};
    const source='संकुल उपयोग;\nआयात गणित.जोड़;\nकार्य मुख्य() -> शून्य { स्थिर क: बिंदु = नया बिंदु(१,२); लिखो(क.अ, जोड़(१, २)); }';
    await fs.writeFile(main,source);
    const lsp=transport();t.after(()=>lsp.process.kill());
    const result=await lsp.request('initialize',{workspaceFolders:[{uri:pathToFileURL(root).href,name:'test'}],capabilities:{}});
    assert.deepEqual(result.capabilities.signatureHelpProvider.triggerCharacters,['(', ',']);
    lsp.send('initialized',{});lsp.send('textDocument/didOpen',{textDocument:{...document,languageId:'bhash',version:1,text:source}});
    const after=needle=>{const p=position(source,needle);return {...p,character:p.character+needle.length};};
    const items=await lsp.request('textDocument/completion',{textDocument:document,position:after('क: बिं')});
    const item=items.find(i=>i.label==='बिंदु'&&i.additionalTextEdits?.length);assert.ok(item,'unimported public type completion carries import edit');
    const diagnostic=await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri)&&m.params.version===1&&m.params.diagnostics.some(d=>d.code==='BH2002'));
    const actions=await lsp.request('textDocument/codeAction',{textDocument:document,range:diagnostic.params.diagnostics[0].range,context:{diagnostics:diagnostic.params.diagnostics}});
    assert.ok(actions.some(a=>a.kind==='quickfix'&&a.title==='आयात गणित.बिंदु'));
    const fixed=source.replace('आयात गणित.जोड़;','आयात गणित.जोड़;\nआयात गणित.बिंदु;');
    lsp.send('textDocument/didChange',{textDocument:{...document,version:2},contentChanges:[{text:fixed}]});
    const p=position(fixed,'क.अ');p.character+=2;
    const members=await lsp.request('textDocument/completion',{textDocument:document,position:p});assert.ok(members.some(i=>i.label==='अ'&&i.detail==='अ: पूर्णांक'));
    const call=position(fixed,'जोड़(१, ');call.character+='जोड़(१, '.length;
    const signature=await lsp.request('textDocument/signatureHelp',{textDocument:document,position:call});assert.equal(signature.activeParameter,1);assert.ok(signature.signatures[0].label.includes('ब: पूर्णांक'));
    const standard='आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { लिखो(पाठ्य.खोजो("भारत", "रत")); }';
    lsp.send('textDocument/didChange',{textDocument:{...document,version:3},contentChanges:[{text:standard}]});
    const stdPosition=position(standard,'पाठ्य.खोजो');stdPosition.character+='पाठ्य.'.length;
    const stdMembers=await lsp.request('textDocument/completion',{textDocument:document,position:stdPosition});
    assert.ok(stdMembers.some(i=>i.label==='बाँटो'&&i.detail.includes('सूची<पाठ>')),'bundled standard library indexed without a project dependency');
    const stdDefinition=await lsp.request('textDocument/definition',{textDocument:document,position:position(standard,'खोजो(')});
    assert.equal(stdDefinition.length,1);assert.ok(stdDefinition[0].uri.includes('__bhash_standard_library__.bhash'));
    assert.ok((await fs.readFile(fileURLToPath(stdDefinition[0].uri),'utf8')).includes('संकुल मानक;'),'F12 target is a real declaration document');
    const bad='कार्य मुख्य() -> शून्य { लिखो(अज्ञात); स्थिर क: पूर्णांक = १; क = २; }';
    lsp.send('textDocument/didChange',{textDocument:{...document,version:4},contentChanges:[{text:bad}]});
    const errors=await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri)&&m.params.version===4&&m.params.diagnostics.length===2);
    assert.deepEqual(errors.params.diagnostics.map(d=>d.code),['BH2001','BH4001']);
    assert.ok(errors.params.diagnostics.every(d=>d.message.includes('—')),'Hindi guidance retains English detail');
    await lsp.request('shutdown',null);lsp.send('exit');lsp.process.stdin.end();
});

test('stdio LSP: locked library navigation and lock-change diagnostics', {timeout:90000},async t=>{
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'bhash-packages-lsp-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const app=path.join(root,'app'),lib=path.join(root,'library');
    for(const dir of [app,lib])await fs.mkdir(path.join(dir,'src'),{recursive:true});
    const manifest=path.join(app,'bhash.project'),lock=path.join(app,'bhash.lock');
    await fs.writeFile(manifest,'name=app\ndependency.lib.path=../library\ndependency.lib.version=1.0.0\n');
    await fs.writeFile(path.join(lib,'bhash.project'),'name=util\nkind=library\nversion=1.0.0\n');
    const library=path.join(lib,'src','library.bhash'),main=path.join(app,'src','main.bhash');
    const source='संकुल पुस्तक; सार्वजनिक कार्य दुगुना(अंक: पूर्णांक) -> पूर्णांक { लौटाओ अंक * २; }';
    const program='आयात पुस्तक.दुगुना; कार्य मुख्य() -> शून्य { लिखो(दुगुना(३)); }';
    await fs.writeFile(library,source);await fs.writeFile(main,program);
    const cli=spawn('java',['-jar',jar,'deps','lock',app,'--offline']);let errors='';cli.stderr.on('data',d=>errors+=d);
    assert.equal(await new Promise(resolve=>cli.on('exit',resolve)),0,errors);
    const lsp=transport();t.after(()=>lsp.process.kill());const uri=file=>pathToFileURL(file).href,document={uri:uri(main)};
    await lsp.request('initialize',{processId:process.pid,workspaceFolders:[{uri:uri(app),name:'app'}],capabilities:{}});lsp.send('initialized',{});
    lsp.send('textDocument/didOpen',{textDocument:{...document,languageId:'bhash',version:1,text:program}});
    await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri(main))&&m.params.diagnostics.length===0);
    const definitions=await lsp.request('textDocument/definition',{textDocument:document,position:position(program,'दुगुना(३)')});
    assert.ok(definitions.some(d=>sameUri(d.uri,uri(library))));
    const completion=await lsp.request('textDocument/completion',{textDocument:document,position:position(program,'दुगुना(३)')});
    assert.ok(completion.some(i=>i.label==='दुगुना'));
    const call=position(program,'दुगुना(३)');call.character+='दुगुना('.length;
    const signature=await lsp.request('textDocument/signatureHelp',{textDocument:document,position:call});assert.ok(signature.signatures[0].label.includes('पूर्णांक'));
    await fs.writeFile(library,source+'\n// changed\n');
    lsp.send('workspace/didChangeWatchedFiles',{changes:[{uri:uri(library),type:2}]});
    await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri(manifest))&&m.params.diagnostics.some(d=>d.code==='BH8003'));
    await fs.writeFile(library,source);const validLock=await fs.readFile(lock,'utf8');await fs.writeFile(lock,'{}');
    lsp.send('workspace/didChangeWatchedFiles',{changes:[{uri:uri(lock),type:2}]});
    await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri(manifest))&&m.params.diagnostics.some(d=>d.code==='BH8002'));
    await fs.writeFile(lock,validLock);lsp.messages.length=0;
    lsp.send('workspace/didChangeWatchedFiles',{changes:[{uri:uri(lock),type:2}]});
    await lsp.wait(m=>m.method==='textDocument/publishDiagnostics'&&sameUri(m.params.uri,uri(main))&&m.params.diagnostics.length===0);
    await lsp.request('shutdown',null);lsp.send('exit');lsp.process.stdin.end();
});
