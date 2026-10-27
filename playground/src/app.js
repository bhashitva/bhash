// SPDX-License-Identifier: Apache-2.0
import {EditorView, basicSetup} from 'codemirror';
import {StreamLanguage} from '@codemirror/language';
import {autocompletion, snippetCompletion} from '@codemirror/autocomplete';
import {setDiagnostics, lintGutter} from '@codemirror/lint';
import {compilerDiagnostics} from './diagnostics.mjs';
const $ = id => document.getElementById(id);
const track = (name, values) => window.bhashAnalytics?.(name, values);
const keywordData = BHASH_KEYWORDS;
const words = new Map(keywordData.map(({token, value}) => [value, token]));
const language = StreamLanguage.define({
  startState: () => ({block: false}),
  token(stream, state) {
    if (state.block) { if (stream.skipTo('*/')) { stream.match('*/'); state.block = false; } else stream.skipToEnd(); return 'comment'; }
    if (stream.eatSpace()) return null;
    if (stream.match('//')) { stream.skipToEnd(); return 'comment'; }
    if (stream.match('/*')) { state.block = true; return 'comment'; }
    if (stream.eat('"')) { let escaped = false, next; while ((next = stream.next()) !== undefined) { if (next === '"' && !escaped) break; escaped = next === '\\' && !escaped; } return 'string'; }
    if (stream.match(/^[0-9०-९]+(?:\.[0-9०-९]+)?/u)) return 'number';
    const name = stream.match(/^[\p{L}\p{M}][\p{L}\p{M}\p{N}_]*/u);
    if (name) { const kind = words.get(name[0].normalize('NFC')); return kind ? kind.endsWith('_TYPE') ? 'typeName' : 'keyword' : 'variableName'; }
    stream.next(); return 'operator';
  }, languageData: {commentTokens: {line: '//', block: {open: '/*', close: '*/'}}}
});
const snippets = [
  ['लिखो', 'print', 'लिखो(${उत्तर});'], ['स्थिर', 'const', 'स्थिर ${नाम}: ${पूर्णांक} = ${०};'],
  ['मान', 'let', 'मान ${नाम}: ${पूर्णांक} = ${०};'], ['यदि', 'if', 'यदि (${शर्त}) {\n    ${}\n}'],
  ['कार्य', 'function', 'कार्य ${नाम}() -> शून्य {\n    ${}\n}'], ['परिगणना', 'enum', 'परिगणना ${स्थिति} { ${लंबित}, ${तैयार} }'],
  ['अभिलेख', 'record', 'अभिलेख ${बिंदु}(${अ}: पूर्णांक, ${ब}: पूर्णांक);'],
  ['प्रयास', 'try', 'प्रयास {\n    ${}\n} पकड़ो (गलती: अपवाद) {\n    लिखो(गलती.संदेश);\n}']
];
const completionOptions = [...keywordData.map(({value}) => ({label: value, type: 'keyword'})), ...snippets.flatMap(([label, english, template]) => [
  snippetCompletion(template, {label: `${label} · snippet`, type: 'keyword', detail: english}),
  snippetCompletion(template, {label: english, type: 'keyword', detail: label})
])];
const examples = {
  hello: {mode: 'scratch', source: 'लिखो("नमस्ते, दुनिया!");', input: ''},
  factorial: {mode: 'program', input: '५', source: `कार्य गुणनफल(अंक: पूर्णांक) -> पूर्णांक {
    यदि (अंक <= १) { लौटाओ १; }
    लौटाओ अंक * गुणनफल(अंक - १);
}
कार्य मुख्य() -> शून्य {
    स्थिर अंक: पूर्णांक = पढ़ो();
    यदि (अंक < ० || अंक > २०) { त्रुटि("० से २० तक पूर्णांक दें।"); }
    लिखो("गुणनफल:", गुणनफल(अंक));
}`},
  collections: {mode: 'scratch', input: '', source: `स्थिर अंक: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,२,३};
अंक.जोड़ो(४);
प्रत्येक (अ: पूर्णांक में अंक) {
    लिखो(अ);
}`},
  standard: {mode: 'program', input: '', source: `आयात मानक.पाठ्य;
आयात मानक.गणित;
आयात मानक.जेसन;
कार्य मुख्य() -> शून्य {
    स्थिर शब्द: सूची<पाठ> = पाठ्य.बाँटो("भाष,भारत,ज्ञान", ",");
    लिखो(पाठ्य.जोड़ो(शब्द, " · "));
    लिखो(पाठ्य.संख्यापाठ(गणित.वर्गमूल(२.०), ४));
    स्थिर अंक: सूची<पूर्णांक> = नया सूची<पूर्णांक>{१,२,३};
    लिखो(जेसन.पाठबनाओ(अंक));
}`},
  types: {mode: 'program', input: '', source: `परिगणना स्थिति { लंबित, तैयार = ५ }
अभिलेख बिंदु(अ: पूर्णांक, ब: पूर्णांक);
कार्य मुख्य() -> शून्य {
    स्थिर क: बिंदु = नया बिंदु(२,३);
    स्थिर मूल: सर्ववस्तु = क;
    लिखो(स्थिति.तैयार.नाम, क.अ, क.ब);
    लिखो(प्रकारहै<बिंदु>(मूल), क == नया बिंदु(२,३));
}`}
};
let initial = examples.hello;
try { const draft = JSON.parse(localStorage.getItem('bhash-draft')); if (validDraft(draft)) initial = draft; } catch {}
try {
  const hash = new URLSearchParams(location.hash.slice(1));
  if (hash.has('code')) {
    const bytes = Uint8Array.from(atob(hash.get('code')), c => c.charCodeAt(0));
    const shared = {source: new TextDecoder('utf-8', {fatal: true}).decode(bytes), mode: hash.get('mode'), input: ''};
    if (validDraft(shared)) initial = shared;
  }
} catch {}
function validDraft(draft) { return draft && typeof draft.source === 'string' && draft.source.length <= 32768 && ['scratch', 'program'].includes(draft.mode) && typeof draft.input === 'string' && draft.input.length <= 16384; }
$('mode').value = initial.mode; $('stdin').value = initial.input;
const editor = new EditorView({doc: initial.source, parent: $('editor'), extensions: [basicSetup, language, lintGutter(), EditorView.lineWrapping,
  autocompletion({override: [context => { const word = context.matchBefore(/[\p{L}\p{M}\p{N}_]+/u); return word || context.explicit ? {from: word?.from ?? context.pos, options: completionOptions} : null; }]}),
  EditorView.contentAttributes.of({'aria-label': 'Bhash source code', spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off'}),
  EditorView.updateListener.of(update => { if (update.docChanged) { save(); queueMicrotask(clearCompilerErrors); } if (update.selectionSet || update.docChanged) { const pos = update.state.selection.main.head, line = update.state.doc.lineAt(pos); $('position').textContent = `Ln ${line.number}, Col ${pos - line.from + 1}`; } })
]});
function clearCompilerErrors() {
  if ($('diagnostics').childElementCount) {
    $('diagnostics').replaceChildren();
    editor.dispatch(setDiagnostics(editor.state, []));
  }
}
function showCompilerErrors(message, compiledSource) {
  $('errors').textContent = message;
  // Do not attach stale server locations after the user has edited during a build.
  if (editor.state.doc.toString() !== compiledSource) return;
  const diagnostics = compilerDiagnostics(message, compiledSource);
  editor.dispatch(setDiagnostics(editor.state, diagnostics));
  $('diagnostics').replaceChildren();
  for (const diagnostic of diagnostics) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `पंक्ति ${diagnostic.line}, स्तंभ ${diagnostic.column}: ${diagnostic.message}`;
    button.addEventListener('click', () => {
      editor.dispatch({selection: {anchor: diagnostic.from, head: diagnostic.to}, scrollIntoView: true});
      editor.focus();
    });
    $('diagnostics').append(button);
  }
}
function save() { try { localStorage.setItem('bhash-draft', JSON.stringify({source: editor.state.doc.toString(), mode: $('mode').value, input: $('stdin').value})); $('saved').textContent = 'SAVED LOCALLY'; } catch { $('saved').textContent = 'STORAGE UNAVAILABLE'; } }
$('stdin').addEventListener('input', save); $('mode').addEventListener('change', save);
$('example').addEventListener('change', () => { const item = examples[$('example').value]; track('playground_example_select', {example: $('example').value}); editor.dispatch({changes: {from: 0, to: editor.state.doc.length, insert: item.source}}); $('mode').value = item.mode; $('stdin').value = item.input; save(); });
for (const button of document.querySelectorAll('[data-insert]')) button.addEventListener('click', () => { const insert = button.dataset.insert.replaceAll('\\"', '"'); editor.dispatch(editor.state.replaceSelection(insert)); editor.focus(); });
for (const name of ['output', 'input']) $(`${name}-tab`).addEventListener('click', () => { for (const other of ['output', 'input']) { $(`${other}-tab`).classList.toggle('selected', other === name); $(`${other}-tab`).setAttribute('aria-selected', String(other === name)); $(`${other}-panel`).hidden = other !== name; } });
$('download').addEventListener('click', () => {
  const text = editor.state.doc.toString(), source = $('mode').value === 'scratch' ? `कार्य मुख्य() -> शून्य {\n${text}\n}\n` : text;
  const url = URL.createObjectURL(new Blob([source], {type: 'text/plain;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = 'playground.bhash'; a.click(); track('playground_download'); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('upload').addEventListener('change', async event => { const file = event.target.files[0]; if (!file) return; if (file.size > 131072) { $('errors').textContent = 'File is too large.'; return; } const source = await file.text(); if (source.length > 32768) { $('errors').textContent = 'File is too large.'; return; } editor.dispatch({changes: {from: 0, to: editor.state.doc.length, insert: source.replace(/^\uFEFF/, '')}}); $('mode').value = 'program'; save(); });
$('share').addEventListener('click', async () => {
  if (editor.state.doc.length > 6000) { $('errors').textContent = 'Download larger programs instead of sharing them as a link.'; return; }
  const bytes = new TextEncoder().encode(editor.state.doc.toString());
  const hash = new URLSearchParams({mode: $('mode').value, code: btoa(Array.from(bytes, b => String.fromCharCode(b)).join(''))});
  const url = `${location.origin}${location.pathname}#${hash}`;
  try { await navigator.clipboard.writeText(url); track('playground_share'); $('status').textContent = 'Link copied · लिंक कॉपी हुआ'; }
  catch { $('errors').textContent = `Copy this link:\n${url}`; }
});
let config, current = 0, abort, frame, timer, nonce;
function finish(message) { abort?.abort(); abort = null; clearTimeout(timer); frame?.remove(); frame = null; nonce = null; $('run').disabled = !config || !navigator.onLine; $('stop').disabled = true; $('indicator').classList.remove('active'); $('status').textContent = message; }
function stop(message = 'Stopped · रोक दिया गया') { current++; frame?.contentWindow?.postMessage({type: 'stop'}, config?.runOrigin ?? location.origin); finish(message); }
$('stop').addEventListener('click', () => { track('playground_stop'); stop(); });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function run() {
  if (!config || !navigator.onLine || $('run').disabled) return;
  track('playground_run', {mode: $('mode').value});
  clearCompilerErrors();
  const compiledSource = editor.state.doc.toString();
  save(); const id = ++current; $('output').textContent = ''; $('errors').textContent = ''; $('output-tab').click();
  $('run').disabled = true; $('stop').disabled = false; $('indicator').classList.add('active'); $('status').textContent = 'Compiling · तैयार किया जा रहा है…';
  abort = new AbortController(); timer = setTimeout(() => stop('Compilation timed out. Try again shortly.'), 240000);
  try {
    const response = await fetch('/api/compile', {method: 'POST', headers: {'X-Bhash-Playground': '1'}, body: new URLSearchParams({source: compiledSource, mode: $('mode').value}), signal: abort.signal});
    let job = await response.json(); if (!response.ok) throw new Error(job.error || 'Compilation service unavailable');
    while (['queued', 'compiling'].includes(job.status)) {
      $('status').textContent = job.status === 'queued' ? 'Queued · अपनी बारी की प्रतीक्षा…' : 'Compiling · तैयार किया जा रहा है…';
      await pause(1000); if (id !== current) return;
      const status = await fetch(`/api/jobs/${job.id}`, {signal: abort.signal}); job = await status.json();
      if (!status.ok) throw new Error(job.error || 'Job expired; run again');
    }
    if (id !== current) return;
    if (job.status !== 'ready') throw new Error(job.error || 'Compilation failed');
    const url = new URL(job.url); if (url.origin !== config.runOrigin) throw new Error('Invalid execution origin');
    track('playground_compile_success');
    clearTimeout(timer); $('status').textContent = 'Loading runtime · रनटाइम लोड हो रहा है…';
    frame = document.createElement('iframe'); frame.title = 'Isolated Bhash execution'; frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
    nonce = crypto.randomUUID(); frame.src = url.href; $('runner-slot').append(frame);
    timer = setTimeout(() => stop('Runtime loading timed out. Check the connection and try again.'), 90000);
  } catch (error) { if (id !== current) return; track('playground_run_failed', {stage: 'compile'}); showCompilerErrors(error.message, compiledSource); finish('Could not run · कार्यक्रम नहीं चला'); }
}
$('run').addEventListener('click', run);
addEventListener('keydown', event => { if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); run(); } });
addEventListener('message', event => {
  if (!frame || event.source !== frame.contentWindow || event.origin !== config.runOrigin || !event.data) return;
  if (event.data.type === 'ready') { frame.contentWindow.postMessage({type: 'start', input: $('stdin').value, nonce}, config.runOrigin); return; }
  if (event.data.nonce !== nonce) return;
  if (event.data.type === 'running') { clearTimeout(timer); $('status').textContent = 'Running · चल रहा है…'; timer = setTimeout(() => stop('Execution stopped after 15 seconds.'), config.executionSeconds * 1000); return; }
  if (event.data.type === 'result') { track('playground_run_complete', {outcome: event.data.exitCode === 0 ? 'success' : 'error'}); $('output').textContent = event.data.output || ''; $('errors').textContent = event.data.errors || ''; finish(`Finished (exit ${event.data.exitCode}) · पूरा हुआ`); }
});
async function connect() {
  $('network').textContent = navigator.onLine ? 'Online compilation' : 'Offline · editing available';
  if (!navigator.onLine) { config = null; finish('Offline · drafts and editing available'); return; }
  try { const response = await fetch('/api/config'); if (!response.ok) throw new Error(); config = await response.json(); config.runOrigin = new URL(config.runOrigin).origin; finish('Ready · लिखना शुरू करें'); }
  catch { config = null; finish('Compiler unavailable · editing is available'); }
}
addEventListener('online', connect); addEventListener('offline', () => { stop('Offline · editing is available'); connect(); });
connect();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
