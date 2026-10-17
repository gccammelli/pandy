package io.github.gccammelli.pandy

/**
 * MakerWorld's "Open in Bambu Handy" (or "…Studio") button becomes Pandy's magenta "Print": tapping it does what the
 * menu's "Download 3MF" does for the chosen profile, and the download comes to Pandy's print sheet. The menu arrow
 * stays as it is. Re-applied as the page changes (it's a single-page app). Written against MakerWorld's markup of
 * October 2026; debug builds let chrome://inspect look at the live page when it changes.
 */
const val PRINT_BUTTON_JS = """
(function(){
  if (window.__pandy) return; window.__pandy = true;
  // MakerWorld's split button: <div><span class="primaryButton"><span>Open in Bambu Handy</span></span><div class="icon-box">⌄</div></div>.
  // It reads "Print" on Pandy's magenta (a stylesheet, so pressed and hover states and re-renders keep it); the arrow
  // keeps MakerWorld's menu. Tapping Print picks the profile to print and asks MakerWorld's own API (the one its
  // "Download 3MF" uses, signed in through this page) for its file link, which Pandy downloads.
  const css = document.createElement('style');
  css.textContent = `
    div:has(> span.primaryButton), div:has(> span.primaryButton):hover, div:has(> span.primaryButton):active { background: #AE006D !important; border-color: #AE006D !important; }
    div:has(> span.primaryButton) span.primaryButton, div:has(> span.primaryButton) span.primaryButton * { background: transparent !important; color: #fff !important; }
    div:has(> span.primaryButton) > .icon-box, div:has(> span.primaryButton) > .icon-box:hover { background: #7E004F !important; }`;
  (document.head || document.documentElement).appendChild(css);
  const LABEL = /^(open in bambu (handy|studio)|print)$/i;
  const product = i => i && i.extention && i.extention.modelInfo && i.extention.modelInfo.compatibility && i.extention.modelInfo.compatibility.devProductName;
  // The selected profile if it was made for this printer, else the model's profile made for it, else the selected one.
  async function choose(){
    const selected = +((location.hash.match(/profileId-(\d+)/) || [])[1] || 0);
    const design = (location.pathname.match(/models\/(\d+)/) || [])[1];
    const mine = window.__pandyPrinter;
    let list = [], fallback = null;
    try {
      const d = await (await fetch('/api/v1/design-service/design/' + design, { credentials: 'include' })).json();
      list = d.instances || []; fallback = d.defaultInstanceId;
    } catch (e) {}
    const sel = list.find(i => i.id === selected);
    if (!mine || !list.length || (sel && product(sel) === mine)) return { id: selected || fallback };
    const made = list.find(i => product(i) === mine);
    if (made) return { id: made.id, note: 'Printing the ' + mine + ' profile: ' + made.title };
    return { id: selected || fallback };
  }
  let busy = false;
  async function print(){
    if (busy) return; busy = true;
    try {
      const c = await choose();
      if (!c.id) { PandyBridge.problem('Pick a print profile first (the list above the button).'); return; }
      const r = await fetch('/api/v1/design-service/instance/' + c.id + '/f3mf?type=download', { credentials: 'include' });
      if (r.status === 401 || r.status === 403) { PandyBridge.problem('Sign in to MakerWorld first (top right).'); return; }
      const j = await r.json();
      if (!r.ok || !j.url) { PandyBridge.problem(j.message || j.error || ('MakerWorld answered ' + r.status)); return; }
      if (c.note) PandyBridge.problem(c.note);
      PandyBridge.file(j.name || ('profile-' + c.id + '.3mf'), j.url);
    } catch (e) { PandyBridge.problem('Couldn\'t get the file from MakerWorld: ' + e); }
    finally { busy = false; }
  }
  function patch(){
    for (const label of document.querySelectorAll('span.primaryButton')) {
      if (!LABEL.test((label.textContent || '').trim())) continue;
      const leaf = [...label.querySelectorAll('span')].pop() || label;
      if (leaf.textContent !== 'Print') leaf.textContent = 'Print';
      if (label.__pandy) continue;
      label.__pandy = true;
      label.addEventListener('click', e => { e.preventDefault(); e.stopImmediatePropagation(); print(); }, true);
    }
  }
  // Re-checked once per frame at most when the page changes (MakerWorld re-renders a lot).
  let queued = false;
  new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; patch(); }); } })
    .observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
  patch();
})();
"""
