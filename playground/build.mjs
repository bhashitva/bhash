// SPDX-License-Identifier: Apache-2.0
import {build} from 'esbuild';
import {readFile, mkdir, cp, rm, writeFile} from 'node:fs/promises';
const grammar = await readFile('../src/main/antlr4/Bhash.g4', 'utf8');
const words = [...grammar.matchAll(/^([A-Z_]+)\s*:\s*'([^']+)'/gm)]
  .filter(([, , value]) => /\p{L}/u.test(value)).map(([, token, value]) => ({token, value}));
await rm('dist', {recursive: true, force: true});
await mkdir('dist', {recursive: true});
await cp('public', 'dist', {recursive: true});
await cp('../deploy/analytics/bhash-analytics.js', 'dist/bhash-analytics.js');
await build({entryPoints: ['src/app.js'], bundle: true, minify: true, target: ['es2022'], outfile: 'dist/app.js',
  define: {BHASH_KEYWORDS: JSON.stringify(words)}, legalComments: 'eof'});
const lock = JSON.parse(await readFile('package-lock.json', 'utf8'));
const licenses = [];
for (const [directory, info] of Object.entries(lock.packages)) {
  if (!directory.startsWith('node_modules/') || info.dev) continue;
  let license;
  for (const name of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'LICENSE-MIT']) {
    try { license = await readFile(`${directory}/${name}`, 'utf8'); break; } catch {}
  }
  if (!license) throw new Error(`Missing redistributable license for ${directory}`);
  licenses.push(`${directory.slice(13)} ${info.version}\n${license}`);
}
await writeFile('dist/THIRD_PARTY_LICENSES.txt', licenses.join('\n\n'));
for (const file of ['LICENSE', 'NOTICE', 'LICENSING.md']) await cp(`../${file}`, `dist/${file}`);
