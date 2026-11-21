// SPDX-License-Identifier: Apache-2.0
// Exercises an installed wheel through real Jupyter editors and a Bhash kernel.
const {chromium}=require('playwright');const {spawn,execFileSync}=require('node:child_process');
const fs=require('node:fs/promises'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),frontend=process.argv[2]||'lab',python=process.env.BHASH_UI_PYTHON||'python';
if(!['lab','notebook'].includes(frontend))throw Error('Use lab or notebook');
const baseline=process.argv.includes('--baseline');
if(baseline&&frontend!=='notebook')throw Error('The upstream baseline is only needed for Notebook');
const output=path.join(root,'target','jupyter-lookup-'+frontend+(baseline?'-baseline':''));let server,browser,page,log='',shutdown;const browserErrors=[];
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function main(){
  await fs.mkdir(output,{recursive:true});const directory=await fs.mkdtemp(path.join(output,'session-'));
  const env={...process.env,JUPYTER_DATA_DIR:path.join(directory,'data'),JUPYTER_CONFIG_DIR:path.join(directory,'config'),JUPYTER_RUNTIME_DIR:path.join(directory,'runtime'),JUPYTER_PLATFORM_DIRS:'1'};
  if(baseline){await fs.mkdir(path.join(env.JUPYTER_CONFIG_DIR,'labconfig'),{recursive:true});await fs.writeFile(path.join(env.JUPYTER_CONFIG_DIR,'labconfig/page_config.json'),JSON.stringify({disabledExtensions:{'@bhashitva/library-lookup':true}}));}
  execFileSync(python,['-m','bhash_jupyter.install'],{env,encoding:'utf8'});
  const notebook={nbformat:4,nbformat_minor:5,metadata:{kernelspec:{name:'bhash',display_name:'Bhash (भाष)',language:'bhash'},language_info:{name:'bhash'}},cells:[{id:'initial',cell_type:'code',metadata:{},source:'पुराना',outputs:[],execution_count:null}]};
  await fs.writeFile(path.join(directory,'starter.ipynb'),JSON.stringify(notebook));
  await fs.writeFile(path.join(directory,'other.ipynb'),JSON.stringify({...notebook,metadata:{kernelspec:{name:'python3',display_name:'Python 3',language:'python'},language_info:{name:'python'}},cells:[{...notebook.cells[0],source:'untouched'}]}));
  // Notebook 7.2's stock checkpoint indicator dereferences the last checkpoint
  // without checking for an empty list. Seed the same initial checkpoint that
  // a notebook created through Jupyter normally has, rather than ignore errors.
  await fs.mkdir(path.join(directory,'.ipynb_checkpoints'));
  for(const name of ['starter','other'])await fs.copyFile(path.join(directory,name+'.ipynb'),path.join(directory,'.ipynb_checkpoints',name+'-checkpoint.ipynb'));
  const listener=net.createServer();await new Promise(resolve=>listener.listen(0,'127.0.0.1',resolve));const port=listener.address().port;await new Promise(resolve=>listener.close(resolve));
  const token=crypto.randomBytes(24).toString('hex'),base='http://127.0.0.1:'+port;
  shutdown=()=>fetch(base+'/api/shutdown?token='+token,{method:'POST'}).catch(()=>{});
  server=spawn(python,['-m',frontend==='lab'?'jupyterlab':'notebook','--no-browser','--ServerApp.ip=127.0.0.1','--ServerApp.port='+port,'--ServerApp.port_retries=0','--ServerApp.root_dir='+directory,'--IdentityProvider.token='+token,'--'+(frontend==='lab'?'LabApp':'JupyterNotebookApp')+'.expose_app_in_browser=True'],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  server.stdout.on('data',data=>log+=data.toString().replaceAll(token,'<test-token>'));server.stderr.on('data',data=>log+=data.toString().replaceAll(token,'<test-token>'));
  let ready=false;for(let end=Date.now()+60000;Date.now()<end;){if(server.exitCode!==null)throw Error('Jupyter server exited: '+log);try{if((await fetch(base+'/api?token='+token)).ok){ready=true;break;}}catch{}await sleep(200);}
  assert.ok(ready,'Jupyter server did not start');
  browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1440,height:1000}});page=await context.newPage();
  const errors=[];let phase='startup';page.on('pageerror',error=>errors.push({message:error.message,stack:error.stack,phase}));
  page.on('console',message=>{if(message.type()==='error')browserErrors.push({message:message.text(),phase,location:message.location()});});
  await page.goto(base+(frontend==='lab'?'/lab/tree/':'/notebooks/')+'starter.ipynb?token='+token,{waitUntil:'domcontentloaded',timeout:60000});
  await page.waitForFunction(()=>window.jupyterapp?.shell?.currentWidget?.content?.activeCell?.editor,null,{timeout:60000});
  await page.evaluate(()=>{window.__bhashNotebook=window.jupyterapp.shell.currentWidget;});
  await page.evaluate(async()=>{await window.jupyterapp.restored;await window.__bhashNotebook.sessionContext.ready;});
  await page.locator('.jp-Spinner:visible').first().waitFor({state:'hidden',timeout:60000});
  // JupyterLab reuses one splash node and removes it 200 ms after fading.
  // Waiting only for a spinner (or opacity) can overlap removals on the next
  // theme change. Wait for the requested theme AND actual splash detachment.
  const changeTheme=async theme=>{
    await page.evaluate(name=>window.jupyterapp.commands.execute('apputils:change-theme',{theme:name}),theme);
    await page.waitForFunction(name=>document.body.dataset.jpThemeName===name&&!document.getElementById('jupyterlab-splash'),theme,{timeout:60000});
  };
  const fingerprint=error=>error.message+'\n'+(error.stack.split('\n')[1]||'').replace(/http:\/\/127\.0\.0\.1:\d+/g,'<test-server>');
  if(baseline){
    await sleep(1000);assert.equal(await page.evaluate(()=>window.jupyterapp.isPluginActivated('@bhashitva/library-lookup:plugin')),false);
    // Notebook 7.6.5's shortcut plugin dereferences an unloaded settings schema.
    // Record only this verified upstream startup error with Bhash disabled;
    // never blanket-ignore schema errors or errors from the lookup bundle.
    await fs.writeFile(path.join(output,'baseline-errors.json'),JSON.stringify(errors,null,2));
    assert.ok(errors.every(error=>error.phase==='startup'&&error.message==="Cannot read properties of undefined (reading 'schema')"&&/\/static\/notebook\/notebook_core\.[^/]+\.js/.test(error.stack)),JSON.stringify(errors,null,2));
    await fs.writeFile(path.join(output,'result.json'),JSON.stringify({lookupDisabled:true,fingerprints:errors.map(fingerprint),errors},null,2));
    assert.deepEqual(browserErrors,[],'disabled-extension baseline has no console errors');
    console.log('PASS Notebook baseline with Bhash lookup disabled; '+errors.length+' known upstream startup error(s).');return;
  }
  const prepare=async(source='पुराना')=>{
    await page.evaluate(text=>{const panel=window.__bhashNotebook;window.jupyterapp.shell.activateById(panel.id);panel.content.activeCellIndex=0;const cell=panel.content.activeCell;cell.model.sharedModel.setSource(text);panel.content.mode='edit';cell.editor.setSelection({start:{line:0,column:0},end:cell.editor.getPositionAt(text.length)});cell.editor.focus();},source);
    await page.waitForFunction(()=>window.jupyterapp.shell.currentWidget===window.__bhashNotebook);
  };
  const source=()=>page.evaluate(()=>window.__bhashNotebook.content.model.cells.get(0).sharedModel.getSource());
  const lookup=page.locator('#bhash-standard-library'),search=lookup.locator('#library-search');
  const open=async()=>{
    const button=page.getByText('📚 पुस्तकालय',{exact:true}).filter({visible:true});
    if(await button.isVisible())await button.click();else await page.evaluate(()=>window.jupyterapp.commands.execute('bhash:standard-library'));
    await search.waitFor();await search.click();
  };
  phase='insert';await prepare();await open();await lookup.locator('#library-results .library-result').first().waitFor();
  assert.match(await lookup.locator('#library-total').innerText(),/131.*940/);
  await search.fill('निरपेक्ष');assert.equal(await lookup.locator('#library-results .library-result').count(),2);await lookup.locator('#library-results .library-result').filter({hasText:'पूर्णांक'}).click();
  assert.match(await lookup.locator('#library-detail').innerText(),/Parameters/);await lookup.locator('#library-detail .library-insert').click();
  assert.equal(await source(),'मानक.गणित.निरपेक्ष(मानक)');assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.model.cells.get(0).executionCount),null);
  const code=page.locator('.jp-Notebook .jp-CodeCell').first().locator('.cm-content');
  await code.press('ControlOrMeta+z');assert.equal(await source(),'पुराना','one Undo restores selected source');
  await prepare('लिखो();');await open();
  await page.evaluate(()=>{const panel=window.__bhashNotebook;window.jupyterapp.shell.activateById(panel.id);panel.content.activeCell.editor.focus();});
  await page.waitForFunction(()=>window.__bhashNotebook.content.activeCell.editor.hasFocus());
  await page.evaluate(()=>window.__bhashNotebook.content.activeCell.editor.setCursorPosition({line:0,column:5}));
  await lookup.locator('#library-detail .library-insert').click();assert.equal(await source(),'लिखो(मानक.गणित.निरपेक्ष(मानक));','insertion follows the latest cursor without reopening');
  await prepare();await open();await search.fill('निरपेक्ष');await lookup.locator('#library-results .library-result').filter({hasText:'पूर्णांक'}).click();await lookup.locator('#library-detail .library-insert').click();
  await page.waitForFunction(()=>window.__bhashNotebook.content.activeCell.editor.hasFocus());
  await page.keyboard.insertText('-३');assert.equal(await source(),'मानक.गणित.निरपेक्ष(-३)');await code.press('Shift+Enter');
  await page.waitForFunction(()=>window.__bhashNotebook.content.model.cells.get(0).outputs.toJSON().some(output=>output.data?.['text/plain']==='३'),null,{timeout:60000});
  await open();await search.fill('text split');assert.equal(await lookup.locator('#library-results .library-result').count(),1);await lookup.locator('#library-results .library-result').click();assert.match(await lookup.locator('#library-detail').innerText(),/सूची<पाठ>/);
  await page.evaluate(()=>{window.__bhashNotebook.content.model.readOnly=true;});
  await lookup.locator('#library-detail .library-insert').click();assert.match(await lookup.locator('#library-notice').innerText(),/read-only/);
  await page.evaluate(()=>{window.__bhashNotebook.content.model.readOnly=false;});
  if(frontend==='lab') {
    const original=await source();
    await page.evaluate(async()=>{window.__otherNotebook=await window.jupyterapp.commands.execute('docmanager:open',{path:'other.ipynb'});await window.__otherNotebook.revealed;});
    await page.waitForFunction(()=>window.jupyterapp.shell.currentWidget===window.__otherNotebook);
    await page.evaluate(()=>window.jupyterapp.shell.activateById('bhash-standard-library'));
    await search.click();await lookup.locator('#library-detail .library-insert').click();assert.match(await lookup.locator('#library-notice').innerText(),/Select a Bhash/);
    assert.equal(await source(),original);assert.equal(await page.evaluate(()=>window.__otherNotebook.content.model.cells.get(0).sharedModel.getSource()),'untouched');
    await page.evaluate(()=>window.jupyterapp.shell.activateById(window.__bhashNotebook.id));await page.waitForFunction(()=>window.jupyterapp.shell.currentWidget===window.__bhashNotebook);
    await open();
  }
  await lookup.getByRole('button',{name:'हस्ताक्षर कॉपी करें',exact:true}).click();await lookup.getByText('हस्ताक्षर कॉपी हुआ। · Signature copied.',{exact:true}).waitFor();
  phase='theme';await page.screenshot({path:path.join(output,'light.png')});await changeTheme('JupyterLab Dark');await page.screenshot({path:path.join(output,'dark.png')});
  phase='offline';await context.setOffline(true);await search.fill('समुच्चय');assert.ok(await lookup.locator('#library-results .library-result').count());await context.setOffline(false);
  phase='examples';
  await lookup.locator('#library-kind').selectOption('types');await search.fill('गणित');await lookup.locator('[data-id="type:गणित"]').click();
  const example=await lookup.locator('#library-detail section').filter({hasText:'के कोड अंश'}).locator('pre').first().innerText();
  await page.evaluate(()=>{const notebook=window.__bhashNotebook.content;notebook.notebookConfig={...notebook.notebookConfig,defaultCell:'markdown'};});
  const before=await page.evaluate(()=>window.__bhashNotebook.content.model.cells.length);await lookup.getByRole('button',{name:'उदाहरण नया कक्ष · New example cell',exact:true}).first().click();
  assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.model.cells.length),before+1);assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.activeCell.model.executionCount),null,'example insertion never executes');
  assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.activeCell.model.type),'code','examples are code even when the default is Markdown');
  assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.activeCell.model.sharedModel.getSource()),example,'the example is populated after notebook selection signals');
  await open();await page.evaluate(()=>window.__bhashNotebook.content.activeCell.model.sharedModel.setSource('बदला'));await lookup.locator('#library-detail .library-insert').click();assert.match(await lookup.locator('#library-notice').innerText(),/Cell changed/);
  await page.setViewportSize({width:620,height:900});await lookup.locator('#library-back').click();await search.fill('जेसन');await lookup.locator('#library-results .library-result').first().click();
  assert.ok(await lookup.evaluate(node=>{const dialog=node.shadowRoot.querySelector('#library-dialog');return dialog.scrollWidth<=dialog.clientWidth+2;}));await page.screenshot({path:path.join(output,'narrow.png')});
  phase='language';await page.setViewportSize({width:1440,height:1000});
  const languageSearch=lookup.locator('#language-search'),languageKind=lookup.locator('#language-kind'),languageGroup=lookup.locator('#language-group');
  const languageOpen=async()=>{
    const button=page.getByText('⌨ शब्द/ढाँचे',{exact:true}).filter({visible:true});
    if(await button.isVisible())await button.click();else await page.evaluate(()=>window.jupyterapp.commands.execute('bhash:language-reference'));
    await languageSearch.waitFor();await languageSearch.click();
  };
  await prepare('लिखो();');
  await page.evaluate(()=>window.__bhashNotebook.content.activeCell.editor.setCursorPosition({line:0,column:5}));
  const executions=await page.evaluate(()=>window.__bhashNotebook.content.model.cells.get(0).executionCount);
  await languageOpen();await languageKind.selectOption('keywords');await languageSearch.fill('');
  assert.equal(await lookup.locator('#language-results button').count(),57,'every grammar/contextual keyword is available');
  assert.ok(await lookup.locator('#language-results').evaluate(node=>{const buttons=node.querySelectorAll('button');return buttons[1].getBoundingClientRect().top>=buttons[0].getBoundingClientRect().bottom-1;}),'results remain readable vertical rows');
  await languageSearch.fill('true');await lookup.locator('[data-id="keyword:TRUE"]').click();await lookup.locator('#language-insert').click();
  assert.equal(await source(),'लिखो(सत्य);');assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.model.cells.get(0).executionCount),executions);
  await code.press('ControlOrMeta+z');assert.equal(await source(),'लिखो();');
  await languageOpen();await languageGroup.selectOption('errors');await languageSearch.fill('catch');assert.equal(await lookup.locator('#language-results button').count(),1);
  await languageGroup.selectOption('all');await languageSearch.fill('मिलान');assert.ok(await lookup.locator('[data-id="keyword:MATCH_CONTEXTUAL"]').count());
  await prepare('    पुराना');await page.evaluate(()=>window.__bhashNotebook.content.activeCell.editor.setSelection({start:{line:0,column:4},end:{line:0,column:10}}));
  await languageOpen();await languageKind.selectOption('constructs');await languageSearch.fill('while');await lookup.locator('[data-id="construct:While"]').click();await lookup.locator('#language-insert').click();
  assert.equal(await source(),'    जबतक (शर्त) {\n        \n    }');
  await prepare('पुराना');await languageOpen();await languageSearch.fill('class');await lookup.locator('[data-id="construct:Class"]').click();
  assert.match(await lookup.locator('#language-detail pre').innerText(),/सार्वजनिक वर्ग नाम/);
  await page.evaluate(()=>{window.__bhashNotebook.content.model.readOnly=true;});await lookup.locator('#language-insert').click();assert.match(await lookup.locator('#language-notice').innerText(),/read-only/);
  await page.evaluate(()=>{window.__bhashNotebook.content.model.readOnly=false;window.__bhashNotebook.content.activeCell.model.sharedModel.setSource('सुरक्षित');});
  await lookup.locator('#language-insert').click();assert.match(await lookup.locator('#language-notice').innerText(),/Cell changed/);assert.equal(await source(),'सुरक्षित');
  await prepare('पुराना');await languageOpen();await languageSearch.fill('print');await lookup.locator('[data-id="construct:Print"]').click();await lookup.locator('#language-insert').click();
  assert.equal(await source(),'लिखो();');assert.equal(await page.evaluate(()=>window.__bhashNotebook.content.model.cells.get(0).executionCount),executions);
  await page.keyboard.insertText('"शब्द और ढाँचे"');assert.equal(await source(),'लिखो("शब्द और ढाँचे");');await code.press('Shift+Enter');
  await page.waitForFunction(()=>window.__bhashNotebook.content.model.cells.get(0).outputs.toJSON().some(output=>String(output.text).includes('शब्द और ढाँचे')),null,{timeout:60000});
  await languageOpen();await lookup.locator('#reference-mode-library').click();await search.waitFor();await lookup.locator('#reference-mode-language').click();await languageSearch.waitFor();
  phase='offline';await context.setOffline(true);await languageSearch.fill('function');assert.ok(await lookup.locator('#language-results button').count());await context.setOffline(false);
  phase='language-theme';await changeTheme('JupyterLab Light');await page.locator('.jp-Spinner:visible').first().waitFor({state:'hidden',timeout:60000});await page.screenshot({path:path.join(output,'language-light.png')});
  await changeTheme('JupyterLab Dark');await page.locator('.jp-Spinner:visible').first().waitFor({state:'hidden',timeout:60000});await page.screenshot({path:path.join(output,'language-dark.png')});
  await page.setViewportSize({width:620,height:900});await languageSearch.fill('प्रत्येक');await lookup.locator('[data-id="construct:Collection iteration"]').click();
  assert.ok(await lookup.locator('#language-pane').evaluate(node=>node.scrollWidth<=node.clientWidth+2));await page.screenshot({path:path.join(output,'language-narrow.png')});
  await languageSearch.press('Escape');assert.equal(await lookup.count(),0);
  // At narrow widths Jupyter moves toolbar controls into its overflow menu.
  // The contributed command must still reopen the disposed reference reliably.
  await page.evaluate(()=>window.jupyterapp.commands.execute('bhash:language-reference'));await languageSearch.waitFor();
  const upstream=frontend==='notebook'?JSON.parse(await fs.readFile(path.join(root,'target/jupyter-lookup-notebook-baseline/result.json'),'utf8')):{lookupDisabled:true,fingerprints:[]};
  assert.equal(upstream.lookupDisabled,true);
  assert.deepEqual(errors.filter(error=>error.phase!=='startup'||!upstream.fingerprints.includes(fingerprint(error))),[]);
  assert.deepEqual(browserErrors.filter(error=>!(error.phase==='offline'&&error.message==='Failed to load resource: net::ERR_INTERNET_DISCONNECTED')),[],'unexpected browser console errors');
  console.log('PASS '+frontend+': library plus all 57 keywords and 25 constructs, Hindi/English search, insertion/selection/Undo/indentation, real execution, guarded edits, offline and themed responsive UI.');
}
main().catch(async error=>{console.error((error.stack||String(error)).replace(/token=[a-f0-9]+/gi,'token=<test-token>'));console.error(JSON.stringify(browserErrors,null,2));await page?.screenshot({path:path.join(output,'failure.png')}).catch(()=>{});process.exitCode=1;}).finally(async()=>{await browser?.close();await shutdown?.();if(server&&server.exitCode===null){await Promise.race([new Promise(resolve=>server.once('exit',resolve)),sleep(10000)]);if(server.exitCode===null)server.kill();}await fs.writeFile(path.join(output,'server.log'),log);await fs.writeFile(path.join(output,'browser-console.json'),JSON.stringify(browserErrors,null,2));});
