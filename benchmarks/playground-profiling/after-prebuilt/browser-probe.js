// SPDX-License-Identifier: Apache-2.0
// Temporary DevTools measurement probe. No deployment or compiler changes.
// Records only timing, job status, resource metadata and output length.
(() => {
  if (location.origin !== 'https://play.bhashitva.org') throw Error('Open the Bhash playground first.');
  if (window.__bhashPerf) throw Error('Probe already installed; use __bhashPerf.cleanup() first.');
  const originalFetch = window.fetch;
  const runs = [];
  let active, frameSource;
  const now = () => performance.now();
  const state = {label: 'unlabelled', runs, installedAt: new Date().toISOString(),
    browser: navigator.userAgent, timeOrigin: performance.timeOrigin};
  const click = () => {
    if (document.getElementById('run').disabled) return;
    frameSource = undefined;
    active = {label: state.label, click: now(), wallTime: new Date().toISOString(),
      mode: document.getElementById('mode').value, api: [], messages: [], statuses: []};
    runs.push(active);
  };
  document.getElementById('run').addEventListener('click', click, true);
  const key = event => {if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') click();};
  document.addEventListener('keydown', key, true);
  const wrappedFetch = new Proxy(originalFetch, {apply(target, receiver, args) {
    const path = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href);
    const run = active;
    const entry = run && path.origin === location.origin && /^\/api\/(compile|jobs\/)/.test(path.pathname)
      ? {path: path.pathname, start: now()} : null;
    if (entry) run.api.push(entry);
    const promise = Reflect.apply(target, receiver, args);
    // Return the original promise, without waiting for the diagnostic copy.
    if (entry) promise.then(response => {
      entry.headers = now(); entry.httpStatus = response.status;
      return response.clone().json().then(job => {
        entry.bodyObserved = now(); entry.jobId = job.id; entry.status = job.status; entry.timings = job.timings;
        if (job.url) run.runnerUrl = job.url;
      });
    }).catch(error => {entry.error = String(error);});
    return promise;
  }});
  window.fetch = wrappedFetch;
  const message = event => {
    if (!active || event.origin !== 'https://run.bhashitva.org') return;
    const type = event.data?.type;
    if (!['ready', 'running', 'result'].includes(type)) return;
    const frame = document.querySelector('#runner-slot iframe');
    if (type === 'ready' && frame && event.source === frame.contentWindow) frameSource = event.source;
    // The application removes the iframe in its result listener; retain the
    // previously verified source so listener ordering cannot lose that event.
    if (!frameSource || event.source !== frameSource) return;
    const t = now(); active.messages.push({type, t});
    if (type === 'result') {
      active.result = t; active.exitCode = event.data.exitCode;
      active.outputCharacters = (event.data.output || '').length;
      active.errorCharacters = (event.data.errors || '').length;
    }
  };
  window.addEventListener('message', message, true);
  const outputObserver = new MutationObserver(() => {
    if (!active || active.firstVisibleOutput !== undefined || !document.getElementById('output').textContent) return;
    const run = active; run.firstVisibleOutput = now();
    requestAnimationFrame(() => {run.nextAnimationFrame = now();});
  });
  outputObserver.observe(document.getElementById('output'), {childList: true, characterData: true, subtree: true});
  const statusObserver = new MutationObserver(() => {
    if (active) active.statuses.push({t: now(), text: document.getElementById('status').textContent});
  });
  statusObserver.observe(document.getElementById('status'), {childList: true, characterData: true, subtree: true});
  state.export = () => {
    const data = {installedAt: state.installedAt, browser: state.browser, timeOrigin: state.timeOrigin, runs,
      resources: performance.getEntriesByType('resource')
        .filter(e => ['https://play.bhashitva.org', 'https://run.bhashitva.org'].includes(new URL(e.name).origin))
        .map(e => e.toJSON())};
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'}));
    const link = document.createElement('a'); link.href = url; link.download = 'bhash-playground-after-5a8fb5e.json';
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    return `${runs.length} runs exported`;
  };
  state.cleanup = () => {
    if (window.fetch === wrappedFetch) window.fetch = originalFetch;
    document.getElementById('run').removeEventListener('click', click, true);
    document.removeEventListener('keydown', key, true);
    window.removeEventListener('message', message, true);
    outputObserver.disconnect(); statusObserver.disconnect(); delete window.__bhashPerf;
  };
  window.__bhashPerf = state;
  return 'Bhash timing probe installed. Label each run; export after completion; cleanup when finished.';
})();
