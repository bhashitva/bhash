// SPDX-License-Identifier: Apache-2.0
const vscode=require('vscode');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const path=require('node:path');
module.exports=async function libraryHost(extension){
    const until=async(action,predicate)=>{let value;const end=Date.now()+5000;do{value=action();if(predicate(value))return;await new Promise(resolve=>setTimeout(resolve,20));}while(Date.now()<end);throw Error('Library editor state did not settle: '+JSON.stringify(value));};
    const {LibraryLookup}=require('../dist/library.js');
    const catalog=JSON.parse(await fs.readFile(path.join(extension.extensionPath,'dist/library.json'),'utf8'));
    const document=await vscode.workspace.openTextDocument({language:'bhash',content:'पुराना'});
    let editor=await vscode.window.showTextDocument(document);editor.selection=new vscode.Selection(0,0,0,document.getText().length);
    const lookup=new LibraryLookup({extensionUri:extension.extensionUri});
    try {
        await lookup.open();await lookup.handleMessage({type:'insert',id:'type:समुच्चय',request:1});
        assert.equal(document.getText(),'मानक.समुच्चय<त>');editor=vscode.window.activeTextEditor;
        assert.equal(document.getText(editor.selection),'त','VS Code selects the generic placeholder');
        await vscode.commands.executeCommand('undo');assert.equal(document.getText(),'पुराना','insertion is one isolated Undo step');
        editor.selection=new vscode.Selection(0,0,0,document.getText().length);await lookup.open();
        const text=catalog.types.find(t=>t.name==='पाठ्य');const id='member:पाठ्य:'+text.members.findIndex(m=>m.name==='बाँटो');
        await lookup.handleMessage({type:'insert',id,request:2});
        assert.equal(document.getText(),'मानक.पाठ्य.बाँटो(मानक, विभाजक)');
        await until(()=>document.getText(editor.selection),value=>value==='मानक');
        // Identifier expressions keep this tab-stop test independent of the
        // editor's quote auto-surround settings.
        await vscode.commands.executeCommand('type',{text:'क'});
        await until(()=>document.getText(),value=>value.includes('बाँटो(क,'));
        await vscode.commands.executeCommand('jumpToNextSnippetPlaceholder');
        await until(()=>document.getText(editor.selection),value=>value==='विभाजक');
        await vscode.commands.executeCommand('type',{text:'ख'});
        await until(()=>document.getText(),value=>value==='मानक.पाठ्य.बाँटो(क, ख)');
        assert.equal(document.getText(),'मानक.पाठ्य.बाँटो(क, ख)','real VS Code Tab fields replace the argument names');
        assert.ok((await vscode.commands.getCommands(true)).includes('bhash.openStandardLibrary'));
        console.log('PASS library lookup: real editor selection, generic/call placeholders and isolated Undo.');
    } finally {lookup.dispose();}
};
