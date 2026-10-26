// SPDX-License-Identifier: Apache-2.0
// Shared GA4 configuration. Never pass source, input, errors, FEN or user text.
(() => {
  'use strict';
  const id = 'G-J31MR361WE';
  const sites = {'bhashitva.org':'website', 'www.bhashitva.org':'website',
    'play.bhashitva.org':'playground', 'chess.bhashitva.org':'chess'};
  const site = sites[location.hostname];
  if (!site || window.bhashAnalytics) return;
  if (navigator.globalPrivacyControl || navigator.doNotTrack === '1') return;
  const cleanURL = (value, campaigns = false) => {
    try {
      const url = new URL(value);
      const clean = new URL(url.origin + url.pathname);
      if (campaigns) for (const key of ['utm_source','utm_medium','utm_campaign']) {
        const value = url.searchParams.get(key);
        if (value && /^[a-zA-Z0-9_-]{1,80}$/.test(value)) clean.searchParams.set(key,value);
      }
      return clean.href;
    } catch { return ''; }
  };
  const context = {page_location: cleanURL(location.href,true), page_referrer: cleanURL(document.referrer),
    page_title: site === 'website' ? 'Bhashitva' : site === 'chess' ? 'Bhash Chess' : 'Bhash Playground'};
  window.dataLayer = window.dataLayer || [];
  window.gtag = window.gtag || function () { window.dataLayer.push(arguments); };
  const gtag = window.gtag;
  gtag('js', new Date());
  gtag('set', context);
  gtag('config', id, {...context, cookie_domain:'bhashitva.org', site_area:site,
    allow_google_signals:false, allow_ad_personalization_signals:false});
  const events = new Set(['playground_run','playground_compile_success','playground_run_complete',
    'playground_run_failed','playground_stop','playground_example_select','playground_download',
    'playground_share','chess_game_start','chess_game_complete','chess_restart',
    'donation_click','resource_click']);
  const values = {mode:['scratch','program','ai','local'], example:['hello','factorial','collections','types'],
    outcome:['success','error','checkmate','stalemate','draw'], destination:['playground','chess','lessons','github','vscode'],
    stage:['compile','runtime']};
  window.bhashAnalytics = (name, parameters = {}) => {
    if (!events.has(name)) return;
    const safe = {send_to:id, site_area:site, ...context};
    for (const [key, allowed] of Object.entries(values)) if (allowed.includes(parameters[key])) safe[key] = parameters[key];
    try { gtag('event',name,safe); } catch { /* Analytics must never interrupt an application. */ }
  };
  document.addEventListener('click', event => {
    const link = event.target.closest?.('a[href]');
    if (!link) return;
    try {
      const url = new URL(link.href,location.href);
      if (url.hostname === 'www.zeffy.com' || url.hostname === 'zeffy.com') window.bhashAnalytics('donation_click');
      else {
        const destination = url.hostname === 'play.bhashitva.org' ? 'playground' :
          url.hostname === 'chess.bhashitva.org' ? 'chess' :
          url.hostname === 'marketplace.visualstudio.com' ? 'vscode' :
          url.hostname === 'github.com' ? (url.pathname.includes('/lessons') ? 'lessons' : 'github') : null;
        if (destination) window.bhashAnalytics('resource_click',{destination});
      }
    } catch { /* Ignore non-URL links. */ }
  });
  const script = document.createElement('script');
  script.async = true; script.src = 'https://www.googletagmanager.com/gtag/js?id=' + id;
  script.referrerPolicy = 'no-referrer'; document.head.append(script);
})();
