// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const textmate=require('vscode-textmate');const oniguruma=require('vscode-oniguruma');
test('TextMate honors Devanagari word boundaries, declarations, strings, comments and numeric scripts',async()=>{
    await oniguruma.loadWASM(fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')).buffer);
    const registry=new textmate.Registry({onigLib:Promise.resolve({createOnigScanner:p=>new oniguruma.OnigScanner(p),createOnigString:s=>new oniguruma.OnigString(s)}),loadGrammar:async()=>textmate.parseRawGrammar(fs.readFileSync(path.join(__dirname,'../syntaxes/bhash.tmLanguage.json'),'utf8'),'bhash.json')});
    const grammar=await registry.loadGrammar('source.bhash');
    const scopes=line=>grammar.tokenizeLine(line).tokens.map(t=>[line.slice(t.startIndex,t.endIndex),t.scopes.join(' ')]);
    assert.ok(scopes('फलन जोड़ो() -> शून्य {}').some(([word,s])=>word==='जोड़ो'&&s.includes('entity.name.function')));
    assert.ok(scopes('फलन जोड़ो() -> शून्य {}').some(([word,s])=>word==='फलन'&&s.includes('keyword')));
    assert.ok(!scopes('कार्य फलनमान').some(([,s])=>s.includes('keyword')));
    const snippets=JSON.parse(fs.readFileSync(path.join(__dirname,'../snippets/bhash.json'),'utf8'));
    assert.deepEqual(snippets.Function.prefix,['function','फलन']);
    assert.equal(snippets['Collection iteration'].body[0],'प्रत्येक (${1:सूची} में ${2:अंक}: ${3:पूर्णांक}) {');
    assert.ok(scopes('स्थिर क: पूर्णांक = १२;').some(([word,s])=>word==='स्थिर'&&s.includes('keyword')));
    assert.ok(!scopes('स्थिरता').some(([,s])=>s.includes('keyword')));
    assert.ok(scopes('वर्ग ग्राहक {}').some(([word,s])=>word==='ग्राहक'&&s.includes('entity.name.type')));
    assert.ok(scopes('लिखो("स्थिर"); // चयन').filter(([word])=>word.includes('स्थिर')).every(([,s])=>s.includes('string')));
    assert.ok(scopes('// चयन').every(([,s])=>s.includes('comment')));
    assert.ok(scopes('१२ 42 ३.१४ 3.14').filter(([,s])=>s.includes('numeric')).length===4);
});
