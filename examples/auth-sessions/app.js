// SPDX-License-Identifier: Apache-2.0
'use strict';
let session;
const el = id => document.getElementById(id);
async function request(path, method = 'GET', body) {
  const headers = {};
  if (method !== 'GET') headers['X-CSRF-Token'] = session.csrf;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const reply = await fetch(path, { method, headers, credentials: 'same-origin', cache: 'no-store', body: body === undefined ? undefined : JSON.stringify(body) });
  if (!reply.ok) throw new Error(reply.status === 401 ? 'नाम या कूटशब्द जाँचें; प्रवेश आवश्यक है।' : reply.status === 403 ? 'अनुमति या सत्र सुरक्षा जाँच असफल। पृष्ठ फिर खोलें।' : reply.status === 429 ? 'बहुत अधिक प्रयास। कुछ समय बाद फिर करें।' : 'अनुरोध असफल: ' + reply.status);
  if (reply.status === 204) return;
  return reply.headers.get('Content-Type')?.includes('application/json') ? reply.json() : reply.text();
}
async function refresh() {
  session = await request('/session');
  el('login').hidden = session.authenticated;
  el('login').querySelector('button').disabled = false;
  el('account').hidden = !session.authenticated;
  el('status').textContent = session.authenticated ? 'आपका सत्र सक्रिय है।' : 'प्रवेश के लिए तैयार।';
  el('welcome').textContent = 'नमस्ते, ' + session.user;
  el('users').textContent = '';
  el('notes').replaceChildren();
  if (session.authenticated) await notes();
}
async function notes() {
  const values = await request('/api/notes');
  el('notes').replaceChildren(...values.map(text => { const item = document.createElement('li'); item.textContent = text; return item; }));
}
const report = error => { el('status').textContent = error.message; };
el('login').addEventListener('submit', async event => {
  event.preventDefault(); const form = event.currentTarget;
  try { session = await request('/login', 'POST', { username: form.elements.username.value, password: form.elements.password.value }); form.elements.password.value = ''; await refresh(); }
  catch (error) { form.elements.password.value = ''; report(error); }
});
el('logout').addEventListener('click', async () => { try { await request('/logout', 'POST'); session = undefined; await refresh(); } catch (error) { report(error); } });
el('note').addEventListener('submit', async event => {
  event.preventDefault(); const form = event.currentTarget;
  try { await request('/api/notes', 'POST', { text: form.elements.text.value }); form.reset(); await notes(); } catch (error) { report(error); }
});
el('admin').addEventListener('click', async () => { try { el('users').textContent = (await request('/api/admin/users')).join(', '); } catch (error) { report(error); } });
refresh().catch(report);
