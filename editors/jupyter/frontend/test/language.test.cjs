// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {languageEntries,searchLanguage}=require('../lib/language.cjs');
const {preparedInsertion}=require('../lib/insert.cjs');
const root=path.resolve(__dirname,'../../../..');
const grammar=fs.readFileSync(path.join(root,'src/main/antlr4/Bhash.g4'),'utf8');
const words=[...grammar.matchAll(/^([A-Z][A-Z_0-9]*)\s*:\s*'([^']+)'/gm)].filter(([, ,value])=>/\p{L}/u.test(value)).map(([,token,value])=>({token,value}));
words.push({token:'UNION_CONTEXTUAL',value:'विकल्पसमूह'},{token:'MATCH_CONTEXTUAL',value:'मिलान'});
const entries=languageEntries(words);
test('all 57 grammar/contextual keywords and all editor constructs have bilingual guidance',()=>{
  assert.equal(words.length,57);assert.equal(entries.filter(e=>e.kind==='keywords').length,words.length);
  assert.equal(entries.filter(e=>e.kind==='constructs').length,25);
  assert.equal(new Set(entries.map(e=>e.id)).size,entries.length);
  for(const entry of entries){assert.match(entry.description,/\p{Script=Devanagari}/u);assert.match(entry.english,/[A-Za-z]/);assert.doesNotMatch(entry.template,/\$\d|\$\{\d+:/);if(entry.kind==='keywords')assert.notEqual(entry.english,entry.token,'every keyword has an English meaning');}
  assert.ok(entries.some(e=>e.value==='फलन'));assert.ok(!entries.some(e=>e.value==='कार्य'));
});
test('Hindi/English search and kind/category filters include constructs and contextual keywords',()=>{
  assert.ok(searchLanguage(entries,'class').some(e=>e.id==='construct:Class'));
  assert.ok(searchLanguage(entries,'प्रत्येक','flow','constructs').some(e=>e.id==='construct:Collection iteration'));
  assert.equal(searchLanguage(entries,'class','all','keywords').length,1);
  assert.ok(searchLanguage(entries,'मि\u200dलान','all','keywords').some(entry=>entry.value==='मिलान'));
  assert.ok(searchLanguage(entries,'let','all','constructs').some(entry=>entry.id==='construct:Variable'));
  assert.equal(searchLanguage(entries,'class','errors').length,0);
  assert.equal(searchLanguage(entries,'<script>alert(1)</script>').length,0);
});
test('keyword insertion preserves identifier boundaries and selected punctuation',()=>{
  assert.equal(preparedInsertion('सत्य','नामदूसरा',3,3,'keywords').text,' सत्य ');
  assert.equal(preparedInsertion('सत्य','लिखो();',5,5,'keywords').text,'सत्य');
});
test('multiline constructs preserve surrounding indentation and placeholder selection',()=>{
  const entry=entries.find(e=>e.id==='construct:While');
  const result=preparedInsertion(entry.template,'    पुराना',4,10,'constructs');
  assert.equal(result.text,'जबतक (शर्त) {\n        \n    }');
  assert.equal(result.text.slice(result.selection.start,result.selection.end),'शर्त');
  const main=entries.find(e=>e.id==='construct:Main function');
  const nested=preparedInsertion(main.template,'\t',1,1,'constructs');
  assert.match(nested.text.slice(nested.selection.start,nested.selection.end),/लिखो/);
});
