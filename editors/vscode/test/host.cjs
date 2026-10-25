// SPDX-License-Identifier: Apache-2.0
const vscode=require('vscode');const assert=require('node:assert/strict');const path=require('node:path');const fs=require('node:fs/promises');const {execFile}=require('node:child_process');const {promisify}=require('node:util');
async function until(action,predicate,description){const deadline=Date.now()+45000;let last;while(Date.now()<deadline){last=await action();if(predicate(last))return last;await new Promise(r=>setTimeout(r,100));}throw new Error(`Timed out: ${description}; ${JSON.stringify(last)}`);}
exports.run=async()=>{
    const workspace=process.env.BHASH_TEST_WORKSPACE,uri=vscode.Uri.file(path.join(workspace,'src','main.bhash'));
    const document=await vscode.workspace.openTextDocument(uri);await vscode.window.showTextDocument(document);
    assert.equal(document.languageId,'bhash');const extension=vscode.extensions.getExtension('bhashitva.bhash');assert.ok(extension);await extension.activate();
    const position=new vscode.Position(2,document.lineAt(2).text.indexOf("दोहरा"));
    const label=item=>typeof item.label==='string'?item.label:item.label.label;
    const completion=await until(()=>vscode.commands.executeCommand('vscode.executeCompletionItemProvider',uri,position),list=>list?.items?.some(i=>label(i)==='दोहरा'),'compiler keyword completion');
    assert.ok(completion.items.some(i=>label(i).startsWith('स्थिर')));
    const definition=await vscode.commands.executeCommand('vscode.executeDefinitionProvider',uri,position);
    assert.ok(definition.some(location=>location.uri?.fsPath===path.join(workspace,'src','math.bhash')),'cross-file definition');
    const outline=await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider',uri);assert.ok(outline.some(symbol=>symbol.name==='मुख्य'));
    const source=document.getText();const edit=new vscode.WorkspaceEdit();edit.replace(uri,new vscode.Range(0,0,document.lineCount,0),source.replace('क: पूर्णांक','क: तार्किक'));await vscode.workspace.applyEdit(edit);
    await until(()=>vscode.languages.getDiagnostics(uri),ds=>ds.some(d=>d.source==='bhash'&&d.severity===vscode.DiagnosticSeverity.Error),'unsaved semantic diagnostic');
    const restore=new vscode.WorkspaceEdit();restore.replace(uri,new vscode.Range(0,0,document.lineCount,0),source);await vscode.workspace.applyEdit(restore);await document.save();
    await until(()=>vscode.languages.getDiagnostics(uri),ds=>ds.filter(d=>d.source==='bhash').length===0,'diagnostic clearing');
    const tasks=await vscode.tasks.fetchTasks({type:'bhash'});const build=tasks.find(task=>task.definition.command==='build');assert.ok(build,'configured Bhash task resolves');
    await new Promise(async(resolve,reject)=>{const timer=setTimeout(()=>{listener.dispose();reject(new Error('Build task timed out'));},60000);const listener=vscode.tasks.onDidEndTaskProcess(event=>{if(event.execution.task.definition.type==='bhash'){clearTimeout(timer);listener.dispose();event.exitCode===0?resolve():reject(new Error(`Build task exit ${event.exitCode}`));}});try{await vscode.tasks.executeTask(build);}catch(error){clearTimeout(timer);listener.dispose();reject(error);}});
    const result=await promisify(execFile)('java',['-jar',path.join(workspace,'build','host-test.jar')],{encoding:'utf8'});assert.equal(result.stdout.trim(),'६');
    assert.equal(await fs.readFile(path.join(workspace,'src','main.bhash'),'utf8'),source);
    console.log('PASS VS Code extension host: activation, completion, cross-file definition, outline, unsaved diagnostics, and JVM build task.');
};
