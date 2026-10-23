/* Run after Maven packaging, with .NET 10 + wasm-tools and playwright installed. */
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');
const root = path.resolve('target/browser-tests');
fs.mkdirSync(root, { recursive: true });
const jar = path.resolve('target/bhash-compiler-0.1.0-SNAPSHOT.jar');
function compile(name, source) {
    const file = path.join(root, `${name}.bhash`);
    fs.writeFileSync(file, source);
    const build = spawnSync('java', ['-jar', jar, 'compile', file, '--target', 'wasm-browser', '-o', path.join(root, name)], { encoding: 'utf8', timeout: 660000 });
    assert.equal(build.status, 0, `${build.stdout}\n${build.stderr}\n${build.error || ''}`);
}
for (const name of ['factorial-console', 'factorial-objects', 'objects', 'contracts-generics'])
    compile(name, fs.readFileSync(`examples/${name}.bhash`, 'utf8'));
compile('semantics', `
वर्ग आधारवर्ग { कार्य मूल्य() -> पूर्णांक { लौटाओ १; } }
वर्ग उपवर्ग विस्तार आधारवर्ग { अधिलेखन कार्य मूल्य() -> पूर्णांक { लौटाओ २; } }
कार्य मुख्य() -> शून्य {
    स्थिर विकल्प: पूर्णांक = पढ़ो();
    यदि (विकल्प == ०) { लिखो(पढ़ो()); लिखो(पढ़ो()); }
    अन्यथा यदि (विकल्प == १) { स्थिर शून्यवस्तु: आधारवर्ग = रिक्त; लिखो(शून्यवस्तु.मूल्य()); }
    अन्यथा यदि (विकल्प == २) { लिखो(१ / पढ़ो()); }
    अन्यथा यदि (विकल्प == ३) {
        स्थिर न्यून: पूर्णांक = -९२२३३७२०३६८५४७७५८०८;
        स्थिर अधिक: पूर्णांक = ९२२३३७२०३६८५४७७५८०७;
        लिखो(न्यून / -१, न्यून % -१, अधिक + १);
        स्थिर वस्तु: आधारवर्ग = नया उपवर्ग();
        लिखो(वस्तु.मूल्य(), वस्तु == वस्तु, वस्तु == रिक्त, १.५, "<script>alert(1)</script>");
    }
    अन्यथा { जबतक (सत्य) {} }
}`);
// A rebuild succeeds, but failed source/builds and edited output preserve prior assets.
compile('static-error', 'वर्ग गलत { साझा मान संख्या: पूर्णांक = १ / ०; } कार्य मुख्य() -> शून्य { लिखो(गलत.संख्या); }');
const semanticsSource = fs.readFileSync(path.join(root, 'semantics.bhash'), 'utf8');
compile('semantics', semanticsSource);
const outputIndex = path.join(root, 'semantics/index.html');
const originalIndex = fs.readFileSync(outputIndex, 'utf8');
function rejectedBuild(source) {
    const file = path.join(root, 'rejected.bhash');
    fs.writeFileSync(file, source);
    const result = spawnSync('java', ['-jar', jar, 'compile', file, '--target', 'wasm-browser', '-o', path.join(root, 'semantics')], { encoding: 'utf8', timeout: 660000 });
    assert.notEqual(result.status, 0);
    assert.equal(fs.readFileSync(outputIndex, 'utf8'), originalIndex);
}
rejectedBuild('कार्य');
const note = path.join(root, 'semantics/_framework/notes.txt');
fs.writeFileSync(note, 'keep me');
rejectedBuild(semanticsSource);
assert.equal(fs.readFileSync(note, 'utf8'), 'keep me');
fs.unlinkSync(note);
fs.appendFileSync(outputIndex, '<!-- user edit -->');
const edited = spawnSync('java', ['-jar', jar, 'compile', path.join(root, 'semantics.bhash'), '--target', 'wasm-browser', '-o', path.join(root, 'semantics')], { encoding: 'utf8', timeout: 60000 });
assert.notEqual(edited.status, 0);
assert.equal(fs.readFileSync(outputIndex, 'utf8'), originalIndex + '<!-- user edit -->');
fs.writeFileSync(outputIndex, originalIndex);
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm' };
const server = http.createServer((request, response) => {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const file = path.resolve(root, '.' + pathname, pathname.endsWith('/') ? 'index.html' : '');
    if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
    fs.readFile(file, (error, data) => {
        if (error) { response.writeHead(404).end(); return; }
        response.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
        response.end(data);
    });
});
(async () => {
    let browser;
    try {
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        browser = await chromium.launch({ headless: true });
        const page = await browser.newPage();
        const pageErrors = [];
        page.on('pageerror', error => pageErrors.push(String(error)));
        const base = `http://127.0.0.1:${server.address().port}`;
        async function run(name, input, expectedOutput, expectedExit = 0, errorContains = '') {
            await page.goto(`${base}/${name}/`);
            await page.locator('#input').fill(input);
            await page.locator('#run').click();
            await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('Finished'), null, { timeout: 60000 });
            assert.equal(await page.locator('#status').textContent(), `Finished (exit ${expectedExit})`);
            assert.equal((await page.locator('#output').textContent()).replaceAll('\r\n', '\n'), expectedOutput);
            const errors = await page.locator('#errors').textContent();
            if (errorContains) assert.ok(errors.includes(errorContains), errors);
            else assert.equal(errors, '');
            assert.deepEqual(pageErrors, []);
            if (name === 'factorial-objects' && input === '५')
                await page.screenshot({ path: 'target/browser-factorial.png', fullPage: true });
            console.log(`PASS ${name} ${JSON.stringify(input)}`);
        }
        const prompt = '० से २० तक एक पूर्णांक लिखें:\n';
        for (const name of ['factorial-console', 'factorial-objects']) {
            for (const [input, result] of [['0','१'], ['1','१'], ['5','१२०'], ['५','१२०'], [' +६ ','७२०'], ['20','२४३२९०२००८१७६६४००००']])
                await run(name, input, `${prompt}गुणनफल: ${result}\n`);
            for (const input of ['-1', '21']) await run(name, input, `${prompt}कृपया ० से २० तक की संख्या दें।\n`);
            for (const input of ['', '1२', 'abc', '9223372036854775808'])
                await run(name, input, prompt, 1, 'भाष:');
        }
        await run('static-error', '', '', 1, 'शून्य से भाग');
        await run('objects', '', 'मनोज १५० १५ १६५\n');
        await run('contracts-generics', '', 'पूर्णांक: २१\nपाठ: नमस्ते\nपात्र ४२ ३.०\n१ १ १००\n');
        await run('semantics', '0\n९२२३३७२०३६८५४७७५८०७\n-9223372036854775808', '९२२३३७२०३६८५४७७५८०७\n-९२२३३७२०३६८५४७७५८०८\n');
        await run('semantics', '1', '', 1, 'रिक्त वस्तु');
        await run('semantics', '2\n0', '', 1, 'शून्य से भाग');
        await run('semantics', '3', '-९२२३३७२०३६८५४७७५८०८ ० -९२२३३७२०३६८५४७७५८०८\n२ सत्य असत्य १.५ <script>alert(1)</script>\n');
        // Restart on the same page; each run uses a fresh worker/runtime.
        await page.locator('#input').fill('0\n5\n6');
        await page.locator('#run').click();
        await page.waitForFunction(() => document.querySelector('#status').textContent === 'Finished (exit 0)');
        assert.equal(await page.locator('#output').textContent(), '५\n६\n');
        await page.locator('#input').fill('4');
        await page.locator('#run').click();
        assert.equal(await page.locator('#stop').isEnabled(), true);
        await page.locator('#stop').click();
        assert.equal(await page.locator('#status').textContent(), 'Stopped');
        assert.equal(await page.locator('#run').isEnabled(), true);
        await page.screenshot({ path: 'target/browser-tests.png', fullPage: true });
        assert.deepEqual(pageErrors, []);
        console.log('Browser WASM smoke tests passed (including restart and stop).');
    } finally {
        await browser?.close();
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
