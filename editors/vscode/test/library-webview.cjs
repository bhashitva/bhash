// SPDX-License-Identifier: Apache-2.0
const {chromium}=require('playwright');const http=require('node:http');const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../dist');let pageHtml='';
const theme=`:root{--vscode-font-family:system-ui;--vscode-font-size:14px;--vscode-foreground:#ddd;--vscode-editor-background:#1f1f1f;--vscode-descriptionForeground:#b4b4b4;--vscode-panel-border:#666;--vscode-widget-border:#666;--vscode-sideBar-background:#242424;--vscode-list-hoverBackground:#333;--vscode-list-activeSelectionBackground:#264f78;--vscode-list-activeSelectionForeground:#fff;--vscode-focusBorder:#4daafc;--vscode-input-background:#313131;--vscode-input-foreground:#fff;--vscode-button-background:#0078d4;--vscode-button-foreground:#fff;--vscode-button-hoverBackground:#006bbd;--vscode-button-secondaryBackground:#37373d;--vscode-button-secondaryForeground:#fff;--vscode-button-secondaryHoverBackground:#45454b;--vscode-editorWarning-foreground:#cca700;--vscode-textLink-foreground:#4daafc;--vscode-textCodeBlock-background:#292929}html.light{--vscode-foreground:#252526;--vscode-editor-background:#fff;--vscode-descriptionForeground:#616161;--vscode-sideBar-background:#f8f8f8;--vscode-list-hoverBackground:#e8e8e8;--vscode-list-activeSelectionBackground:#0060c0;--vscode-input-background:#fff;--vscode-input-foreground:#222;--vscode-button-secondaryBackground:#e5e5e5;--vscode-button-secondaryForeground:#222;--vscode-button-secondaryHoverBackground:#d8d8d8;--vscode-textCodeBlock-background:#f4f4f4;--vscode-textLink-foreground:#005fb8}`;
const server=http.createServer((req,res)=>{
    if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end(pageHtml);return;}
    if(req.url==='/theme.css'){res.setHeader('Content-Type','text/css');res.end(theme);return;}
    const file=path.resolve(root,'.'+req.url.split('?')[0]);
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'application/json');res.end(fs.readFileSync(file));
});
(async()=>{let browser;try{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    pageHtml=`<!doctype html><html lang="hi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none';style-src 'self';script-src 'nonce-test';connect-src 'self';"><link rel="stylesheet" href="/library-base.css"><link rel="stylesheet" href="/library-theme.css"><link rel="stylesheet" href="/theme.css"></head><body><button id="library-open" hidden></button>${fs.readFileSync(path.join(root,'library.html'),'utf8').replace('id="library-dialog"','id="library-dialog" data-catalog="/library.json"')}<script nonce="test" src="/library-webview.js"></script></body></html>`;
    fs.mkdirSync('target/vscode-library',{recursive:true});browser=await chromium.launch({headless:true});
    const page=await browser.newPage({viewport:{width:1180,height:800}});const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{window.libraryRequests=[];window.acquireVsCodeApi=()=>({postMessage:m=>{window.libraryRequests.push(m);queueMicrotask(()=>window.postMessage({request:m.request,ok:true},'*'));}});});
    await page.goto('http://127.0.0.1:'+server.address().port);await page.locator('.library-result').first().waitFor();
    assert.equal(await page.locator('#library-open').isVisible(),false);
    const search=page.locator('#library-search');await search.fill('text split');
    assert.equal(await page.locator('.library-result').count(),1);await page.locator('.library-result').click();
    assert.ok((await page.locator('#library-detail').innerText()).includes('सूची<पाठ>'));
    await page.locator('#library-detail .library-insert').click();
    assert.equal(await page.locator('#library-dialog').isVisible(),true,'reference remains open after insertion');
    const inserted=await page.evaluate(()=>window.libraryRequests.find(m=>m.type==='insert'));
    assert.match(inserted.id,/^member:पाठ्य:/u);assert.equal(inserted.text,undefined);
    await page.getByRole('button',{name:'हस्ताक्षर कॉपी करें',exact:true}).click();
    await page.getByText('हस्ताक्षर कॉपी हुआ। · Signature copied.',{exact:true}).waitFor();
    await page.getByRole('link',{name:'पूरा हिन्दी API संदर्भ ↗',exact:true}).click();
    assert.ok((await page.evaluate(()=>window.libraryRequests)).some(m=>m.type==='documentation'&&m.path.endsWith('/पाठ्य.md')));
    await page.locator('.library-result').click();
    await page.screenshot({path:'target/vscode-library/dark.png'});
    await page.evaluate(()=>document.documentElement.className='light');await page.screenshot({path:'target/vscode-library/light.png'});
    await page.setViewportSize({width:520,height:800});await page.locator('#library-back').click();
    await search.fill('निरपेक्ष');assert.equal(await page.locator('.library-result').count(),2);
    await page.locator('.library-result').filter({hasText:'पूर्णांक'}).click();
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:'target/vscode-library/narrow.png'});
    await page.locator('#library-close').click();assert.ok((await page.evaluate(()=>window.libraryRequests)).some(m=>m.type==='close'));
    assert.deepEqual(errors,[]);console.log('PASS VS Code webview: shared catalog, secure message bridge, calls/copy/docs, dark/light themes and narrow layouts.');
}finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}})().catch(error=>{console.error(error);process.exitCode=1;});
