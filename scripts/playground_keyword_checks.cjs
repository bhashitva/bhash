// SPDX-License-Identifier: Apache-2.0
const assert = require('node:assert/strict');
const fs = require('node:fs');

// Shared by the quick UI test and the real compiler/WASM integration suite.
module.exports = async function keywordChecks(page) {
  const source = page.getByRole('textbox', {name: 'Bhash source code', exact: true});
  const search = page.getByLabel('शब्द खोजें · Search', {exact: true});
  const group = page.getByLabel('समूह · Category', {exact: true});
  const button = word => page.locator('#keyword-list button').filter({has: page.locator('span', {hasText: new RegExp('^'+word+'$')})});
  const text = () => source.innerText();
  const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
  const spelling = [...fs.readFileSync('src/main/antlr4/Bhash.g4','utf8').matchAll(/^[A-Z][A-Z_0-9]*\s*:\s*'([^']+)'/gm)]
    .map(match => match[1]).filter(word => /\p{L}/u.test(word)).concat(['विकल्पसमूह','मिलान']);
  const open = async () => { if (!await search.isVisible()) await page.locator('.keyword-picker summary').click(); };
  await open();
  await group.selectOption('all');
  assert.deepEqual((await page.locator('#keyword-list button').evaluateAll(buttons => buttons.map(b => b.dataset.keyword))).sort(), spelling.sort());
  await search.fill('FUNCTION'); assert.equal(await button('फलन').count(),1);
  await search.fill('no such keyword'); assert.equal(await page.locator('#keyword-list button').count(),0);
  assert.equal(await page.locator('#keyword-empty').isVisible(),true);
  await group.selectOption('errors'); await search.fill('catch'); assert.equal(await button('पकड़ो').count(),1);
  await group.selectOption('all'); await search.fill('');

  // Picker focus must preserve the editor's selection, and insertion must be a
  // separate undo step rather than undoing the previously typed source as well.
  await source.fill('पुराना'); await source.press(modifier+'+a');
  await search.fill('string'); await button('पाठ').click();
  assert.equal(await text(),'पाठ ');
  assert.equal(await source.evaluate(node => node === node.ownerDocument.activeElement),true);
  await source.press(modifier+'+z'); assert.equal(await text(),'पुराना');
  await source.press(process.platform === 'darwin' ? 'Meta+Shift+z' : 'Control+y'); assert.equal(await text(),'पाठ ');

  // Put the cursor between two ordinary words. No existing text may disappear.
  await source.fill('😀ab'); await source.press('Home');
  await source.press('ArrowRight'); await source.press('ArrowRight');
  await open(); await search.fill('if'); await button('यदि').click(); assert.equal(await text(),'😀a यदि b');
  await source.press(modifier+'+z'); assert.equal(await text(),'😀ab');

  // Assemble and return a runnable program using a picked keyword. The full
  // Playground suite executes this through the real compiler and WASM runtime.
  await source.fill(''); await open(); await search.fill('print');
  await button('लिखो').focus(); await page.keyboard.press('Enter');
  await page.keyboard.type('("कीवर्ड से नमस्ते 😀");');
  assert.equal(await text(),'लिखो ("कीवर्ड से नमस्ते 😀");');
  assert.ok((await page.locator('#keyword-help').textContent()).includes('लिखो'));
  assert.equal(await search.isVisible(),false);
  await page.locator('.keyword-picker summary').press('Enter');
  assert.equal(await search.isVisible(),true);
  await page.setViewportSize({width:390,height:844});
  await search.fill(''); await group.selectOption('all');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true);
  assert.ok((await button('फलन').boundingBox()).height >= 44);
  assert.ok(await page.locator('#keyword-list').evaluate(node => node.scrollHeight > node.clientHeight));
  await page.setViewportSize({width:1280,height:900});
  await group.selectOption('common'); await search.fill('');
  await search.press('Escape'); assert.equal(await search.isVisible(),false);
  console.log('PASS keyword catalog, search, selection, cursor, undo/redo, keyboard and mobile layout');
};
