// SPDX-License-Identifier: Apache-2.0
import {readFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {build} from 'esbuild';
const here=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const root=path.resolve(here,'../../..');
const html=await readFile(path.join(root,'playground/public/index.html'),'utf8');
const dialog=html.match(/<dialog id="library-dialog"[\s\S]*?<\/dialog>/)?.[0];
if(!dialog)throw Error('Shared library dialog was not found');
const markup='<button id="library-open" hidden></button>'+dialog.replace('कॉल डालें, तर्क भरें, Tab से आगे बढ़ें।','कॉल डालें और चुना तर्क भरें। · Insert without running.');
const style=(await readFile(path.join(root,'playground/public/style.css'),'utf8'))+'\n'+await readFile(path.join(here,'style/library.css'),'utf8');
await mkdir(path.join(here,'lib'),{recursive:true});
await build({entryPoints:[path.join(here,'src/index.mjs')],outfile:path.join(here,'lib/index.js'),bundle:true,format:'esm',platform:'browser',target:'es2022',external:['@jupyterlab/*','@lumino/*'],define:{BHASH_LIBRARY_HTML:JSON.stringify(markup),BHASH_LIBRARY_STYLE:JSON.stringify(style)}});
await build({entryPoints:[path.join(here,'src/insert.mjs')],outfile:path.join(here,'lib/insert.cjs'),bundle:true,platform:'node',format:'cjs'});
