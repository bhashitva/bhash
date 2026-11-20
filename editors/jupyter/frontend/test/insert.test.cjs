// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');const assert=require('node:assert/strict');
const {plainSnippet,captureTarget,insertCall,checkTarget,isBhash}=require('../lib/insert.cjs');
function fixture(source='लिखो(पुराना);',start=5,end=11) {
  let value=source,range={start:{line:0,column:start},end:{line:0,column:end}},stops=0;
  const sharedModel={getSource:()=>value,setSource:text=>{value=text;},undoManager:{stopCapturing:()=>stops++}};
  const editor={getSelections:()=>[range],getOption:()=>false,getOffsetAt:p=>p.column,getPositionAt:column=>({line:0,column}),setSelection:r=>{range=r;},replaceSelection:text=>{value=value.slice(0,range.start.column)+text+value.slice(range.end.column);},focus(){}};
  const cell={editor,model:{type:'code',sharedModel}};
  const panel={isDisposed:false,sessionContext:{session:{kernel:{name:'bhash'}}},content:{activeCell:cell,widgets:[cell],model:{getMetadata:()=>({name:'bhash'}),readOnly:false}}};
  return {panel,cell,sharedModel,target:()=>captureTarget(panel),value:()=>value,range:()=>range,stops:()=>stops};
}
test('placeholder text is plain Bhash, with UTF-16 offsets selecting the first argument',()=>{
  assert.deepEqual(plainSnippet('😀मानक.गणित.निरपेक्ष(${अंक})'),{text:'😀मानक.गणित.निरपेक्ष(अंक)',selection:{start:21,end:24}});
  assert.deepEqual(plainSnippet('मानक.समुच्चय<${त}>'),{text:'मानक.समुच्चय<त>',selection:{start:13,end:14}});
  assert.equal(plainSnippet('मानक.गणित.पाई').selection,null);
});
test('captured selection is replaced and only the first placeholder selected, without executing',()=>{
  const f=fixture('पुराना',0,6);const target=insertCall(f.target(),'मानक.पाठ्य.बाँटो(${पाठ}, ${विभाजक})');
  assert.equal(f.value(),'मानक.पाठ्य.बाँटो(पाठ, विभाजक)');
  assert.equal(f.value().slice(f.range().start.column,f.range().end.column),'पाठ');
  assert.equal(f.stops(),2);assert.equal(target.source,f.value());
});
test('backwards selections replace the same range safely',()=>{
  const f=fixture('कपुरानाख',7,1);insertCall(f.target(),'${नया}');assert.equal(f.value(),'कनयाख');
});
test('changed, closed, removed, Markdown and read-only destinations are rejected',()=>{
  const f=fixture();const old=f.target();f.sharedModel.setSource('बदला');assert.throws(()=>insertCall(old,'foo'),/Cell changed/);
  const fresh=f.target();f.panel.content.model.readOnly=true;assert.throws(()=>checkTarget(fresh),/read-only/);f.panel.content.model.readOnly=false;
  f.panel.content.widgets=[];assert.throws(()=>checkTarget(fresh),/Select a Bhash/);f.panel.content.widgets=[f.cell];
  f.panel.isDisposed=true;assert.throws(()=>checkTarget(fresh));f.panel.isDisposed=false;
  f.cell.model.type='markdown';assert.equal(f.target(),null);
});
test('Python kernels cannot receive insertion and offline Bhash metadata permits browsing',()=>{
  const f=fixture();f.panel.sessionContext.session.kernel.name='python3';assert.equal(isBhash(f.panel),false);assert.throws(()=>checkTarget(f.target()));
  f.panel.sessionContext.session=null;assert.equal(isBhash(f.panel),true);
});
test('multiple cursors are rejected rather than silently losing a selection',()=>{
  const f=fixture();const t=f.target();t.selections.push(t.selections[0]);assert.throws(()=>insertCall(t,'foo'),/one cursor/);assert.equal(f.value(),'लिखो(पुराना);');
});
