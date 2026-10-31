// SPDX-License-Identifier: Apache-2.0
const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {createRequire}=require('node:module');
const {spawnSync}=require('node:child_process');
const lock=require('../package-lock.json');
const clientRequire=createRequire(require.resolve('vscode-languageclient/package.json'));

test('the shipped language client and build tools resolve patched minimatch',()=>{
    const entries=Object.entries(lock.packages).filter(([key])=>/node_modules\/minimatch$/.test(key));
    assert.ok(entries.length,'lockfile must include minimatch');
    for(const [key,info] of entries){
        const [major,minor,patch]=info.version.split('.').map(Number);
        assert.ok(major>11 || major===11 && (minor>0 || patch>=1),`${key}: ${info.version} is affected by GHSA-g3hv-75wg-m249`);
    }
    assert.equal(clientRequire('minimatch/package.json').version,require('minimatch/package.json').version);
});

test('language-client glob filters preserve Unicode, braces, exclusions and path behavior',()=>{
    const {Minimatch}=clientRequire('minimatch');
    const matcher=new Minimatch('**/*.{bhash,project,lock}',{noext:true});
    assert.ok(matcher.makeRe());
    for(const name of ['src/भारत😀.bhash','bhash.project','bhash.lock','C:/कार्य/src/मुख्य.bhash'])assert.ok(matcher.match(name),name);
    for(const name of ['src/main.java','src/.hidden.bhash','src/main.bhash.tmp'])assert.ok(!matcher.match(name),name);
    assert.ok(new Minimatch('**/*.BHASH',{nocase:true}).match('C:/कार्य/main.bhash'));
    assert.ok(!new Minimatch('**/*.BHASH',{nocase:false}).match('C:/कार्य/main.bhash'));
});

test('optimization-level-two preprocessing bounds generated glob alternatives',()=>{
    // Run the advisory's amplification shape in a child with a hard deadline so
    // a vulnerable dependency cannot hang the test runner or editor indefinitely.
    const entry=clientRequire.resolve('minimatch');
    const result=spawnSync(process.execPath,['-e',`
        const assert=require('node:assert/strict');
        const {Minimatch}=require(process.argv[1]);
        const matcher=new Minimatch(Array(18).fill('**/..').join('/')+'/x', {optimizationLevel:2,braceExpandMax:128});
        assert.ok(matcher.globParts.length<=128);
        console.log('bounded');
    `,entry],{encoding:'utf8',timeout:10000,windowsHide:true,cwd:path.resolve(__dirname,'..')});
    assert.ifError(result.error);
    assert.equal(result.status,0,result.stderr);
    assert.equal(result.stdout.trim(),'bounded');
});
