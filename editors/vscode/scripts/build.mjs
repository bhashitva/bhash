// SPDX-License-Identifier: Apache-2.0
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const root = new URL('../../../', import.meta.url);
const manifest = JSON.parse(await readFile('package.json', 'utf8'));
const debuggerCatalog = JSON.parse(await readFile('src/windows-debugger.json','utf8'));
if (debuggerCatalog.id !== 'bhash-windows-x86_64-gdb-v1' || !/^[0-9a-f]{64}$/.test(debuggerCatalog.sha256)
    || /^0+$/.test(debuggerCatalog.sha256) || !debuggerCatalog.url.startsWith('https://github.com/bhashitva/bhash/releases/download/'))
    throw new Error('Refusing to package an unpinned Windows debugger catalog.');

const hindiName = '\u092d\u093e\u0937';
if (manifest.displayName !== `Bhash (${hindiName})` || !manifest.contributes.languages.find(language => language.id === 'bhash')?.aliases.includes(hindiName))
    throw new Error('The Bhash Hindi display name and language alias must be valid UTF-8 before packaging.');
// Refuse to produce a deceptively installable VSIX from a missing/interrupted Maven build.
const compiler = new URL('target/bhash-compiler-0.1.0-SNAPSHOT.jar', root);
const sanity = spawnSync('java', ['-Dfile.encoding=UTF-8', '-jar', fileURLToPath(compiler), 'targets'], {encoding: 'utf8'});
if (sanity.status !== 0) throw new Error(`Build the compiler with mvn package before packaging the extension. ${sanity.stderr || sanity.error || ''}`);
const grammar = await readFile(new URL('src/main/antlr4/Bhash.g4',root),'utf8');
const words = [...grammar.matchAll(/^([A-Z][A-Z_0-9]*)\s*:\s*'([^']+)'/gm)].filter(([, ,value])=>/\p{L}/u.test(value));
const profile = JSON.parse(await readFile(new URL('docs/identifier-profile.json', root), 'utf8'));
const cursorRanges = [...profile.continue, ...profile.contextual.map(point => [point, point]), [65,90], [97,122]];
const identifier = '[' + cursorRanges.map(([a,b]) => `\\x{${a.toString(16)}}` + (a === b ? '' : `-\\x{${b.toString(16)}}`)).join('') + ']';
const spelling = word => word.normalize('NFD').replace(/([\u0915-\u0939])\u093c/g, pair => {
    const composed = pair.normalize('NFC');
    const legacy = {'क़':'क़','ख़':'ख़','ग़':'ग़','ज़':'ज़','ड़':'ड़','ढ़':'ढ़','फ़':'फ़','य़':'य़'}[pair];
    return legacy ? `(?:${pair}|${legacy})` : composed;
}).replace(/\u094d(?=[\u0904-\u0939])/gu, '\u094d[\u200c\u200d]?');
const bounded = list => `(?<!${identifier})(?:${list.map(spelling).join('|')})(?!${identifier})`;
const supplementary = cursorRanges.filter(([a]) => a > 0xffff).flatMap(([a,b]) =>
    Array.from({length:b-a+1}, (_, offset) => String.fromCodePoint(a+offset).split('').map(unit =>
        `\\u${unit.charCodeAt(0).toString(16).padStart(4,'0')}`).join('')));
const wordPart = '(?:[' + cursorRanges.filter(([a]) => a <= 0xffff).map(([a,b]) => `\\u${a.toString(16).padStart(4,'0')}` + (a === b ? '' : `-\\u${b.toString(16).padStart(4,'0')}`)).join('') + ']|' + supplementary.join('|') + ')';
const languageConfig = await readFile('language-configuration.json','utf8');
await writeFile('language-configuration.json', languageConfig.replace(/("wordPattern"\s*:\s*)"(?:\\.|[^"\\])*"/,
    (_, prefix) => prefix + JSON.stringify(wordPart + '+')));
