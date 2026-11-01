// SPDX-License-Identifier: Apache-2.0
// Controlled loopback before/after measurements. These are not public-WAN timings.
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const destination=path.resolve('target/playground-performance');fs.mkdirSync(destination,{recursive:true});
const host=path.resolve('target/browser-publish-profile/host');
const results={environment:'GitHub CI, Chromium headless, loopback HTTP, no throttling or compression',runs:[]};
const loop='लिखो("आरंभ"); मान इ: पूर्णांक = ०; मान योग: पूर्णांक = ०; जबतक (इ < २००००००) { योग = योग + (इ * १७ + १३) % १००९; इ = इ + १; } लिखो(योग);';
const hello='लिखो("नमस्ते, दुनिया!");';
const cases=[['hello-cold-1',hello+' // a'],['hello-warm-1',hello+' // a'],['hello-warm-2',hello+' // a'],['hello-warm-3',hello+' // a'],['hello-cold-2',hello+' // b'],['hello-cold-3',hello+' // c'],['cpu-cold',loop],['cpu-warm-1',loop],['cpu-warm-2',loop],['cpu-warm-3',loop]];
async function bounded(promise, milliseconds, label) {
  let timer;
  try { return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(label)),milliseconds);timer.unref();})]); }
  finally {clearTimeout(timer);}
}
async function measure(kind,browser) {
  const env={...process.env,PORT:'0',BHASH_ORIGIN:'http://localhost:0',BHASH_RUN_ORIGIN:'http://127.0.0.1:0',BHASH_WASM_OFFLINE:'true',BHASH_DATA:path.resolve(`target/performance-${kind}-data`)};
  delete env.BHASH_BROWSER_SHARED;delete env.BHASH_WASM_PROFILE;delete env.BHASH_BROWSER_HOST;
  if(kind==='prebuilt')env.BHASH_BROWSER_HOST=host;
  const server=spawn('java',['-cp','target/bhash-compiler-0.1.0-SNAPSHOT.jar','dev.bhash.playground.PlaygroundServer'],{env,stdio:['ignore','pipe','pipe']});
  const serverExited=new Promise(resolve=>server.once('exit',resolve));
  let logs='',context;
  const resources=[],pending=[];
  try {
    const origin=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(Error('Server startup timeout '+logs)),30000);
      server.stdout.on('data',chunk=>{logs+=chunk;const match=logs.match(/listening at (http:\/\/localhost:\d+)/);if(match){clearTimeout(timer);resolve(match[1]);}});
      server.stderr.on('data',chunk=>{logs+=chunk;});server.on('exit',code=>{clearTimeout(timer);reject(Error(`Server exited ${code}: ${logs}`));});
    });
    context=await browser.newContext({viewport:{width:1280,height:900}});
    // HAR finalization can retain abandoned worker requests after the iframe is
    // destroyed. Record public request timings/sizes without a teardown barrier.
    context.on('requestfinished',request=>{
      const url=new URL(request.url());
      if(url.hostname!=='127.0.0.1'||!/^\/(artifacts|browser-host)\//.test(url.pathname))return;
      const timing=request.timing();
      pending.push(bounded(request.sizes(),2000,'Resource size unavailable').then(sizes=>resources.push({url:request.url(),timing,sizes})).catch(()=>resources.push({url:request.url(),timing,sizes:null})));
    });
    const page=await context.newPage();await page.goto(origin);await page.waitForFunction(()=>!document.querySelector('#run').disabled);
    await page.evaluate(()=>{
      window.measurements=[];let active,frame;
      document.querySelector('#run').addEventListener('click',()=>{active={click:performance.now(),timeOrigin:performance.timeOrigin,messages:[],api:[]};window.measurements.push(active);frame=undefined;},true);
      const original=window.fetch;
      window.fetch=function(...args){const run=active;const promise=original.apply(this,args);if(run&&/\/api\/(compile|jobs\/)/.test(String(args[0])))promise.then(response=>response.clone().json()).then(job=>run.api.push({t:performance.now(),job}));return promise;};
      addEventListener('message',event=>{
        if(!active||event.origin!==location.origin.replace('localhost','127.0.0.1'))return;
        const iframe=document.querySelector('iframe');if(event.data?.type==='ready'&&event.source===iframe?.contentWindow)frame=event.source;
        if(event.source===frame)active.messages.push({t:performance.now(),type:event.data?.type});
      },true);
      new MutationObserver(()=>{if(active&&active.output===undefined&&document.querySelector('#output').textContent)active.output=performance.now();}).observe(document.querySelector('#output'),{childList:true,subtree:true,characterData:true});
    });
    for(const [label,source] of cases) {
      await page.locator('.cm-content').fill(source);await page.click('#run');
      await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Finished')||document.querySelector('#status').textContent.startsWith('Could not run'),null,{timeout:240000});
      assert.ok((await page.locator('#status').textContent()).startsWith('Finished (exit 0)'),await page.locator('#errors').textContent());
      const expected=label.startsWith('cpu')?'आरंभ\n१००७९९३८३२\n':'नमस्ते, दुनिया!\n';
      assert.equal((await page.locator('#output').textContent()).replaceAll('\r\n','\n'),expected);
      const sample=await page.evaluate(()=>window.measurements.at(-1));results.runs.push({kind,label,...sample});
      fs.writeFileSync(path.join(destination,'measurements.json'),JSON.stringify(results,null,2));
      console.log(`${kind} ${label}: ${((sample.output-sample.click)/1000).toFixed(3)}s`);
    }
  } finally {
    console.log(`${kind}: closing browser context`);
    try {
      await Promise.allSettled(pending);
      fs.writeFileSync(path.join(destination,`${kind}-resources.json`),JSON.stringify(resources,null,2));
      if(context)await bounded(context.close(),20000,'Browser context cleanup timed out');
    } finally {
      console.log(`${kind}: stopping measurement service`);
      server.kill('SIGTERM');
      try {await bounded(serverExited,10000,'Measurement service shutdown timed out');}
      catch(error){server.kill('SIGKILL');await bounded(serverExited,5000,'Measurement service could not be stopped');throw error;}
      fs.writeFileSync(path.join(destination,`${kind}-server.log`),logs);
    }
  }
}
(async()=>{const browser=await chromium.launch({headless:true});try{await measure('baseline',browser);await measure('prebuilt',browser);}finally{await browser.close();}})().catch(error=>{console.error(error);process.exitCode=1;});
