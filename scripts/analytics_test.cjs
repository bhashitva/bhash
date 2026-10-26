// SPDX-License-Identifier: Apache-2.0
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync('deploy/analytics/bhash-analytics.js','utf8');
function setup(host='play.bhashitva.org', privacy={}) {
  const scripts=[], listeners={};
  const window={};
  const context=vm.createContext({window, location:{hostname:host,href:`https://${host}/?code=SECRET&email=x@example.org&utm_source=linkedin#code=SECRET`},
    navigator:privacy, document:{referrer:'https://example.org/path?secret=SECRET#input',
      addEventListener:(name,fn)=>listeners[name]=fn, createElement:()=>({}), head:{append:s=>scripts.push(s)}}, URL, Date, Set});
  vm.runInContext(code,context);
  return {window,scripts,listeners,context};
}
test('one shared stream, private URL fields stripped, duplicate initialization suppressed',()=>{
  for(const host of ['bhashitva.org','play.bhashitva.org','chess.bhashitva.org']) {
    const c=setup(host);const config=Array.from(c.window.dataLayer[2]);
    assert.equal(config[1],'G-J31MR361WE');
    assert.equal(config[2].cookie_domain,'bhashitva.org');
    assert.equal(config[2].allow_google_signals,false);
    assert.equal(config[2].page_location,`https://${host}/?utm_source=linkedin`);
    assert.equal(config[2].page_referrer,'https://example.org/path');
    assert.equal(c.scripts.length,1);vm.runInContext(code,c.context);assert.equal(c.scripts.length,1);
    c.window.bhashAnalytics('playground_run',{mode:'scratch',source:'SECRET',input:'SECRET',error:'SECRET'});
    assert.equal(c.window.dataLayer.length,4);
    assert.ok(!JSON.stringify(Array.from(c.window.dataLayer[3])).includes('SECRET'));
    c.window.bhashAnalytics('arbitrary_event',{source:'SECRET'});assert.equal(c.window.dataLayer.length,4);
    c.window.bhashAnalytics('playground_run_complete',{outcome:'SECRET'});
    assert.equal(c.window.dataLayer[4][2].outcome,undefined);
  }
});
test('development/runner hosts and browser privacy opt-outs never load Google',()=>{
 for(const c of [setup('localhost'),setup('run.bhashitva.org'),setup('play.bhashitva.org',{doNotTrack:'1'}),setup('play.bhashitva.org',{globalPrivacyControl:true})]) {
  assert.equal(c.scripts.length,0);assert.equal(c.window.bhashAnalytics,undefined);
 }
});
test('donation and resource events never send link URL or text',()=>{
 const c=setup('bhashitva.org');
 for(const href of ['https://www.zeffy.com/donation?name=SECRET','https://github.com/bhashitva/bhash/tree/main/lessons#SECRET']) {
  c.listeners.click({target:{closest:()=>({href})}});
 }
 assert.equal(c.window.dataLayer[3][1],'donation_click');
 assert.equal(c.window.dataLayer[4][1],'resource_click');
 assert.equal(c.window.dataLayer[4][2].destination,'lessons');
 assert.ok(!JSON.stringify(c.window.dataLayer).includes('SECRET'));
});
