// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');
const fs=require('node:fs');const path=require('node:path');const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..');const catalog=JSON.parse(fs.readFileSync(path.join(root,'dist/library.json'),'utf8'));
const memberId=(type,name)=>'member:'+type+':'+catalog.types.find(t=>t.name===type).members.findIndex(m=>m.name===name);
function fixture(language='bhash') {
    const disposables=[];const messages=[],insertions=[],links=[],copies=[];let selectionListener,activeListener;
    const document={languageId:language,version:1,isClosed:false};
    const editor={document,selections:[{start:2,end:7}],viewColumn:1,insertSnippet:async(...args)=>{insertions.push(args);return true;}};
    const uri=file=>({fsPath:file,toString:()=>pathToFileURL(file).href});
    const disposable=()=>({dispose(){disposables.push(true);}});
    const panel={webview:{cspSource:'https://assets.example.test',asWebviewUri:value=>value,html:'',postMessage:async message=>{messages.push(message);return true;},onDidReceiveMessage:handler=>{panel.receive=handler;return disposable();}},reveal(){},onDidDispose(handler){panel.closed=handler;return disposable();},dispose(){panel.closed?.();}};
    const vscode={Uri:{joinPath:(base,...parts)=>uri(path.join(base.fsPath,...parts)),parse:value=>({toString:()=>value})},ViewColumn:{Beside:2},
        SnippetString:class{constructor(){this.operations=[];}appendText(value){this.operations.push(['text',value]);return this;}appendPlaceholder(value){this.operations.push(['placeholder',value]);return this;}},
        window:{activeTextEditor:editor,onDidChangeActiveTextEditor:handler=>{activeListener=handler;return disposable();},onDidChangeTextEditorSelection:handler=>{selectionListener=handler;return disposable();},
            createWebviewPanel:(...args)=>{panel.created=(panel.created||0)+1;panel.options=args[3];return panel;},showTextDocument:async()=>editor},
        workspace:{isTrusted:false,fs:{readFile:async value=>fs.readFileSync(value.fsPath)}},env:{clipboard:{writeText:async value=>copies.push(value)},openExternal:async value=>{links.push(value.toString());return true;}}};
    const module={exports:{}};
    vm.runInNewContext(fs.readFileSync(path.join(root,'dist/library.js'),'utf8'),{module,exports:module.exports,Buffer,console,require:name=>name==='vscode'?vscode:require(name)});
    const lookup=new module.exports.LibraryLookup({extensionUri:uri(root)});
    return {lookup,panel,messages,insertions,links,copies,document,editor,vscode,selection:()=>selectionListener({textEditor:editor}),active:(value=editor)=>{vscode.window.activeTextEditor=value;activeListener(value);},module};
}
test('bundled catalog and shared renderer are available offline with a restricted CSP',async()=>{
    const f=fixture();await f.lookup.open();
    assert.equal(catalog.types.length,131);assert.equal(catalog.types.reduce((n,t)=>n+t.members.length,0),940);
    assert.match(f.panel.webview.html,/default-src 'none'/);assert.match(f.panel.webview.html,/script-src 'nonce-[a-f0-9]{48}'/);
    assert.ok(!f.panel.webview.html.includes('unsafe-inline'));assert.ok(!f.panel.webview.html.includes('unsafe-eval'));
    assert.ok(f.panel.webview.html.includes('data-catalog='));assert.equal(f.panel.options.localResourceRoots.length,1);
    for(const asset of ['library-webview.js','library.json','library-theme.css','library-base.css'])assert.ok(fs.existsSync(path.join(root,'dist',asset)));
    f.lookup.dispose();
});
test('insertion derives a safe snippet from the catalog and replaces captured selections',async()=>{
    const f=fixture();await f.lookup.open();await f.lookup.handleMessage({type:'insert',id:'type:समुच्चय',request:1,text:'untrusted text'});
    const [snippet,ranges,options]=f.insertions[0];assert.deepEqual(Array.from(snippet.operations,op=>Array.from(op)),[['text','मानक.समुच्चय<'],['placeholder','त'],['text','>']]);
    assert.deepEqual(Array.from(ranges),f.editor.selections);assert.equal(options.undoStopBefore,true);assert.equal(options.undoStopAfter,true);
    await f.lookup.handleMessage({type:'insert',id:memberId('पाठ्य','बाँटो'),request:2});
    assert.deepEqual(Array.from(f.insertions[1][0].operations,op=>Array.from(op)),[['text','मानक.पाठ्य.बाँटो('],['placeholder','मानक'],['text',', '],['placeholder','विभाजक'],['text',')']]);
    assert.equal(f.messages.at(-1).ok,true);f.lookup.dispose();
});
test('stale and closed editors are never overwritten; selecting a fresh location recovers',async()=>{
    const f=fixture();await f.lookup.open();f.document.version++;
    await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:1});assert.equal(f.insertions.length,0);assert.equal(f.messages.at(-1).ok,false);
    f.selection();await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:2});assert.equal(f.insertions.length,1);
    f.document.isClosed=true;await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:3});assert.equal(f.insertions.length,1);f.lookup.dispose();
});
test('opening without a Bhash editor supports browsing and explains insertion requirements',async()=>{
    const f=fixture('plaintext');await f.lookup.open();await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:1});
    assert.equal(f.insertions.length,0);assert.match(f.messages.at(-1).error,/Open a Bhash editor/);f.lookup.dispose();
});
test('switching to another language clears the insertion target and concurrent opens reuse one pane',async()=>{
    const f=fixture();await Promise.all([f.lookup.open(),f.lookup.open()]);assert.equal(f.panel.created,1);
    f.active({...f.editor,document:{...f.document,languageId:'plaintext'}});
    await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:1});assert.equal(f.insertions.length,0);
    f.active();await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:2});assert.equal(f.insertions.length,1);f.lookup.dispose();
});
test('an edit during editor activation is rejected before inserting a snippet',async()=>{
    const f=fixture();await f.lookup.open();
    f.vscode.window.showTextDocument=async()=>{f.document.version++;return f.editor;};
    await f.lookup.handleMessage({type:'insert',id:'type:पाठ्य',request:1});assert.equal(f.insertions.length,0);assert.equal(f.messages.at(-1).ok,false);f.lookup.dispose();
});
test('library entry points are contributed and command IDs are unique',()=>{
    const manifest=require('../package.json');const commands=manifest.contributes.commands.map(c=>c.command);
    assert.ok(commands.includes('bhash.openStandardLibrary'));assert.equal(new Set(commands).size,commands.length);
    assert.ok(manifest.contributes.menus['editor/title'].some(c=>c.command==='bhash.openStandardLibrary'));
});
test('forged IDs, arbitrary URLs and source-bearing messages cannot edit or execute anything',async()=>{
    const f=fixture();await f.lookup.open();
    for(const id of ['../../secret','member:गणित:-1','type:missing'])await f.lookup.handleMessage({type:'insert',id,request:1});
    await f.lookup.handleMessage({type:'documentation',path:'https://evil.example',request:2});
    await f.lookup.handleMessage({type:'documentation',path:'docs/../../secret.md',request:3});
    await f.lookup.handleMessage({type:'run',id:'type:पाठ्य',request:4});
    assert.equal(f.insertions.length,0);assert.equal(f.links.length,0);assert.ok(f.messages.every(m=>!m.ok));
    await f.lookup.handleMessage({type:'copy',id:memberId('पाठ्य','बाँटो'),request:5,text:'untrusted'});
    assert.ok(f.copies[0].includes('बाँटो'));assert.ok(!f.copies[0].includes('untrusted'));
    await f.lookup.handleMessage({type:'documentation',path:'tutorial/null-safety/README.md',request:6});
    assert.equal(f.links[0],'https://github.com/bhashitva/bhash/blob/main/tutorial/null-safety/README.md');f.lookup.dispose();
});
