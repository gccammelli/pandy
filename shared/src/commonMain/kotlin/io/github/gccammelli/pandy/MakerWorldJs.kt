package io.github.gccammelli.pandy

/**
 * MakerWorld's primary print action becomes Pandy's "Print": tapping it downloads the chosen profile to Pandy's print
 * sheet. On models MakerWorld can't hand to Handy the main button is "Download 3MF": that stays a download, and Pandy
 * adds its own Print above it. When Pandy can't print (window.__pandyCanPrint false: the printer takes commands only
 * from Bambu's apps) the page is left exactly as MakerWorld made it. Re-applied as the page changes (it's a
 * single-page app). Written against MakerWorld's markup of October 2026; debug builds let chrome://inspect inspect it.
 */
const val PRINT_BUTTON_JS = """
(function(){
  if (window.__pandy) return; window.__pandy = true;
  // MakerWorld's split button: <div><span class="primaryButton"><span>Open in Bambu Handy</span></span><div class="icon-box">⌄</div></div>.
  // It reads "Print" on Pandy's magenta (a stylesheet, so pressed and hover states and re-renders keep it); the arrow
  // keeps MakerWorld's menu. Tapping Print picks the profile to print and asks MakerWorld's own API (the one its
  // "Download 3MF" uses, signed in through this page) for its file link, which Pandy downloads.
  // Styles apply only while Pandy can print (the class on <html>), so a cloud-mode printer leaves MakerWorld's look.
  const css = document.createElement('style');
  css.textContent = `
    html.pandy-print div.pandy-split, html.pandy-print div.pandy-split:hover, html.pandy-print div.pandy-split:active { background: #AE006D !important; border-color: #AE006D !important; }
    html.pandy-print div.pandy-split span.primaryButton, html.pandy-print div.pandy-split span.primaryButton * { background: transparent !important; color: #fff !important; }
    html.pandy-print div.pandy-split > .icon-box, html.pandy-print div.pandy-split > .icon-box:hover { background: #7E004F !important; }
    .pandy-print-button { display: none; }
    html.pandy-print .pandy-print-button { display: flex; align-items: center; justify-content: center; height: 48px; margin-bottom: 10px;
      border-radius: 8px; background: #AE006D; color: #fff; font-weight: 600; font-size: 17px; cursor: pointer; }`;
  (document.head || document.documentElement).appendChild(css);
  const LABEL = /^(open in bambu (handy|studio)|print)$/i;
  const DOWNLOAD = /^download 3mf$/i;
  const canPrint = () => window.__pandyCanPrint !== false;
  const variants = i => {
    const primary = i && i.extention && i.extention.modelInfo && i.extention.modelInfo.compatibility;
    const other = i && i.extention && i.extention.otherCompatibilityModelInfo;
    return (primary ? [primary] : []).concat(Array.isArray(other) ? other : []);
  };
  const variantFor = (i, printer) => variants(i).find(v => v && v.devProductName === printer && typeof v.devModelName === 'string' && v.devModelName);
  // MakerWorld keeps printer variants inside one instance. Pick the requested instance, then its exact printer
  // variant; the dev model tells the download API which variant to sign instead of silently using the default.
  async function choose(){
    const selected = +((location.hash.match(/profileId-(\d+)/) || [])[1] || 0);
    const design = (location.pathname.match(/models\/(\d+)/) || [])[1];
    const mine = window.__pandyPrinter;
    let list = [], fallback = null;
    try {
      const d = await (await fetch('/api/v1/design-service/design/' + design, { credentials: 'include' })).json();
      list = d.instances || []; fallback = d.defaultInstanceId;
    } catch (e) {}
    if (!mine || !list.length) return { id: selected || fallback };
    const sel = list.find(i => i.id === selected || i.profileId === selected);
    const preferred = sel || list.find(i => i.id === fallback || i.profileId === fallback);
    const selectedVariant = preferred && variantFor(preferred, mine);
    const made = (selectedVariant && { instance: preferred, variant: selectedVariant }) ||
      list.map(i => ({ instance: i, variant: variantFor(i, mine) })).find(x => x.variant);
    if (made) return { id: made.instance.id, devModelName: made.variant.devModelName,
      note: !preferred || made.instance.id !== preferred.id ? 'Using the ' + mine + ' printer variant.' : null };
    if (list.some(i => variants(i).some(v => v && v.devProductName === mine)))
      return { id: sel ? sel.id : (selected || fallback), note: 'The ' + mine + ' variant has no complete profile key; Pandy will convert the selected profile.' };
    return { id: sel ? sel.id : (selected || fallback), note: 'MakerWorld has no complete ' + mine + ' printer variant for this model; Pandy will convert the selected profile.' };
  }
  let busy = false;
  const bridgeToken = () => window.__pandyBridgeToken || '';
  async function print(){
    if (busy) return; busy = true;
    try {
      const c = await choose();
      if (c.error) { PandyBridge.problem(c.error, bridgeToken()); return; }
      if (!c.id) { PandyBridge.problem('Pick a print profile first (the list above the button).', bridgeToken()); return; }
      const variant = c.devModelName ? '&fileType=&devModelName=' + encodeURIComponent(c.devModelName) : '';
      const r = await fetch('/api/v1/design-service/instance/' + c.id + '/f3mf?type=download' + variant, { credentials: 'include' });
      if (r.status === 401 || r.status === 403) { PandyBridge.problem('Sign in to MakerWorld first (top right).', bridgeToken()); return; }
      const j = await r.json();
      if (!r.ok || !j.url) { PandyBridge.problem(j.message || j.error || ('MakerWorld answered ' + r.status), bridgeToken()); return; }
      if (c.note) PandyBridge.problem(c.note, bridgeToken());
      PandyBridge.file(j.name || ('profile-' + c.id + '.3mf'), j.url, bridgeToken());
    } catch (e) { PandyBridge.problem('Couldn\'t get the file from MakerWorld: ' + e, bridgeToken()); }
    finally { busy = false; }
  }
  window.__pandyPrint = print;
  const HANDY_ITEM = /^(open in bambu handy|open in bambu studio)$/i;
  // MakerWorld's "Get the full app experience" dialog: Pandy is the app. Press its own Cancel so the dimmed backdrop and scroll lock go too.
  // Said instead of the dialog, once per page load.
  function welcome(){
    if (window.__pandyWelcomed) return; window.__pandyWelcomed = true;
    const text = canPrint() ? 'Pandy starts prints from here: tap Print on any model.'
      : 'To start prints from here, connect your printer and Pandy Slicer in Me \u203A Settings.';
    const back = document.createElement('div');
    back.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#0008;display:flex;align-items:center;justify-content:center;padding:24px;font-family:sans-serif';
    back.innerHTML = '<div style="background:#fff;color:#222;border-radius:16px;padding:24px;max-width:340px;width:100%;text-align:center">' +
      '<div style="font-size:19px;font-weight:700;margin-bottom:12px">You are browsing Bambu Lab MakerWorld</div>' +
      '<div style="font-size:15px;line-height:1.45;margin-bottom:20px"></div>' +
      '<div style="background:#AE006D;color:#fff;border-radius:8px;padding:13px;font-size:17px;font-weight:600">OK!</div></div>';
    back.querySelector('div > div:nth-child(2)').textContent = text;
    back.addEventListener('click', () => back.remove());
    document.body.appendChild(back);
  }
  function hideAppBanner(){
    for (const el of document.querySelectorAll('div,section,p,span,h1,h2,h3')) {
      if (el.children.length || !/get the full app experience/i.test(el.textContent || '')) continue;
      for (let box = el; box.parentElement && box !== document.body; box = box.parentElement) {
        const cancel = [...box.querySelectorAll('button,div,span')].find(b => !b.children.length && /^cancel$/i.test((b.textContent || '').trim()));
        if (cancel) { cancel.click(); welcome(); break; }
      }
    }
  }
  function patch(){
    hideAppBanner();
    document.documentElement.classList.toggle('pandy-print', canPrint());
    for (const label of document.querySelectorAll('span.primaryButton')) {
      const box = label.parentElement;
      const text = (label.textContent || '').trim();
      const leaf = [...label.querySelectorAll('span')].pop() || label;
      // "Download 3MF" as the main button stays a download; Pandy's own Print goes above it.
      if (DOWNLOAD.test(text)) {
        if (box && !(box.previousElementSibling && box.previousElementSibling.classList.contains('pandy-print-button'))) {
          const own = document.createElement('div');
          own.className = 'pandy-print-button'; own.textContent = 'Print';
          own.addEventListener('click', e => { e.preventDefault(); e.stopImmediatePropagation(); print(); });
          box.parentElement && box.parentElement.insertBefore(own, box);
        }
        if (box) box.classList.remove('pandy-split');
        continue;
      }
      if (!LABEL.test(text)) continue;
      if (!canPrint()) {
        // Pandy can't print: MakerWorld's own label and action come back.
        if (label.__pandyLabel && leaf.textContent === 'Print') leaf.textContent = label.__pandyLabel;
        if (box) box.classList.remove('pandy-split');
        continue;
      }
      if (box) box.classList.add('pandy-split');
      if (!/^print$/i.test(leaf.textContent.trim())) label.__pandyLabel = leaf.textContent;
      if (leaf.textContent !== 'Print') leaf.textContent = 'Print';
      if (label.__pandy) continue;
      label.__pandy = true;
      const button = label.parentElement || label;
      button.addEventListener('click', e => {
        // Cover bare padding around the text, but leave every other child (arrow/menu) to MakerWorld.
        const target = e.target;
        if (target !== button && !label.contains(target)) return;
        // MakerWorld reuses this node when the selected profile changes. Only hijack it while it still means Print.
        if (!canPrint() || !LABEL.test((label.textContent || '').trim())) return;
        e.preventDefault(); e.stopImmediatePropagation(); print();
      }, true);
    }
    // After a profile change MakerWorld puts Handy in the dropdown instead of the primary button.
    for (const span of document.querySelectorAll('div > span')) {
      const item = span.parentElement;
      if (!item) continue;
      if (item.children.length !== 1 || item.firstElementChild.tagName !== 'SPAN') continue;
      const text = (item.textContent || '').trim();
      if (item.__pandy) continue;
      if (!HANDY_ITEM.test(text)) continue;
      item.__pandy = true;
      item.addEventListener('click', e => {
        // The menu node can also be recycled for Download 3MF or another action.
        const current = (item.textContent || '').trim();
        if (!canPrint() || (!HANDY_ITEM.test(current) && !/^print$/i.test(current))) return;
        e.preventDefault(); e.stopImmediatePropagation(); print();
      }, true);
    }
  }
  window.__pandyRepatch = patch;
  // Re-checked once per frame at most when the page changes (MakerWorld re-renders a lot).
  let queued = false;
  new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; patch(); }); } })
    .observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
  patch();
})();
"""

/** Sites the MakerWorld page may open inside Pandy: MakerWorld, Bambu's sign-in and files, and the sign-in providers. */
private val IN_APP_HOSTS = listOf("makerworld.com", "bambulab.com", "bblmw.com", "appleid.apple.com", "accounts.google.com", "facebook.com")

/**
 * Whether a top-level page may load in Pandy's web view. Others open in the browser, so the bridge to the printer is
 * only ever next to MakerWorld; frames inside a page (sign-in captchas, videos) aren't affected.
 */
fun inAppPage(scheme: String?, host: String?): Boolean {
    val s = scheme?.lowercase()
    if (s in setOf("about", "blob", "data")) return true // made by the page itself
    if (s != "http" && s != "https") return false
    val h = host?.lowercase()?.removeSuffix(".") ?: return false
    return s == "https" && IN_APP_HOSTS.any { h == it || h.endsWith(".$it") }
}
