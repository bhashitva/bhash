// SPDX-License-Identifier: Apache-2.0
import {languageEntries,searchLanguage,keywordGroups} from './language-model.mjs';
import {plainSnippet} from './insert.mjs';
const node=(tag,text,className)=>{const n=document.createElement(tag);if(text)n.textContent=text;if(className)n.className=className;return n;};
export function mountLanguageBrowser(root,words,insert,onClose){
  const entries=languageEntries(words),container=node('section',null,'language-pane');container.id='language-pane';
  const header=node('header',null,'library-header'),heading=node('div');
  heading.append(node('p','भाष संदर्भ · LANGUAGE REFERENCE','library-kicker'),node('h2','शब्द और ढाँचे · Keywords & constructs'),node('p',`${words.length} शब्द · ${entries.length-words.length} ढाँचे`));header.append(heading);
  const close=node('button','✕');close.type='button';close.setAttribute('aria-label','शब्द और ढाँचे बंद करें · Close language reference');close.addEventListener('click',onClose);header.append(close);
  const controls=node('div',null,'language-controls');
  const search=node('input');search.id='language-search';search.type='search';search.maxLength=100;search.placeholder='फलन, class, foreach…';
  const searchLabel=node('label','खोजें · Search');searchLabel.htmlFor=search.id;
  const kind=node('select');kind.id='language-kind';kind.setAttribute('aria-label','नतीजे का प्रकार · Result kind');
  for(const [id,label] of [['all','शब्द और ढाँचे · Both'],['keywords','शब्द · Keywords'],['constructs','ढाँचे · Constructs']])kind.add(new Option(label,id));
  const group=node('select');group.id='language-group';group.setAttribute('aria-label','शब्द विषय · Language topic');
  for(const [id,label] of keywordGroups)group.add(new Option(id==='all'?'सभी विषय · All topics':label,id));group.value='all';
  controls.append(searchLabel,search,kind,group);
  const count=node('p');count.id='language-count';count.setAttribute('role','status');controls.append(count);
  const body=node('div',null,'language-body'),list=node('nav'),detail=node('article');list.id='language-results';list.setAttribute('aria-label','शब्द और ढाँचे के नतीजे · Language results');detail.id='language-detail';
  const notice=node('p',null,'language-notice');notice.id='language-notice';notice.setAttribute('role','status');
  let selected;
  function show(entry,focus=false){
    selected=entry;detail.replaceChildren();
    for(const button of list.querySelectorAll('button'))button.setAttribute('aria-pressed',String(button.dataset.id===entry.id));
    const back=node('button','← खोज के नतीजे · Results','language-back');back.type='button';back.addEventListener('click',()=>{body.dataset.detail='false';[...list.querySelectorAll('button')].find(button=>button.dataset.id===entry.id)?.focus();});
    const title=node('h2',entry.value);title.tabIndex=-1;
    const button=node('button',entry.kind==='keywords'?'शब्द डालें · Insert keyword':'ढाँचा डालें · Insert construct','library-insert');button.id='language-insert';button.type='button';
    button.addEventListener('click',()=>{try{insert(entry);notice.textContent='डाला गया। अपने मान भरें; Undo से वापस लें। कक्ष अपने-आप नहीं चला। · Inserted without running.';}catch(error){notice.textContent=error.message;}});
    detail.append(back,node('p',entry.kind==='keywords'?'कीवर्ड · Keyword':'संपादन योग्य ढाँचा · Editable construct','library-kicker'),title,node('p',entry.english,'library-english'),node('p',entry.description),node('pre',plainSnippet(entry.template).text),button,node('p','पहला स्थान भरें, फिर बाकी पाठ संपादित करें। Tab के अनेक snippet स्थान यहाँ नहीं बनते। · Edit placeholders before running.','library-hint'));
    if(focus){body.dataset.detail='true';title.focus();}
  }
  function render(){
    const found=searchLanguage(entries,search.value,group.value,kind.value);list.replaceChildren();count.textContent=`${found.length} नतीजे · results`;
    for(const entry of found){const button=node('button',null,'library-result');button.type='button';button.dataset.id=entry.id;button.append(node('span',entry.kind==='keywords'?'शब्द · Keyword':'ढाँचा · Construct'),node('strong',entry.value),node('span',entry.english));button.addEventListener('click',()=>show(entry,true));list.append(button);}
    if(found.length)show(found.find(entry=>entry.id===selected?.id)||found[0]);else detail.replaceChildren(node('p','कोई नतीजा नहीं। खोज या विषय बदलें। · No matching entries.'));
    body.dataset.detail='false';
  }
  for(const element of [search,kind,group])element.addEventListener(element===search?'input':'change',render);
  body.append(list,detail);container.append(header,controls,body,notice);root.append(container);render();
  return {element:container,focus:()=>search.focus()};
}
