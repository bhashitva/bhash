// SPDX-License-Identifier: Apache-2.0
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const root = new URL('../../../', import.meta.url);
// Refuse to produce a deceptively installable VSIX from a missing/interrupted Maven build.
const compiler = new URL('target/bhash-compiler-0.1.0-SNAPSHOT.jar', root);
const sanity = spawnSync('java', ['-Dfile.encoding=UTF-8', '-jar', fileURLToPath(compiler), 'targets'], {encoding: 'utf8'});
if (sanity.status !== 0) throw new Error(`Build the compiler with mvn package before packaging the extension. ${sanity.stderr || sanity.error || ''}`);
const grammar = await readFile(new URL('src/main/antlr4/Bhash.g4',root),'utf8');
const words = [...grammar.matchAll(/^([A-Z_]+)\s*:\s*'([^']+)'/gm)].filter(([, ,value])=>/\p{L}/u.test(value));
const identifier = '[\\u0900-\\u094F\\u0951-\\u0963\\u0966-\\u097F0-9_]';
const bounded = list => `(?<!${identifier})(?:${list.join('|')})(?!${identifier})`;
const syntax = {name:'Bhash',scopeName:'source.bhash',patterns:[
    {name:'comment.line.double-slash.bhash',begin:'//',end:'$'},
    {name:'comment.block.bhash',begin:'/\\*',end:'\\*/'},
    {name:'string.quoted.double.bhash',begin:'"',end:'"|$',patterns:[{name:'constant.character.escape.bhash',match:'\\\\(?:["\\\\nrt]|u[0-9a-fA-F]{4})'}]},
    {match:`(${bounded(['वर्ग','अनुबंध','अभिलेख','परिगणना'])})\\s+(${identifier}+)`,captures:{1:{name:'keyword.declaration.bhash'},2:{name:'entity.name.type.bhash'}}},
    {match:`(${bounded(['कार्य'])})\\s+(${identifier}+)`,captures:{1:{name:'keyword.declaration.function.bhash'},2:{name:'entity.name.function.bhash'}}},
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
await build({entryPoints:['src/extension.ts'],bundle:true,platform:'node',format:'cjs',target:'node20',external:['vscode'],outfile:'dist/extension.js',legalComments:'eof'});
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
