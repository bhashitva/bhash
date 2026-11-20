// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import {randomBytes} from 'node:crypto';
import {libraryIndex, librarySnippet, signature, documentationUrl, LibraryEntry} from '../../../playground/src/library-model.mjs';

const escapeHtml=(text:string)=>text.replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]!));
export function editorSnippet(template:string):vscode.SnippetString {
    const snippet=new vscode.SnippetString();let offset=0;
    for(const match of template.matchAll(/\$\{([^}]*)\}/gu)) {
        snippet.appendText(template.slice(offset,match.index));snippet.appendPlaceholder(match[1]);offset=match.index!+match[0].length;
    }
    return snippet.appendText(template.slice(offset));
}
interface Target {document:vscode.TextDocument; version:number; selections:vscode.Selection[]; column:vscode.ViewColumn|undefined;}
export class LibraryLookup implements vscode.Disposable {
    private panel:vscode.WebviewPanel|undefined;
    private target:Target|undefined;
    private entries=new Map<string,LibraryEntry>();
    private paths=new Set<string>(['tutorial/null-safety/README.md']);
    private readonly listeners:vscode.Disposable[]=[];
    constructor(private readonly context:Pick<vscode.ExtensionContext,'extensionUri'>) {
        this.capture(vscode.window.activeTextEditor);
        this.listeners.push(vscode.window.onDidChangeActiveTextEditor(editor=>this.capture(editor)),
            vscode.window.onDidChangeTextEditorSelection(event=>{if(event.textEditor===vscode.window.activeTextEditor)this.capture(event.textEditor);}));
    }
    private capture(editor:vscode.TextEditor|undefined) {
        if(editor&&editor.document.languageId!=='bhash'){this.target=undefined;return;}
        if(editor?.document.languageId==='bhash')this.target={document:editor.document,version:editor.document.version,selections:[...editor.selections],column:editor.viewColumn};
    }
    async open() {
        this.capture(vscode.window.activeTextEditor);
        if(this.panel){this.panel.reveal(this.panel.viewColumn);void this.panel.webview.postMessage({type:'focus-search'});return;}
        const root=vscode.Uri.joinPath(this.context.extensionUri,'dist');
        const catalog=JSON.parse(Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.joinPath(root,'library.json'))).toString('utf8'));
        const index=libraryIndex(catalog);this.entries=new Map(index.entries.map(entry=>[entry.id,entry]));
        for(const type of index.types){this.paths.add(type.reference);this.paths.add(type.guide);}
        const markup=Buffer.from(await vscode.workspace.fs.readFile(vscode.Uri.joinPath(root,'library.html'))).toString('utf8');
        // Another open request may have completed while the files were loading.
        const existing=this.panel as vscode.WebviewPanel|undefined;
        if(existing){existing.reveal(existing.viewColumn);return;}
        const panel=vscode.window.createWebviewPanel('bhash.library','भाष पुस्तकालय · Bhash Library',vscode.ViewColumn.Beside,
            {enableScripts:true,localResourceRoots:[root],retainContextWhenHidden:true});
        this.panel=panel;
        const uri=(name:string)=>escapeHtml(panel.webview.asWebviewUri(vscode.Uri.joinPath(root,name)).toString());
        const nonce=randomBytes(24).toString('hex');
        panel.webview.html=`<!doctype html><html lang="hi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${panel.webview.cspSource}; script-src 'nonce-${nonce}'; connect-src ${panel.webview.cspSource};"><link rel="stylesheet" href="${uri('library-base.css')}"><link rel="stylesheet" href="${uri('library-theme.css')}"><title>Bhash Library</title></head><body><button id="library-open" hidden></button>${markup.replace('id="library-dialog"',`id="library-dialog" data-catalog="${uri('library.json')}"`)}<script nonce="${nonce}" src="${uri('library-webview.js')}"></script></body></html>`;
        const receive=panel.webview.onDidReceiveMessage(message=>{void this.handleMessage(message,panel);});
        panel.onDidDispose(()=>{receive.dispose();if(this.panel===panel)this.panel=undefined;});
    }
    // Messages contain catalog IDs, never source text, editor URIs or commands.
    async handleMessage(message:unknown,panel=this.panel) {
        if(!panel||!message||typeof message!=='object')return;
        const m=message as {type?:unknown;id?:unknown;path?:unknown;request?:unknown};
        if(typeof m.request!=='number'||!Number.isSafeInteger(m.request))return;
        try {
            if(m.type==='close'){panel.dispose();return;}
            if(m.type==='documentation') {
                if(typeof m.path!=='string'||!this.paths.has(m.path))throw Error('Unknown documentation link.');
                if(!await vscode.env.openExternal(vscode.Uri.parse(documentationUrl(m.path))))throw Error('Could not open documentation.');
            } else {
                const entry=typeof m.id==='string'?this.entries.get(m.id):undefined;
                if(!entry)throw Error('Unknown library entry.');
                if(m.type==='copy')await vscode.env.clipboard.writeText(signature(entry.type,entry.member));
                else if(m.type==='insert')await this.insert(entry);
                else throw Error('Unknown library action.');
            }
            await panel.webview.postMessage({request:m.request,ok:true});
        } catch(error) {
            await panel.webview.postMessage({request:m.request,ok:false,error:error instanceof Error?error.message:String(error)});
        }
    }
    private async insert(entry:LibraryEntry) {
        const target=this.target;
        if(!target||target.document.isClosed||target.document.languageId!=='bhash')throw Error('पहले .bhash फ़ाइल खोलें और डालने का स्थान चुनें। · Open a Bhash editor first.');
        if(target.document.version!==target.version)throw Error('कोड बदल गया है। संपादक में स्थान चुनकर फिर डालें। · Select an insertion point again.');
        const editor=await vscode.window.showTextDocument(target.document,{viewColumn:target.column,preserveFocus:false});
        if(target.document.version!==target.version)throw Error('The source changed; select an insertion point again.');
        if(!await editor.insertSnippet(editorSnippet(librarySnippet(entry.type,entry.member)),target.selections,{undoStopBefore:true,undoStopAfter:true}))throw Error('इस संपादक में पाठ नहीं डाला जा सका। · The editor could not be edited.');
        this.capture(editor);
    }
    dispose(){this.panel?.dispose();for(const listener of this.listeners)listener.dispose();}
}
export function registerLibraryLookup(context:vscode.ExtensionContext) {
    const lookup=new LibraryLookup(context);
    context.subscriptions.push(lookup,vscode.commands.registerCommand('bhash.openStandardLibrary',()=>lookup.open().catch(error=>vscode.window.showErrorMessage(String(error)))));
    const status=vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right,110);
    status.text='$(book) Bhash Library';status.tooltip='मानक पुस्तकालय: वर्ग और फलन खोजें';status.command='bhash.openStandardLibrary';
    const refresh=()=>vscode.window.activeTextEditor?.document.languageId==='bhash'?status.show():status.hide();
    context.subscriptions.push(status,vscode.window.onDidChangeActiveTextEditor(refresh));refresh();
}
