// SPDX-License-Identifier: Apache-2.0
// Fast editor-only test. Compilation/execution is covered by playground_smoke_test.cjs.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const keywordChecks = require('./playground_keyword_checks.cjs');
const root = path.resolve('playground/dist');
const server = http.createServer((request,response) => {
  if(request.url === '/api/config') { response.setHeader('Content-Type','application/json'); response.end(JSON.stringify({runOrigin:'http://127.0.0.1:1',executionSeconds:15})); return; }
  const file=path.resolve(root,'.'+decodeURIComponent(request.url.split('?')[0] === '/' ? '/index.html' : request.url.split('?')[0]));
  if(!file.startsWith(root+path.sep) || !fs.existsSync(file)) {response.writeHead(404).end();return;}
  response.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.svg')?'image/svg+xml':file.endsWith('.json')?'application/json':'text/html');
  response.end(fs.readFileSync(file));
});
(async () => {
  let browser;
  try {
    fs.mkdirSync('target/keyword-ui',{recursive:true});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    browser=await chromium.launch({headless:true});
    const page=await browser.newPage({viewport:{width:1280,height:900}});
    const errors=[]; page.on('pageerror',error=>errors.push(error.message));
    let compileRequests=0; page.on('request',request=>{if(request.url().endsWith('/api/compile'))compileRequests++;});
    await page.goto('http://127.0.0.1:'+server.address().port);
    await page.locator('#keyword-list button').first().waitFor({state:'attached'});
    await keywordChecks(page);
    await require('./playground_library_checks.cjs')(page);
    assert.equal(compileRequests,0,'Choosing keywords must not execute source');
    await page.reload();
    await page.locator('#keyword-list button').first().waitFor({state:'attached'});
    assert.equal(await page.locator('.cm-content').innerText(),'लिखो(मानक.गणित.निरपेक्ष(-५));');
    await page.evaluate(() => navigator.serviceWorker.ready);
    await page.reload(); // acquire the installed offline editor shell
    await page.context().setOffline(true);
    await page.reload();
    await page.locator('#keyword-list button').first().waitFor({state:'attached'});
    await page.locator('#library-open').click();
    await page.locator('#library-search').fill('split');
    await page.locator('.library-result').filter({hasText:'बाँटो'}).waitFor();
    await page.locator('#library-search').press('Escape');
    await page.locator('.cm-content').press((process.platform==='darwin'?'Meta':'Control')+'+a');
    await page.locator('.cm-content').press('Backspace');
    await page.waitForFunction(()=>JSON.parse(localStorage.getItem('bhash-draft'))?.source==='');
    await page.locator('.keyword-picker summary').click();
    await page.getByRole('button',{name:'स्थिर · Constant',exact:true}).click();
    assert.equal(await page.locator('.cm-content').innerText(),'स्थिर ');
    await page.context().setOffline(false);
    await page.locator('.keyword-picker summary').click();
    await page.screenshot({path:'target/keyword-ui/desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:'target/keyword-ui/mobile.png',fullPage:true});
    assert.deepEqual(errors,[]);
    console.log('PASS keyword editing does not compile/run, survives draft reload and works offline');
  } finally { await browser?.close(); await new Promise(resolve=>server.close(resolve)); }
})().catch(error=>{console.error(error);process.exitCode=1;});
