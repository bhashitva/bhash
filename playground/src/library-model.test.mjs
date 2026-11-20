// SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {libraryIndex,searchLibrary,signature,librarySnippet,documentationUrl} from './library-model.mjs';
const catalog=JSON.parse(readFileSync(new URL('../public/library.json',import.meta.url),'utf8'));
const index=libraryIndex(catalog);
const type=name=>catalog.types.find(type=>type.name===name);

test('offline catalog covers the documented public types and compression enums',()=>{
  const names=readFileSync(new URL('../../tutorial/standard-library/reference-data/types.txt',import.meta.url),'utf8')
    .split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!line.startsWith('#')).map(line=>line.split('|')[0]);
  assert.deepEqual(catalog.types.map(t=>t.name).sort(),names.concat(['संपीड़नप्रारूप','संपीड़नदोषकोड']).sort());
  assert.equal(new Set(index.entries.map(e=>e.id)).size,index.entries.length);
  for(const entry of index.entries){
    assert.equal(typeof signature(entry.type,entry.member),'string');
    assert.equal(typeof librarySnippet(entry.type,entry.member),'string');
    assert.ok(entry.type.english&&entry.type.description);
    if(entry.member){assert.ok(Array.isArray(entry.member.parameters));assert.ok(entry.member.description&&entry.member.english);}
  }
  assert.ok(index.entries.some(e=>e.member&&e.member.origin!==e.type.name));
  const mapper=type('रूपांतरक').members.find(m=>m.name==='बदलो');
  assert.equal(mapper.english,'Map Value');assert.ok(mapper.description.includes('ख प्रकार'));
});
test('Hindi and English queries find methods and keep overloads distinct',()=>{
  for(const query of ['split','SPLIT','text split','बाँटो','बा\u200dँटो','पाठ्य.बाँटो','मानक.पाठ्य.बाँटो'])
    assert.ok(searchLibrary(index,{query}).some(e=>e.type.name==='पाठ्य'&&e.member?.name==='बाँटो'),query);
  const overloads=searchLibrary(index,{query:'absolute',kind:'members'}).filter(e=>e.type.name==='गणित'&&e.member?.name==='निरपेक्ष');
  assert.equal(overloads.length,2);assert.notEqual(overloads[0].id,overloads[1].id);
  assert.ok(searchLibrary(index,{query:'फ़ाइल'.normalize('NFD'),kind:'types'}).some(e=>e.type.name==='फ़ाइल'));
  assert.equal(searchLibrary(index,{query:'<script>missing</script>'}).length,0);
  assert.ok(searchLibrary(index).every(e=>!e.member&&!e.type.internal));
  assert.ok(searchLibrary(index,{internal:true,kind:'types'}).some(e=>e.type.internal));
  const group=type('गणित').group;
  assert.ok(searchLibrary(index,{group}).every(e=>e.type.group===group));
});
test('call templates use qualified static names and explicit instance/generic placeholders',()=>{
  const text=type('पाठ्य'), split=text.members.find(m=>m.name==='बाँटो');
  assert.equal(librarySnippet(text,split),'मानक.पाठ्य.बाँटो(${मानक}, ${विभाजक})');
  const set=type('समुच्चय');
  assert.match(librarySnippet(set),/^मानक\.समुच्चय<\$\{/u);
  assert.match(librarySnippet(set,set.members.find(m=>m.kind==='constructor')),/^नया मानक\.समुच्चय</u);
  assert.match(librarySnippet(set,set.members.find(m=>m.name==='जोड़ो')), /^\$\{वस्तु\}\.जोड़ो\(/u);
  const asyncHttp=type('असमजाल'),run=asyncHttp.members.find(m=>m.name==='चलाओ');
  assert.ok(run.async);assert.match(librarySnippet(asyncHttp,run),/^प्रतीक्षा /u);
  const task=type('कार्य'),delay=task.members.find(m=>m.name==='विलंब');
  assert.ok(delay.result.startsWith('कार्यफल<'));assert.ok(!librarySnippet(task,delay).startsWith('प्रतीक्षा '));
  const format=type('संपीड़नप्रारूप');assert.equal(librarySnippet(format,format.members.find(m=>m.name==='गज़िप')),'मानक.संपीड़नप्रारूप.गज़िप');
});
test('availability and profile guidance is explicit, and documentation links are valid',()=>{
  assert.equal(type('पाठ्य').browser,'supported');
  for(const name of ['फ़ाइल','डेटा','संपीड़न'])assert.equal(type(name).browser,'host');
  assert.match(type('जेसन').notes,/Strict|nullable/u);
  for(const t of catalog.types)for(const path of [t.reference,t.guide]){
    assert.ok(existsSync(new URL('../../'+path,import.meta.url)),path);
    assert.ok(documentationUrl(path).startsWith('https://github.com/bhashitva/bhash/blob/main/'));
  }
  for(const path of ['javascript:alert(1)','docs/../../bad.md','https://evil.test/a.md'])assert.throws(()=>documentationUrl(path));
});
