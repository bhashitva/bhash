// SPDX-License-Identifier: Apache-2.0
// Real end-to-end checks: HTTP submission -> compiler process -> .NET WASM in a cross-origin iframe.
const {spawn} = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const server = spawn('java', ['-cp', 'target/bhash-compiler-0.1.0-SNAPSHOT.jar', 'dev.bhash.playground.PlaygroundServer'], {
  env: {...process.env, PORT:'0', BHASH_ORIGIN:'http://localhost:0', BHASH_RUN_ORIGIN:'http://127.0.0.1:0',
    BHASH_DATA: path.resolve('target/playground-e2e'), BHASH_WASM_OFFLINE:'true'}, stdio:['ignore','pipe','pipe']
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