const syntax = {name:'Bhash',scopeName:'source.bhash',patterns:[
    {name:'comment.line.double-slash.bhash',begin:'//',end:'$'},
    {name:'comment.block.bhash',begin:'/\\*',end:'\\*/'},
    {name:'string.quoted.double.bhash',begin:'"',end:'"|$',patterns:[{name:'constant.character.escape.bhash',match:'\\\\(?:["\\\\nrt]|u[0-9a-fA-F]{4})'}]},
    {match:`(${bounded(['वर्ग','अनुबंध','अभिलेख','परिगणना'])})\\s+(${identifier}+)`,captures:{1:{name:'keyword.declaration.bhash'},2:{name:'entity.name.type.bhash'}}},
    {match:`(${bounded(words.filter(([,token])=>token==='FUNCTION').map(([, ,word])=>word))})\\s+(${identifier}+)`,captures:{1:{name:'keyword.declaration.function.bhash'},2:{name:'entity.name.function.bhash'}}},
    {name:'storage.type.bhash',match:bounded(words.filter(([token])=>token.endsWith('_TYPE')||token==='VOID').map(([, ,word])=>word).concat(['सूची','मानचित्र','सरणी','अपवाद']))},
    {name:'constant.language.bhash',match:bounded(['सत्य','असत्य','रिक्त'])},
    {name:'keyword.control.bhash',match:bounded(words.filter(([token])=>!token.endsWith('_TYPE')&&!['VOID','TRUE','FALSE','NULL'].includes(token)).map(([, ,word])=>word))},
    {name:'support.function.bhash',match:bounded(['पढ़ो','पाठपढ़ो','लंबाई','अंश','अंकपाठ','पूर्णांकपाठ','बड़ाअक्षर','त्रुटि','यूटीएफ८बफ़र','यूटीएफ८पाठ'])},
    {name:'constant.numeric.bhash',match:'(?<![\\u0900-\\u097F0-9_])(?:[0-9]+(?:\\.[0-9]+)?|[०-९]+(?:\\.[०-९]+)?)(?![\\u0900-\\u097F0-9_])'},
    {name:'keyword.operator.bhash',match:'->|>>>|>>|<<|==|!=|<=|>=|&&|\\|\\||[+*/%=&|^~!<>-]'},
    {name:'punctuation.section.bhash',match:'[{}()\\[\\];,:.]'}
]};
await writeFile('syntaxes/bhash.tmLanguage.json',JSON.stringify(syntax,null,2)+'\n');
const tsc=spawnSync(process.execPath,['node_modules/typescript/bin/tsc','--project','.'],{stdio:'inherit'});
if(tsc.status!==0)process.exit(tsc.status || 1);
await mkdir('dist',{recursive:true});await mkdir('server',{recursive:true});
await build({entryPoints:['src/extension.ts','src/native-task.ts','src/native.ts','src/task-console.ts','src/native-debug.ts','src/windows-debug-adapter.ts'],bundle:true,platform:'node',format:'cjs',target:'node20',external:['vscode'],outdir:'dist',legalComments:'eof'});
await copyFile(new URL('target/bhash-compiler-0.1.0-SNAPSHOT.jar',root),'server/bhash-compiler.jar');
for(const file of ['LICENSE','NOTICE','LICENSING.md','THIRD_PARTY_NOTICES.md'])await copyFile(new URL(file,root),file);
const lock=JSON.parse(await readFile('package-lock.json','utf8')), notices=[];
for(const [directory,info]of Object.entries(lock.packages)){
    if(!directory.startsWith('node_modules/')||info.dev)continue;
    let license;
    for(const file of ['LICENSE','License.txt','LICENSE.txt','LICENSE.md']){try{license=await readFile(`${directory}/${file}`,'utf8');break;}catch{}}
    if(!license)throw new Error(`Missing license: ${directory}`);
    notices.push(`${directory.slice(13)} ${info.version}\n${license}`);
}
await writeFile('THIRD_PARTY_CLIENT_NOTICES.txt',notices.join('\n\n'));
