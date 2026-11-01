// SPDX-License-Identifier: Apache-2.0
// Real end-to-end checks: HTTP submission -> compiler process -> .NET WASM in a cross-origin iframe.
const {spawn} = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const server = spawn('java', ['-cp', 'target/bhash-compiler-0.1.0-SNAPSHOT.jar', 'dev.bhash.playground.PlaygroundServer'], {
  env: {...process.env, PORT:'0', BHASH_ORIGIN:'http://localhost:0', BHASH_RUN_ORIGIN:'http://127.0.0.1:0',
    BHASH_DATA: path.resolve('target/playground-e2e'), BHASH_WASM_OFFLINE:'true', BHASH_REQUESTS_PER_MINUTE:'120'}, stdio:['ignore','pipe','pipe']
});
let logs=''; server.stderr.on('data', data => {logs+=data;});
(async () => {
  let browser;
  try {
    const origin=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error(`Server startup timeout ${logs}`)),20000);
      server.stdout.on('data',data=>{logs+=data; const match=logs.match(/listening at (http:\/\/localhost:\d+)/);if(match){clearTimeout(timer);resolve(match[1]);}});
      server.on('exit',code=>{clearTimeout(timer);reject(new Error(`Server exited ${code}: ${logs}`));});
    });
    browser=await chromium.launch({headless:true});
    const context=await browser.newContext({viewport:{width:1280,height:900}});
    const page=await context.newPage(); const pageErrors=[];page.on('pageerror',error=>pageErrors.push(error.message));
    await page.goto(origin);await page.waitForFunction(()=>!document.querySelector('#run').disabled);
    async function execute(expected, exit=0) {
      await page.click('#run');
      await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Finished'),null,{timeout:240000});
      assert.ok((await page.locator('#status').textContent()).startsWith(`Finished (exit ${exit})`));
      assert.equal((await page.locator('#output').textContent()).replaceAll('\r\n','\n'),expected);
      if(exit===0)assert.equal(await page.locator('#errors').textContent(),'');
    }
    await execute('नमस्ते, दुनिया!\n');console.log('PASS playground hello');
    // Same source reuses the completed build and preserves fresh runtime state.
    await execute('नमस्ते, दुनिया!\n');console.log('PASS playground cached rerun');
    await page.selectOption('#example','factorial');await execute('गुणनफल: १२०\n');console.log('PASS playground input factorial');
    await page.selectOption('#example','types');await execute('तैयार २ ३\nसत्य सत्य\n');console.log('PASS playground records enums');
    await page.selectOption('#mode','program');
    await page.locator('.cm-content').fill('वर्ग गिनती { साझा मान अंक: पूर्णांक = ०; साझा कार्य अगला() -> पूर्णांक { अंक = अंक + १; लौटाओ अंक; } } कार्य मुख्य() -> शून्य { लिखो(गिनती.अगला()); }');
    await execute('१\n');await execute('१\n');console.log('PASS cached program gets fresh static state');
    if(process.env.BHASH_BROWSER_HOST) {
      const responses=await page.request.get(origin+'/api/config');assert.equal(responses.status(),200);
      // Worker resources are not necessarily exposed in parent performance entries;
      // inspect the immutable configuration and validate origins through HTTP instead.
      const artifacts=fs.readdirSync('target/playground-e2e/artifacts');
      const directory=artifacts.find(id=>fs.existsSync(path.join('target/playground-e2e/artifacts',id,'browser-host-config.js')));
      const config=fs.readFileSync(path.join('target/playground-e2e/artifacts',directory,'browser-host-config.js'),'utf8');
      const runtimePath=config.match(/"(\/browser-host\/[a-f0-9]{64}\/)/)[1]+'_framework/dotnet.js';
      assert.equal((await page.request.get(origin+runtimePath)).status(),404);
      const runtime=await page.request.get(origin.replace('localhost','127.0.0.1')+runtimePath);
      assert.equal(runtime.status(),200);assert.ok(runtime.headers()['cache-control'].includes('immutable'));
      for(const name of ['host.properties','references/BhashBrowserHost.dll'])
        assert.equal((await page.request.get(origin.replace('localhost','127.0.0.1')+runtimePath.replace('_framework/dotnet.js',name))).status(),404);
      console.log('PASS immutable runtime URLs and execution-origin isolation');
      let releaseDownload,downloadStarted;
      const pending=new Promise(resolve=>{downloadStarted=resolve;});
      const release=new Promise(resolve=>{releaseDownload=resolve;});
      await context.route('**/BhashProgram.dll',async route=>{downloadStarted();await release;await route.abort().catch(()=>{});});
      await page.click('#run');await Promise.race([pending,new Promise((_,reject)=>{const timer=setTimeout(()=>reject(Error('Program download did not start')),30000);timer.unref();})]);await page.click('#stop');
      releaseDownload();await context.unroute('**/BhashProgram.dll');
      assert.ok((await page.locator('#status').textContent()).startsWith('Stopped'));
      assert.equal(await page.locator('iframe').count(),0);assert.equal(await page.locator('#output').textContent(),'');
      await execute('१\n');console.log('PASS stop during program download and clean recovery');
    }
    await page.screenshot({path:'target/playground-desktop.png',fullPage:true});
    await page.selectOption('#mode','scratch');await page.locator('.cm-content').fill('लिखो(अज्ञात);');await page.click('#run');
    await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Could not run'),null,{timeout:30000});
    assert.ok((await page.locator('#errors').textContent()).includes('playground.bhash:1:'));console.log('PASS playground source diagnostics');
    await page.locator('.cm-content').fill('जबतक (सत्य) {}');await page.click('#run');
    await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Running'),null,{timeout:240000});
    await page.click('#stop');assert.ok((await page.locator('#status').textContent()).startsWith('Stopped'));assert.equal(await page.locator('iframe').count(),0);console.log('PASS playground stop');
    await page.locator('.cm-content').fill('जबतक (सत्य) { लिखो("सीमा"); }');await page.click('#run');
    await page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Finished'),null,{timeout:240000});
    assert.ok((await page.locator('#errors').textContent()).includes('आउटपुट सीमा'));assert.ok((await page.locator('#output').textContent()).length<=65536);console.log('PASS playground output limit');
    await page.selectOption('#example','hello');await page.setViewportSize({width:390,height:844});
    await execute('नमस्ते, दुनिया!\n');assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.locator('.cm-content').fill('स्थिर ');await page.locator('[data-insert=";"]').click();
    assert.ok((await page.locator('.cm-content').textContent()).includes(';'));
    await page.selectOption('#example','hello');await page.reload();await page.waitForFunction(()=>!document.querySelector('#run').disabled);
    assert.ok((await page.locator('.cm-content').textContent()).includes('नमस्ते'));
    await page.screenshot({path:'target/playground-mobile.png',fullPage:true});
    await context.setOffline(true);await page.reload();
    assert.ok((await page.locator('.cm-content').textContent()).includes('नमस्ते'));assert.equal(await page.locator('#run').isEnabled(),false);
    await context.setOffline(false);
    assert.deepEqual(pageErrors,[]);console.log('PASS playground mobile layout, toolbar, draft recovery and offline editor');
    console.log('Playground end-to-end smoke tests passed.');
    await context.close();
  } finally {await browser?.close();server.kill('SIGTERM');}
})().catch(error=>{console.error(error);console.error(logs);process.exitCode=1;});
