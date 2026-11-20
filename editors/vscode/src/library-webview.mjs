// SPDX-License-Identifier: Apache-2.0
import {mountLibraryBrowser} from '../../../playground/src/library-browser.mjs';
const host=acquireVsCodeApi();let sequence=0;
const pending=new Map();
function request(type,values={}) {
    const id=++sequence;
    return new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});host.postMessage({type,...values,request:id});});
}
addEventListener('message',event=>{const m=event.data;if(m?.type==='focus-search'){const search=document.getElementById('library-search');search.focus();search.select();return;}const wait=pending.get(m?.request);if(!wait)return;pending.delete(m.request);m.ok?wait.resolve():wait.reject(Error(m.error||'Library action failed'));});
const notice=text=>document.getElementById('library-notice').textContent=text;
mountLibraryBrowser((_template,id)=>{request('insert',{id}).then(()=>notice('डाला गया। संपादक में तर्क भरें; Tab से आगे बढ़ें।')).catch(error=>notice(error.message));},()=>{}, {
    embedded:true,catalogUrl:document.getElementById('library-dialog').dataset.catalog,
    copyText:(_text,id)=>request('copy',{id}),
    openDocumentation:path=>request('documentation',{path}).catch(error=>notice(error.message)),
    onClose:()=>host.postMessage({type:'close',request:++sequence})
});
