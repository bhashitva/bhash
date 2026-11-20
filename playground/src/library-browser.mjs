// SPDX-License-Identifier: Apache-2.0
import {libraryIndex, searchLibrary, signature, typeName, kindNames, librarySnippet, documentationUrl} from './library-model.mjs';

// Render catalog strings as text, never as HTML. Nothing here runs source code.
const element = (tag, text='', className='') => { const node=document.createElement(tag); node.textContent=text; if(className)node.className=className; return node; };
const action = (label, callback, primary=false) => { const button=element('button',label,primary?'library-insert':'');button.type='button';button.addEventListener('click',callback);return button; };
const section = (heading, ...children) => { const node=element('section');node.append(element('h3',heading),...children);return node; };
const link = (label, path) => { const a=element('a',label);a.href=documentationUrl(path);a.target='_blank';a.rel='noopener noreferrer';return a; };

export function mountLibraryBrowser(insert, rememberSelection) {
  const $=id=>document.getElementById(id);
  const dialog=$('library-dialog'), trigger=$('library-open'), list=$('library-results'), detail=$('library-detail');
  let index, loading, matches=[], selected, limit=60;
  const close=()=>dialog.close();
  const use=(type,member)=>{dialog.close('insert');insert(librarySnippet(type,member));};
  const copy=async text=>{try{await navigator.clipboard.writeText(text);$('library-notice').textContent='हस्ताक्षर कॉपी हुआ। · Signature copied.';}catch{$('library-notice').textContent='कॉपी उपलब्ध नहीं है; नीचे दिया हस्ताक्षर चुनकर कॉपी करें।';}};
  function memberButton(type, member, position) {
    const button=action('',()=>select({type,member,id:`member:${type.name}:${position}`},true));
    button.className='library-member';button.append(element('strong',`${member.name} · ${member.english}`),element('code',signature(type,member)));
    return button;
  }
  function select(entry, focus=false) {
    selected=entry; const {type,member}=entry;
    $('library-body').dataset.detail='true';
    for(const button of list.querySelectorAll('button[data-id]'))button.setAttribute('aria-pressed',String(button.dataset.id===entry.id));
    const heading=element('h2',member?member.name:type.name); heading.id='library-detail-title';heading.tabIndex=-1;
    const top=element('div','','library-detail-top');
    const back=action('← '+type.name,()=>select({type,member:null,id:`type:${type.name}`},true));
    if(member)top.append(back);
    top.append(element('p',member?typeName(type,true):`मानक · ${kindNames[type.kind]}`,'library-kicker'),heading,
      element('p',member?member.english:type.english,'library-english'));
    detail.replaceChildren(top);
    detail.scrollTop=0;
    detail.append(element('p',member?member.description:type.description,'library-description'));
    if(type.browser==='host') detail.append(element('p','स्थानीय लक्ष्य · इस API की होस्ट सुविधाएँ Playground में पूरी तरह उपलब्ध नहीं हैं। JVM, .NET या native लक्ष्य और मार्गदर्शिका देखें।','library-target'));
    else if(type.browser==='check') detail.append(element('p','लक्ष्य की सीमाएँ · सभी पुस्तकालय API ब्राउज़र में नहीं चलतीं। उपयोग से पहले नीचे दी मार्गदर्शिका पढ़ें।','library-target'));
    else detail.append(element('span','ब्राउज़र में उपलब्ध · Browser','library-badge'));
    if(type.internal || member?.internal)detail.append(element('p','रनटाइम सहायक API · सामान्य अनुप्रयोग के लिए ऊपर के सार्वजनिक आवरणों को प्राथमिकता दें।','library-target'));
    const actions=element('div','','library-detail-actions');
    actions.append(action(member?'कॉल / नाम डालें':'प्रकार का नाम डालें',()=>use(type,member),true),action('हस्ताक्षर कॉपी करें',()=>copy(signature(type,member))));
    detail.append(actions,element('pre',signature(type,member),'library-signature'));
    detail.append(element('p',member&&!member.shared&&member.kind!=='constructor'?'वस्तु को अपनी variable से बदलें। चुने हुए तर्क और प्रकार भरें; Tab से अगले स्थान पर जाएँ।':'चुने हुए तर्क और प्रकार भरें; Tab से अगले स्थान पर जाएँ। इससे क्रमानुदेश अपने आप नहीं चलता।','library-hint'));
    if(member) {
      if(member.kind==='field')detail.append(element('span',member.mutable?'पढ़ना और लिखना · Mutable':'केवल पढ़ना · Read only','library-badge'));
      if(member.origin!==type.name)detail.append(element('p',`विरासत से: ${member.origin}`,'library-hint'));
      if(member.async)detail.append(element('p','असमकालिक कॉल · प्रतीक्षा का उपयोग असमकालिक फलन के भीतर करें।','library-target'));
      else if(member.result.startsWith('कार्यफल<'))detail.append(element('p','यह कॉल कार्य लौटाती है। उसका फल लेने के लिए असमकालिक फलन में प्रतीक्षा उपयोग करें।','library-hint'));
      if(member.parameters.length) {
        const table=element('table'); const head=element('tr');head.append(element('th','मापदंड'),element('th','प्रकार'));table.append(head);
        for(const parameter of member.parameters){const row=element('tr');row.append(element('td',parameter.name),element('td',parameter.type));table.append(row);}
        detail.append(section('मापदंड · Parameters',table));
      }
      if(member.kind!=='constructor')detail.append(section(member.kind==='field'?'क्षेत्र का प्रकार · Field type':'फल का प्रकार · Returns',element('code',member.result)));
    } else {
      if(type.parents.length)detail.append(element('p','आधार और अनुबंध: '+type.parents.join(', '),'library-hint'));
      const visible=type.members.map((m,i)=>[m,i]).filter(([m])=>$('library-internal').checked||!m.internal);
      for(const [kind,label] of [['constructor','निर्माता · Constructors'],['method','फलन · Methods'],['field','क्षेत्र / मान · Fields']]) {
        const members=visible.filter(([m])=>m.kind===kind);
        if(members.length)detail.append(section(label,...members.map(([m,i])=>memberButton(type,m,i))));
      }
    }
    if(type.notes)detail.append(section('व्यवहार और सीमाएँ',element('p',type.notes)));
    if(type.examples.length)detail.append(section(`${type.name} के कोड अंश · Examples`,element('p','ये प्रकार के उपयोग के अंश हैं; आवश्यक आयात और तैयारी के लिए पूरा संदर्भ देखें।'),...type.examples.map(code=>element('pre',code))));
    const links=element('div','','library-doc-links');links.append(link('पूरा हिन्दी API संदर्भ ↗',type.reference),link('विषय की मार्गदर्शिका ↗',type.guide));detail.append(links);
    detail.append(element('p','हस्ताक्षर default legacy profile के हैं। Strict रिक्त-सुरक्षा में nullable सीमाओं के लिए प्रकार की टिप्पणियाँ और रिक्त-सुरक्षा मार्गदर्शिका देखें।','library-hint'),link('रिक्त-सुरक्षा के नियम ↗','tutorial/null-safety/README.md'));
    if(focus)heading.focus({preventScroll:true});
  }
  function render(keepScroll=false) {
    if(!index)return;
    const scroll=keepScroll?list.scrollTop:0;
    matches=searchLibrary(index,{query:$('library-search').value,kind:$('library-kind').value,group:$('library-group').value,internal:$('library-internal').checked});
    list.replaceChildren();
    $('library-count').textContent=`${matches.length} नतीजे · results`;
    for(const entry of matches.slice(0,limit)) {
      const button=action('',()=>select(entry,true));button.className='library-result';button.dataset.id=entry.id;
      button.setAttribute('aria-pressed',String(entry.id===selected?.id));
      button.append(element('span',entry.member?`${entry.member.kind==='field'?'क्षेत्र':'फलन'} · ${entry.type.name}`:kindNames[entry.type.kind],'library-result-kind'),
        element('strong',entry.member?entry.member.name:entry.type.name),element('span',entry.member?entry.member.english:entry.type.english));
      if(entry.member)button.append(element('code',`(${entry.member.parameters.map(p=>p.type).join(', ')}) → ${entry.member.result||typeName(entry.type)}`,'library-result-parameters'));
      list.append(button);
    }
    if(!matches.length)list.append(element('p','कोई नतीजा नहीं। खोज या विषय बदलें; आंतरिक API के लिए नीचे का विकल्प चुनें।','library-empty'));
    if(matches.length>limit)list.append(action('और नतीजे दिखाएँ · More',()=>{
      const previous=limit;limit+=60;render(true);list.querySelectorAll('button[data-id]')[previous]?.focus();
    }));
    list.scrollTop=scroll;
    const current=matches.find(entry=>entry.id===selected?.id);
    if(matches.length&&(!keepScroll||!current))select(current||matches[0]);
    if(!matches.length){selected=null;detail.replaceChildren(element('p','किसी वर्ग या फलन को चुनकर उसका विवरण देखें।','library-empty'));}
    // On phones show results until a deliberate selection, not the automatic preview.
    $('library-body').dataset.detail='false';
  }
  function refresh(){limit=60;render();}
  for(const id of ['library-search','library-kind','library-group','library-internal'])$(id).addEventListener(id==='library-search'?'input':'change',refresh);
  $('library-back').addEventListener('click',()=>{
    $('library-body').dataset.detail='false';
    (list.querySelector('[aria-pressed="true"]')||list.querySelector('button[data-id]')||$('library-search')).focus();
  });
  $('library-close').addEventListener('click',close);
  dialog.addEventListener('keydown',event=>{
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();}
  },{capture:true});
  trigger.addEventListener('click',async()=>{
    rememberSelection();
    // Native dialog restores its opener on close. A delayed close handler must
    // not steal focus back after the user has already returned to the editor.
    dialog.returnValue='';
    dialog.showModal();$('library-search').focus();$('library-notice').textContent='';
    if(index){render();return;}
    $('library-count').textContent='पुस्तकालय लोड हो रहा है…';
    try {
      loading??=fetch('./library.json').then(response=>{if(!response.ok)throw Error('Library unavailable');return response.json();}).then(catalog=>libraryIndex(catalog));
      index=await loading;
      if($('library-group').options.length===1)for(const group of [...new Set(index.types.map(type=>type.group))])$('library-group').add(new Option(group,group));
      $('library-total').textContent=`${index.types.length} प्रकार · ${index.entries.length-index.types.length} सदस्य`;
      render();
    } catch {loading=null;$('library-count').textContent='संदर्भ लोड नहीं हुआ। इंटरनेट जोड़कर बंद करें और फिर खोलें।';}
  });
}
