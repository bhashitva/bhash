// SPDX-License-Identifier: Apache-2.0
const vscode=require('vscode');const assert=require('node:assert/strict');const path=require('node:path');const fs=require('node:fs/promises');const {execFile}=require('node:child_process');const {promisify}=require('node:util');
async function until(action,predicate,description){const deadline=Date.now()+45000;let last;while(Date.now()<deadline){last=await action();if(predicate(last))return last;await new Promise(r=>setTimeout(r,100));}throw new Error(`Timed out: ${description}; ${JSON.stringify(last)}`);}
exports.run=async()=>{
    const workspace=process.env.BHASH_TEST_WORKSPACE;let uri=vscode.Uri.file(path.join(workspace,'src','main.bhash'));
    const document=await vscode.workspace.openTextDocument(uri);uri=document.uri;await vscode.window.showTextDocument(document);
    assert.equal(document.languageId,'bhash');const extension=vscode.extensions.getExtension('bhashitva.bhash');assert.ok(extension);await extension.activate();
    // Use the editor command (F9), which checks the language contribution.
    // debug.addBreakpoints bypasses this gate and missed the 0.8.0 regression.
    assert.equal(vscode.workspace.getConfiguration('debug',uri).get('allowBreakpointsEverywhere'),false);
    const breakpointEditor=await vscode.window.showTextDocument(document);
    breakpointEditor.selection=new vscode.Selection(3,0,3,0);
    await vscode.commands.executeCommand('editor.debug.action.toggleBreakpoint');
    const editorPoint=vscode.debug.breakpoints.find(b=>b instanceof vscode.SourceBreakpoint&&b.location.uri.toString()===uri.toString()&&b.location.range.start.line===3);
    assert.ok(editorPoint,'Bhash must permit normal gutter/F9 breakpoints with the override disabled');
    await vscode.commands.executeCommand('editor.debug.action.toggleBreakpoint');
    assert.ok(!vscode.debug.breakpoints.some(b=>b.id===editorPoint.id),'F9 removes the breakpoint');
    console.log('PASS Bhash editor breakpoint toggle with allowBreakpointsEverywhere=false.');
    const position=new vscode.Position(2,document.lineAt(2).text.indexOf("दोहरा"));
    const label=item=>typeof item.label==='string'?item.label:item.label.label;
    const completion=await until(()=>vscode.commands.executeCommand('vscode.executeCompletionItemProvider',uri,position),list=>list?.items?.some(i=>label(i)==='दोहरा'),'compiler keyword completion');
    assert.ok(completion.items.some(i=>label(i).startsWith('स्थिर')));
    const definition=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',uri,position);
    assert.ok(definition.some(location=>location.uri && path.relative(path.join(workspace,'src','math.bhash'),location.uri.fsPath)===''),'cross-file definition');
    const outline=await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider',uri);assert.ok(outline.some(symbol=>symbol.name==='मुख्य'));
    const source=document.getText();const edit=new vscode.WorkspaceEdit();edit.replace(uri,new vscode.Range(0,0,document.lineCount,0),source.replace('क: पूर्णांक','क: तार्किक'));await vscode.workspace.applyEdit(edit);
    await until(()=>vscode.languages.getDiagnostics(uri),ds=>ds.some(d=>d.source==='bhash'&&d.severity===vscode.DiagnosticSeverity.Error),'unsaved semantic diagnostic');
    const restore=new vscode.WorkspaceEdit();restore.replace(uri,new vscode.Range(0,0,document.lineCount,0),source);await vscode.workspace.applyEdit(restore);await document.save();
    await until(()=>vscode.languages.getDiagnostics(uri),ds=>ds.filter(d=>d.source==='bhash').length===0,'diagnostic clearing');
    // Exercise new features through VS Code's actual provider adapters.
    const rich='कार्य मुख्य() -> शून्य { स्थिर अंक: सूची<पूर्णांक> = नया सूची<पूर्णांक>(); अंक.जोड़ो(१); }';
    const change=new vscode.WorkspaceEdit();change.replace(uri,new vscode.Range(0,0,document.lineCount,0),rich);await vscode.workspace.applyEdit(change);
    const memberPosition=document.positionAt(rich.indexOf('अंक.जोड़ो')+'अंक.'.length);
    const memberList=await until(()=>vscode.commands.executeCommand('vscode.executeCompletionItemProvider',uri,memberPosition),list=>list?.items?.some(i=>label(i)==='जोड़ो'),'typed list member completion');
    assert.ok(memberList.items.some(i=>label(i)==='जोड़ो'&&i.detail.includes('पूर्णांक')));
    const signaturePosition=document.positionAt(rich.indexOf('जोड़ो(')+'जोड़ो('.length);
    const signature=await vscode.commands.executeCommand('vscode.executeSignatureHelpProvider',uri,signaturePosition,'(');
    assert.ok(signature?.signatures.some(s=>s.label.includes('पूर्णांक')),'typed list signature help');
    const standard='आयात मानक.पाठ्य; कार्य मुख्य() -> शून्य { पाठ्य.बाँटो("अ", ","); }';
    const stdEdit=new vscode.WorkspaceEdit();stdEdit.replace(uri,new vscode.Range(0,0,document.lineCount,0),standard);await vscode.workspace.applyEdit(stdEdit);
    const stdPosition=document.positionAt(standard.indexOf('पाठ्य.बाँटो')+'पाठ्य.'.length);
    const stdItems=await until(()=>vscode.commands.executeCommand('vscode.executeCompletionItemProvider',uri,stdPosition),list=>list?.items?.some(i=>label(i)==='बाँटो'),'standard-library completion');
    assert.ok(stdItems.items.some(i=>label(i)==='बाँटो'&&i.detail.includes('सूची<पाठ>')));
    const stdDefinitions=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',uri,document.positionAt(standard.indexOf('बाँटो(')));
    assert.ok(stdDefinitions.length>0,'standard-library F12');
    const stdDoc=await vscode.workspace.openTextDocument(stdDefinitions[0].uri);assert.ok(stdDoc.getText().includes('संकुल मानक;'));
    const original=new vscode.WorkspaceEdit();original.replace(uri,new vscode.Range(0,0,document.lineCount,0),source);await vscode.workspace.applyEdit(original);await document.save();
    await until(()=>vscode.languages.getDiagnostics(uri),ds=>ds.filter(d=>d.source==='bhash').length===0,'diagnostic clearing after provider tests');
    const tasks=await vscode.tasks.fetchTasks({type:'bhash'});const build=tasks.find(task=>task.definition.command==='build');assert.ok(build,'configured Bhash task resolves');
    await new Promise(async(resolve,reject)=>{const timer=setTimeout(()=>{listener.dispose();reject(new Error('Build task timed out'));},60000);const listener=vscode.tasks.onDidEndTaskProcess(event=>{if(event.execution.task.definition.type==='bhash'){clearTimeout(timer);listener.dispose();event.exitCode===0?resolve():reject(new Error(`Build task exit ${event.exitCode}`));}});try{await vscode.tasks.executeTask(build);}catch(error){clearTimeout(timer);listener.dispose();reject(error);}});
    const result=await promisify(execFile)('java',['-jar',path.join(workspace,'build','host-test.jar')],{encoding:'utf8'});assert.equal(result.stdout.trim(),'६');
    if(['linux','darwin'].includes(process.platform) && process.env.BHASH_TEST_NATIVE==='1') {
        // Resolve a configured task before first use: resolution itself must not install tools.
        const native=(await vscode.tasks.fetchTasks({type:'bhash'})).find(t=>t.definition.target==='native');
        assert.ok(native,'configured Native task resolves');
        assert.ok(native.execution instanceof vscode.ProcessExecution);
        assert.equal(path.basename(native.execution.args[0]),'native-task.js');
        await assert.rejects(fs.stat(process.env.BHASH_TOOLCHAIN_HOME),'task discovery must not install tools');
        await new Promise(async(resolve,reject)=>{
            const timer=setTimeout(()=>{listener.dispose();reject(Error('Native task timed out'));},300000);
            const listener=vscode.tasks.onDidEndTaskProcess(event=>{if(event.execution.task.definition.target==='native'){
                clearTimeout(timer);listener.dispose();event.exitCode===0?resolve():reject(Error(`Native task exit ${event.exitCode}`));
            }});
            try{await vscode.tasks.executeTask(native);}catch(error){clearTimeout(timer);listener.dispose();reject(error);}
        });
        // Execute the produced executable directly: it needs neither Java nor LLVM at runtime.
        const nativeResult=await promisify(execFile)(path.join(workspace,'build','host-test-native'),[],{encoding:'utf8'});
        assert.equal(nativeResult.stdout.trim(),'६');
        const status=await promisify(execFile)('java',['-jar',extension.extensionPath+'/server/bhash-compiler.jar','native','toolchain','status','--json']);
        assert.equal(JSON.parse(status.stdout).archiveSha256,require('../dist/native.js').sdk.sha256);
        console.log('PASS VS Code Native configured build: first-use GitHub SDK download, verified cache, and generated native execution.');
    }
    assert.equal(await fs.readFile(path.join(workspace,'src','main.bhash'),'utf8'),source);
    console.log('PASS VS Code extension host: activation, completion, cross-file definition, outline, unsaved diagnostics, and JVM build task.');
};
