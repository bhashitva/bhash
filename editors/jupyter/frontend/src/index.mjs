// SPDX-License-Identifier: Apache-2.0
import {ICommandPalette,ToolbarButton,Clipboard} from '@jupyterlab/apputils';
import {ILabShell} from '@jupyterlab/application';
import {INotebookTracker,NotebookActions} from '@jupyterlab/notebook';
import {Widget} from '@lumino/widgets';
import {mountLibraryBrowser} from '../../../../playground/src/library-browser.mjs';
import {libraryIndex,librarySnippet,signature} from '../../../../playground/src/library-model.mjs';
import catalog from '../../../../playground/public/library.json';
import {captureTarget,checkTarget,insertCall,isBhash} from './insert.mjs';

const command='bhash:standard-library';
const entries=new Map(libraryIndex(catalog).entries.map(entry=>[entry.id,entry]));
class LibraryWidget extends Widget {
  constructor(actions) {
    super();this.id='bhash-standard-library';this.title.label='भाष पुस्तकालय · Bhash Library';this.title.closable=true;
    this.node.style.height='100%';this.node.style.minWidth='300px';
    this.root=this.node.attachShadow({mode:'open'});
    const style=document.createElement('style');style.textContent=BHASH_LIBRARY_STYLE;
    const body=document.createElement('div');body.innerHTML=BHASH_LIBRARY_HTML;
    body.style.height='100%';this.root.append(style,body);this.actions=actions;
  }
  onAfterAttach() {
    if(this.mounted)return;this.mounted=true;
    const notice=text=>this.root.querySelector('#library-notice').textContent=text;
    const act=callback=>{try{callback();notice('डाला गया; अपने मान भरें। कक्ष अपने-आप नहीं चला। · Inserted without running.');}catch(error){notice(error.message);}};
    mountLibraryBrowser((_template,id)=>act(()=>this.actions.insert(entries.get(id))),()=>{}, {
      root:this.root,catalog,embedded:true,onClose:()=>this.close(),
      insertionHint:'चुना तर्क भरें; शेष तर्क और वस्तु को अपने मानों से बदलें। कक्ष अपने-आप नहीं चलता। · Replace the argument placeholders before running.',
      copyText:async(_text,id)=>Clipboard.copyToSystem(signature(entries.get(id).type,entries.get(id).member)),
      insertExample:(id,index)=>act(()=>this.actions.example(entries.get(id),index))
    });
  }
  onActivateRequest(){this.root.querySelector('#library-search')?.focus();}
  onCloseRequest(){this.dispose();}
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
    const open=panel=>{
      if(panel)capture(panel);else if(tracker.currentWidget)capture(tracker.currentWidget);
      if(!widget||widget.isDisposed) {
        widget=new LibraryWidget({
          insert:entry=>{if(!entry)throw Error('Unknown library entry');target=insertCall(target,librarySnippet(entry.type,entry.member));app.shell.activateById(target.panel.id);},
          example:(entry,index)=>{
            checkTarget(target);const example=entry?.type.examples[index];if(typeof example!=='string')throw Error('Unknown example');
            const panel=target.panel;panel.content.activeCellIndex=panel.content.widgets.indexOf(target.cell);
            const cellIndex=panel.content.activeCellIndex+1;
            NotebookActions.insertBelow(panel.content);
            const model=panel.content.model.cells.get(cellIndex);
            if(!model||model===target.cell.model)throw Error('नया कक्ष नहीं बन सका। · Could not insert an example cell.');
            model.sharedModel.setSource(example);
            app.shell.activateById(panel.id);
            target=captureTarget(panel);panel.content.activeCell.editor?.focus();
          }
        });
        // Notebook 7 reserves its main area for a single notebook. Its right
        // sidebar is the supported home for auxiliary widgets.
        app.shell.add(widget,labShell?'main':'right',labShell?{mode:'split-right'}:undefined);
      }
      app.shell.activateById(widget.id);
    };
    app.commands.addCommand(command,{label:'Bhash: Standard Library Lookup · मानक पुस्तकालय',caption:'Search Bhash classes and functions in Hindi or English',execute:()=>open()});
    palette.addItem({command,category:'Bhash · भाष'});
    const attach=(_sender,panel)=>{
      const button=new ToolbarButton({label:'📚 पुस्तकालय',tooltip:'Bhash: Standard Library Lookup · हिन्दी / English',onClick:()=>open(panel)});
      if(!panel.toolbar.insertAfter('cellType','bhash-library',button))panel.toolbar.addItem('bhash-library',button);
      const refresh=()=>{button.node.hidden=!isBhash(panel);};
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
