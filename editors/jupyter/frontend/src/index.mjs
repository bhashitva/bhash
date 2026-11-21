// SPDX-License-Identifier: Apache-2.0
import {ICommandPalette,ToolbarButton,Clipboard} from '@jupyterlab/apputils';
import {ILabShell} from '@jupyterlab/application';
import {INotebookTracker} from '@jupyterlab/notebook';
import {Widget} from '@lumino/widgets';
import {mountLibraryBrowser} from '../../../../playground/src/library-browser.mjs';
import {libraryIndex,librarySnippet,signature} from '../../../../playground/src/library-model.mjs';
import catalog from '../../../../playground/public/library.json';
import {captureTarget,checkTarget,insertCall,isBhash} from './insert.mjs';
import {mountLanguageBrowser} from './language-browser.mjs';

const command='bhash:standard-library';
const entries=new Map(libraryIndex(catalog).entries.map(entry=>[entry.id,entry]));
class LibraryWidget extends Widget {
  constructor(actions) {
    super();this.id='bhash-standard-library';this.title.label='भाष पुस्तकालय · Bhash Library';this.title.closable=true;
    this.node.style.height='100%';this.node.style.minWidth='300px';
    this.root=this.node.attachShadow({mode:'open'});
    const style=document.createElement('style');style.textContent=BHASH_LIBRARY_STYLE;
    const body=document.createElement('div');body.innerHTML=BHASH_LIBRARY_HTML;
    body.id='reference-library';
    const shell=document.createElement('div');shell.className='reference-root';
    const navigation=document.createElement('nav');navigation.className='reference-modes';navigation.setAttribute('aria-label','भाष संदर्भ · Bhash reference');
    this.modes={};
    for(const [id,label] of [['library','📚 पुस्तकालय · Library'],['language','⌨ शब्द और ढाँचे · Language']]){
      const button=document.createElement('button');button.type='button';button.id='reference-mode-'+id;button.textContent=label;button.addEventListener('click',()=>this.setMode(id));this.modes[id]=button;navigation.append(button);
    }
    shell.append(navigation,body);this.root.append(style,shell);this.actions=actions;this.mode='library';this.library=body;this.shell=shell;
  }
  onAfterAttach() {
    if(this.mounted)return;this.mounted=true;
    const notice=text=>this.root.querySelector('#library-notice').textContent=text;
    const act=async callback=>{try{await callback();notice('डाला गया; अपने मान भरें। कक्ष अपने-आप नहीं चला। · Inserted without running.');}catch(error){notice(error.message);}};
    mountLibraryBrowser((_template,id)=>act(()=>this.actions.insert(entries.get(id))),()=>{}, {
      root:this.root,catalog,embedded:true,onClose:()=>this.close(),
      insertionHint:'चुना तर्क भरें; शेष तर्क और वस्तु को अपने मानों से बदलें। कक्ष अपने-आप नहीं चलता। · Replace the argument placeholders before running.',
      copyText:async(_text,id)=>Clipboard.copyToSystem(signature(entries.get(id).type,entries.get(id).member)),
      insertExample:(id,index)=>act(()=>this.actions.example(entries.get(id),index))
    });
    this.language=mountLanguageBrowser(this.shell,BHASH_LANGUAGE_WORDS,entry=>this.actions.language(entry),()=>this.close());
    this.setMode(this.mode,false);
    this.root.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();this.close();}},{capture:true});
  }
  setMode(mode,focus=true){
    this.mode=mode;
    this.title.label=mode==='language'?'भाष शब्द और ढाँचे · Bhash Language':'भाष पुस्तकालय · Bhash Library';
    if(!this.language)return;
    this.library.hidden=mode!=='library';this.language.element.hidden=mode!=='language';
    for(const [id,button] of Object.entries(this.modes))button.setAttribute('aria-pressed',String(id===mode));
    if(focus)this.onActivateRequest();
  }
  onActivateRequest(){if(this.mode==='language')this.language?.focus();else this.root.querySelector('#library-search')?.focus();}
  onCloseRequest(){this.actions.beforeClose?.();this.dispose();}
}

