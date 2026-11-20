// SPDX-License-Identifier: Apache-2.0
const assert=require('node:assert/strict');
const fs=require('node:fs');

module.exports=async function libraryChecks(page){
  fs.mkdirSync('target/library-ui',{recursive:true});
  const dialog=page.locator('#library-dialog');
  const search=page.getByLabel('पुस्तकालय खोजें · Search library',{exact:true});
  const source=page.getByRole('textbox',{name:'Bhash source code',exact:true});
  const write=async value=>{
    // Use real editor keystrokes, including when a snippet field is active.
    await source.press((process.platform==='darwin'?'Meta':'Control')+'+a');
    await source.press('Backspace');
    if(value)await source.pressSequentially(value);
    await page.waitForFunction(value=>JSON.parse(localStorage.getItem('bhash-draft'))?.source===value,value);
  };
  const open=async()=>{await page.locator('#library-open').click();await page.locator('.library-result').first().waitFor();};
  const result=text=>page.locator('.library-result').filter({hasText:text});
  const insert=()=>page.locator('#library-detail .library-insert').click();
  const modifier=process.platform==='darwin'?'Meta':'Control';
  const redo=process.platform==='darwin'?'Meta+Shift+z':'Control+y';
  await open();assert.equal(await search.evaluate(n=>n===n.ownerDocument.activeElement),true);
  await search.press(modifier+'+Enter');assert.equal(await dialog.isVisible(),true);
  await page.getByRole('button',{name:'और नतीजे दिखाएँ · More',exact:true}).click();
  assert.ok(await page.locator('.library-result').count()>60);
  assert.ok((await page.locator('#library-total').innerText()).includes('131'));
  await search.fill('split');assert.equal(await result('बाँटो').count(),1);await result('बाँटो').click();
  assert.ok((await page.locator('#library-detail').innerText()).includes('विभाजक'));
  assert.ok((await page.locator('#library-detail').innerText()).includes('सूची<पाठ>'));
  await search.fill('फ़ाइल'.normalize('NFD'));assert.ok(await result('फ़ाइल').count());
  await result('फ़ाइल').first().click();assert.ok((await page.locator('#library-detail').innerText()).includes('स्थानीय लक्ष्य'));
  assert.equal(await page.locator('#library-detail-title').evaluate(n=>n===n.ownerDocument.activeElement),true);
  await search.fill('<img src=x onerror=alert(1)>');assert.equal(await page.locator('.library-result').count(),0);assert.equal(await dialog.locator('img').count(),0);
  await search.fill('वेब');await page.locator('#library-kind').selectOption('types');
  await page.locator('.library-result').filter({has:page.locator('strong',{hasText:/^वेब$/u})}).click();
  const publicMembers=await page.locator('#library-detail .library-member').count();
  await page.locator('#library-internal').check();
  assert.ok(await page.locator('#library-detail .library-member').count()>publicMembers);
  await page.locator('#library-internal').uncheck();
  assert.equal(await page.locator('#library-detail .library-member').count(),publicMembers);
  await search.fill('गणित');await page.locator('#library-kind').selectOption('types');await result('गणित').click();
  assert.ok(await page.locator('#library-detail .library-member').count());
  await page.screenshot({path:'target/library-ui/desktop.png',fullPage:false});
  await search.press('Escape');assert.equal(await dialog.isVisible(),false);
  assert.equal(await page.locator('#library-open').evaluate(n=>n===n.ownerDocument.activeElement),true);

  // Generic type insertion preserves and replaces the existing selection.
  await write('पुराना');await source.press(modifier+'+a');await open();await search.fill('set');
  await result('समुच्चय').first().click();await insert();
  assert.equal(await source.innerText(),'मानक.समुच्चय<त>');
  assert.equal(await source.evaluate(n=>n===n.ownerDocument.activeElement),true);
  assert.equal(await source.evaluate(n=>n.ownerDocument.getSelection().toString()),'त');
  await source.press(modifier+'+z');assert.equal(await source.innerText(),'पुराना');
  await source.press(redo);assert.equal(await source.innerText(),'मानक.समुच्चय<त>');

  // Calls use snippet fields: Tab advances arguments without typing Hindi names.
  await write('');await open();await page.locator('#library-kind').selectOption('all');await search.fill('text split');
  await result('बाँटो').click();await insert();await source.pressSequentially('"अ,ब"');await source.press('Tab');await source.pressSequentially('","');
  assert.equal(await source.innerText(),'मानक.पाठ्य.बाँटो("अ,ब", ",")');

  await open();await search.fill('compression');await page.locator('#library-kind').selectOption('types');
  await result('संपीड़न').filter({hasText:'Compression'}).first().click();
  assert.ok((await page.locator('#library-detail').innerText()).includes('स्थानीय लक्ष्य'));
  await page.setViewportSize({width:390,height:844});
  await page.locator('#library-back').click();assert.equal(await page.locator('#library-results').isVisible(),true);
  await search.fill('गणित');await result('गणित').click();
  assert.equal(await page.locator('#library-results').isVisible(),false);
  assert.equal(await page.locator('#library-detail').isVisible(),true);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  assert.ok(await dialog.evaluate(n=>n.scrollWidth<=n.clientWidth));
  await page.screenshot({path:'target/library-ui/mobile.png',fullPage:false});
  await search.press('Escape');await page.setViewportSize({width:1280,height:900});

  // Return a real runnable expression; the full Playground smoke test executes it.
  await write('');await open();await page.locator('#library-kind').selectOption('members');await search.fill('absolute');
  assert.equal(await result('निरपेक्ष').count(),2);
  await result('निरपेक्ष').filter({hasText:'पूर्णांक'}).click();await insert();await source.pressSequentially('-५');
  const call=await source.innerText();assert.equal(call,'मानक.गणित.निरपेक्ष(-५)');
  await write('लिखो('+call+');');
  console.log('PASS library lookup, Hindi/English search, overloads, descriptions, signatures, generic insertion, Tab fields, Undo and mobile navigation');
};
