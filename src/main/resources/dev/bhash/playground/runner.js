// SPDX-License-Identifier: Apache-2.0
import {parentOrigin} from './runner-config.js';
let worker, nonce;
const send = data => parent.postMessage({...data, nonce}, parentOrigin);
addEventListener('message', event => {
  if (event.origin !== parentOrigin || event.source !== parent) return;
  if (event.data.type === 'stop') { worker?.terminate(); worker = null; return; }
  if (event.data.type !== 'start' || worker || typeof event.data.nonce !== 'string' || typeof event.data.input !== 'string' || event.data.input.length > 16384) return;
  nonce = event.data.nonce;
  try {
    worker = new Worker(new URL('./playground-worker.js', import.meta.url), {type: 'module'});
    worker.onmessage = ({data}) => { if (data.type === 'running') { send(data); return; } send({type: 'result', ...data}); worker.terminate(); worker = null; };
    worker.onerror = event => { send({type: 'result', output: '', errors: event.message || 'WebAssembly failed to load', exitCode: 1}); worker.terminate(); worker = null; };
    worker.postMessage({input: event.data.input});
  } catch (error) { send({type: 'result', output: '', errors: String(error), exitCode: 1}); }
});
parent.postMessage({type: 'ready'}, parentOrigin);