export default {
  id:'@bhashitva/library-lookup:plugin',autoStart:true,requires:[INotebookTracker,ICommandPalette],optional:[ILabShell],
  activate(app,tracker,palette,labShell) {
    let widget,target=null,watched=null;
    const unwatch=()=>{if(watched){watched.editor.model.selections.changed.disconnect(watched.changed);watched=null;}};
    const capture=panel=>{
      target=captureTarget(panel);const cell=target?.cell;if(!cell){unwatch();return;}
      void Promise.resolve(cell.ready).then(()=>{
        const editor=cell.editor;if(panel.isDisposed||target?.cell!==cell||!editor||watched?.editor===editor)return;
        unwatch();
        const changed=(_sender,change)=>{if(change.key===editor.uuid&&editor.hasFocus()&&tracker.currentWidget===panel)target=captureTarget(panel);};
        editor.model.selections.changed.connect(changed);
        watched={editor,changed,panel};
      }).catch(()=>{});
    };
    const open=(panel,mode='library')=>{
      if(panel)capture(panel);else if(tracker.currentWidget)capture(tracker.currentWidget);
      if(!widget||widget.isDisposed) {
        widget=new LibraryWidget({
          // Notebook 7.2 retains its current sidebar widget on removal. Clear
          // it through the public shell API before disposal so the same pane
          // ID can be reopened without being mistaken for an expand/collapse.
          beforeClose:()=>{if(!labShell&&widget?.isVisible)app.shell.collapseRight?.();},
          insert:entry=>{if(!entry)throw Error('Unknown library entry');target=insertCall(target,librarySnippet(entry.type,entry.member));app.shell.activateById(target.panel.id);},
          language:entry=>{target=insertCall(target,entry.template,entry.kind);app.shell.activateById(target.panel.id);},
          example:async(entry,index)=>{
            const original=target;checkTarget(original);const example=entry?.type.examples[index];if(typeof example!=='string')throw Error('Unknown example');
            const panel=original.panel,cellIndex=panel.content.widgets.indexOf(original.cell)+1;
            // Insert an explicit code model: the user's default new-cell type may
            // be Markdown. Notebook change signals may replace target immediately.
            panel.content.model.sharedModel.insertCell(cellIndex,{cell_type:'code',source:example,metadata:{},outputs:[],execution_count:null});
            panel.content.activeCellIndex=cellIndex;
            app.shell.activateById(panel.id);
            const cell=panel.content.activeCell;await cell?.ready;
            if(panel.isDisposed||tracker.currentWidget!==panel||panel.content.activeCell!==cell)return;
            target=captureTarget(panel);cell?.editor?.focus();
          }
        });
        // Notebook 7 reserves its main area for a single notebook. Its right
        // sidebar is the supported home for auxiliary widgets.
        app.shell.add(widget,labShell?'main':'right',labShell?{mode:'split-right'}:undefined);
      }
      widget.setMode(mode,false);
      // Notebook 7.2 toggles an already-open sidebar in activateById. Merely
      // focus the visible reference so repeated toolbar clicks cannot hide it.
      if(!labShell&&widget.isVisible)widget.activate();else app.shell.activateById(widget.id);
    };
    app.commands.addCommand(command,{label:'Bhash: Standard Library Lookup · मानक पुस्तकालय',caption:'Search Bhash classes and functions in Hindi or English',execute:()=>open()});
    palette.addItem({command,category:'Bhash · भाष'});
    app.commands.addCommand('bhash:language-reference',{label:'Bhash: Keywords and Constructs · शब्द और ढाँचे',caption:'Search and insert Bhash keywords and editable constructs',execute:()=>open(undefined,'language')});
    palette.addItem({command:'bhash:language-reference',category:'Bhash · भाष'});
    const attach=(_sender,panel)=>{
      const button=new ToolbarButton({label:'📚 पुस्तकालय',tooltip:'Bhash: Standard Library Lookup · हिन्दी / English',onClick:()=>open(panel)});
      const languageButton=new ToolbarButton({label:'⌨ शब्द/ढाँचे',tooltip:'Bhash: Keywords and Constructs · शब्द और ढाँचे',onClick:()=>open(panel,'language')});
      if(!panel.toolbar.insertAfter('cellType','bhash-language',languageButton))panel.toolbar.addItem('bhash-language',languageButton);
      if(!panel.toolbar.insertAfter('bhash-language','bhash-library',button))panel.toolbar.addItem('bhash-library',button);
      const refresh=()=>{button.node.hidden=languageButton.node.hidden=!isBhash(panel);};
      panel.sessionContext.kernelChanged.connect(refresh);panel.context.ready.then(refresh);
      panel.disposed.connect(()=>{panel.sessionContext.kernelChanged.disconnect(refresh);if(target?.panel===panel)target=null;if(watched?.panel===panel)unwatch();});
      refresh();
    };
    tracker.widgetAdded.connect(attach);tracker.forEach(panel=>attach(null,panel));
    tracker.currentChanged.connect((_sender,panel)=>{if(panel)capture(panel);});
    tracker.activeCellChanged.connect(()=>{if(tracker.currentWidget)capture(tracker.currentWidget);});
    // Selection changes while the user is editing refresh the destination. Merely
    // focusing the reference must not silently accept source edits from elsewhere.
    document.addEventListener('focusin',event=>{
      const panel=tracker.currentWidget;
      if(panel?.node.contains(event.target))capture(panel);
    });
  }
};
