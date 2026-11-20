// SPDX-License-Identifier: Apache-2.0
// Only source editing: this module never sends an execute request to a kernel.
export function plainSnippet(template) {
  let text='',offset=0,selection=null;
  for(const match of template.matchAll(/\$\{([^}]*)\}/gu)) {
    text+=template.slice(offset,match.index);
    if(!selection)selection={start:text.length,end:text.length+match[1].length};
    text+=match[1];offset=match.index+match[0].length;
  }
  return {text:text+template.slice(offset),selection};
}
export function isBhash(panel) {
  if(!panel||panel.isDisposed||!panel.content.model)return false;
  return panel.sessionContext.session?.kernel?.name==='bhash'||
    (!panel.sessionContext.session?.kernel&&(panel.content.model.getMetadata('kernelspec')?.name==='bhash'||panel.content.model.getMetadata('language_info')?.name==='bhash'));
}
export function captureTarget(panel) {
  if(!isBhash(panel))return null;
  const cell=panel.content.activeCell,editor=cell?.editor;
  if(!editor||cell.model.type!=='code')return null;
  return {panel,cell,source:cell.model.sharedModel.getSource(),selections:editor.getSelections()};
}
export function checkTarget(target) {
  if(!target||!isBhash(target.panel)||!target.panel.content.widgets.includes(target.cell))throw Error('पहले Bhash नोटबुक का code कक्ष चुनें। · Select a Bhash code cell.');
  if(target.panel.content.model.readOnly||target.cell.editor?.getOption('readOnly'))throw Error('यह नोटबुक केवल पढ़ने के लिए है। · The notebook is read-only.');
  if(target.cell.model.sharedModel.getSource()!==target.source)throw Error('कक्ष बदल गया है। स्थान चुनकर पुस्तकालय फिर खोलें। · Cell changed; select it and reopen the lookup.');
}
export function insertCall(target,template) {
  checkTarget(target);
  const {panel,cell}=target,editor=cell.editor;
  if(!editor?.replaceSelection)throw Error('कक्ष का संपादक उपलब्ध नहीं है। · Cell editor is unavailable.');
  if(target.selections.length!==1)throw Error('एक कर्सर या चयन रखें। · Use one cursor or selection.');
  const {text,selection}=plainSnippet(template);
  panel.content.activeCellIndex=panel.content.widgets.indexOf(cell);
  panel.content.mode='edit';
  // Jupyter's editor performs one undoable replacement, retaining notebook history.
  const start=Math.min(editor.getOffsetAt(target.selections[0].start),editor.getOffsetAt(target.selections[0].end));
  const end=Math.max(editor.getOffsetAt(target.selections[0].start),editor.getOffsetAt(target.selections[0].end));
  editor.setSelection({start:editor.getPositionAt(start),end:editor.getPositionAt(end)});
  const undo=cell.model.sharedModel.undoManager;
  undo?.stopCapturing();
  editor.replaceSelection(text);
  undo?.stopCapturing();
  if(selection)editor.setSelection({start:editor.getPositionAt(start+selection.start),end:editor.getPositionAt(start+selection.end)});
  editor.focus();
  return captureTarget(panel);
}
