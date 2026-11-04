// SPDX-License-Identifier: Apache-2.0
const form = document.querySelector('form');
const run = document.querySelector('#run');
const stop = document.querySelector('#stop');
const output = document.querySelector('#output');
const errors = document.querySelector('#errors');
const status = document.querySelector('#status');
let worker;
let stopping;
function finish(message) {
    clearTimeout(stopping);
    stopping=undefined;
    worker?.terminate();
    worker = undefined;
    run.disabled = false;
    stop.disabled = true;
    status.textContent = message;
}
form.addEventListener('submit', event => {
    event.preventDefault();
    if (worker) return;
    output.textContent = '';
    errors.textContent = '';
    run.disabled = true;
    stop.disabled = false;
    status.textContent = 'Running…';
    try {
        worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
        worker.onmessage = ({ data }) => {
            if(data.type==='running')return;
            output.textContent = data.output;
            errors.textContent = data.errors;
            finish(stopping?'Stopped':`Finished (exit ${data.exitCode})`);
        };
        worker.onerror = event => {
            errors.textContent = event.message || 'Unable to load the WebAssembly runtime.';
            finish('Failed');
        };
        worker.postMessage({ input: document.querySelector('#input').value });
    } catch (error) {
        errors.textContent = String(error);
        finish('Failed');
    }
});
stop.addEventListener('click', () => {
    if(!worker)return;
    stop.disabled=true;
    status.textContent='Stopping…';
    worker.postMessage({type:'cancel'});
    stopping=setTimeout(()=>finish('Stopped'),1000);
});
