package io.github.gccammelli.pandy

/**
 * The model sites Pandy browses. MakerWorld hands over complete Bambu projects; Thingiverse and Printables mostly offer
 * plain STL (or 3MF) files, which Pandy wraps into a 3MF (see [stlTo3mf]) for Pandy Slicer.
 */
enum class ModelSite(val label: String, val home: String, private val hosts: List<String>, private val fileHosts: List<String> = hosts) {
    MakerWorld("MakerWorld", "https://makerworld.com/en", listOf("makerworld.com"), listOf("makerworld.com", "bblmw.com")),
    Thingiverse("Thingiverse", "https://www.thingiverse.com", listOf("thingiverse.com")),
    Printables("Printables", "https://www.printables.com", listOf("printables.com"));

    /** Whether [host] is the site's own (or a subdomain of it). */
    fun ownsPage(host: String?): Boolean = host?.lowercase()?.removeSuffix(".")?.let { h -> hosts.any { h == it || h.endsWith(".$it") } } == true

    /** Whether [url] is an https address of a host the site serves its files from (no credentials, backslashes or spaces). */
    fun ownsFile(url: String): Boolean {
        if (!url.startsWith("https://") || url.any { it == '\\' || it == '@' || it.isWhitespace() }) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        return host.isNotEmpty() && fileHosts.any { host == it || host.endsWith(".$it") }
    }

    /** The other sites, for the long-press menu. */
    val others: List<ModelSite> get() = entries.filter { it != this }
}

/** Hosts a model site's page may load in Pandy: the sites, Bambu's sign-in, and the sign-in providers. */
internal val SITE_HOSTS = listOf("makerworld.com", "bambulab.com", "bblmw.com", "thingiverse.com", "printables.com", "prusa3d.com",
    "appleid.apple.com", "accounts.google.com", "facebook.com")

/** Whether the site's Download button was pressed by Pandy's Print button just now: "1" or "0" (a string, the same on every platform). */
const val PRINT_INTENT_JS = "(Date.now() < (window.__pandyPrintUntil || 0)) ? '1' : '0'"

/** Files Pandy can slice: STL and 3MF. [name] may carry a query string. */
fun isPrintableModel(name: String) = name.substringBefore('?').let { '.' in it && it.substringAfterLast('.').lowercase() in setOf("stl", "3mf") }

/**
 * Thingiverse and Printables pages. Thingiverse makes its downloads in the page and saves them as an anonymous blob, so this
 * remembers the name the page gives the download link, for the blob that follows. While Pandy can print (window.__pandyCanPrint,
 * see [canPrintJs]) every STL or 3MF file row gets Pandy's own magenta "Print" button (with the Pandy bear) beside the site's
 * buttons. It presses the site's Download button and notes that for the next few seconds ([PRINT_INTENT_JS]), so that download
 * opens Pandy's print sheet; the site's own Download button stays a plain download that is saved.
 */
const val SITE_DOWNLOAD_JS = """
(function(){
  if (window.__pandySite) return; window.__pandySite = true;
  const remember = a => { if (a && a.getAttribute && a.getAttribute('download')) window.__pandyDownloadName = a.getAttribute('download'); };
  document.addEventListener('click', e => remember(e.target && e.target.closest && e.target.closest('a[download]')), true);
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ remember(this); return click.apply(this, arguments); };
  // A file row names its model file (Thingiverse "3DBenchy.stl", Printables "STL" tag) and has a plain "Download" button.
  const MODEL = /\.(stl|3mf)\b|\b(stl|3mf)\b/i;
  const canPrint = () => window.__pandyCanPrint === true;
  const LOGO = 'data:image/png;base64,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';
  const css = document.createElement('style');
  css.textContent = `
    .pandy-print-btn { display: none !important; }
    html.pandy-print .pandy-print-btn { display: inline-flex !important; align-items: center; justify-content: center; gap: 6px; box-sizing: border-box;
      background: #AE006D !important; border: 1px solid #AE006D !important; color: #fff !important; font-weight: 600; cursor: pointer; }
    html.pandy-print .pandy-print-btn:hover, html.pandy-print .pandy-print-btn:active { background: #7E004F !important; border-color: #7E004F !important; }
    .pandy-print-btn img { height: 1.25em; width: auto; flex: none; display: block; }`;
  (document.head || document.documentElement).appendChild(css);
  function addPrint(){
    document.documentElement.classList.toggle('pandy-print', canPrint());
    for (const button of document.querySelectorAll('button,a')) {
      if (button.classList.contains('pandy-print-btn')) continue;
      let text = null;
      const walk = document.createTreeWalker(button, NodeFilter.SHOW_TEXT);
      for (let n = walk.nextNode(); n && !text; n = walk.nextNode()) if (/^(download|print)$/i.test(n.nodeValue.trim())) text = n;
      if (!text) continue;
      if (button.__pandyModel === undefined) {
        let row = button, found = false;
        for (let i = 0; i < 5 && row.parentElement && !found; i++) { row = row.parentElement; found = row.innerText.length < 240 && MODEL.test(row.innerText); }
        button.__pandyModel = found && !/gcode|print file/i.test(row.innerText);
      }
      if (!button.__pandyModel) continue;
      const before = button.previousElementSibling;
      if (before && before.classList.contains('pandy-print-btn')) continue;
      // Same look as the site's own buttons in the row (its classes: size, radius, font), in Pandy's magenta.
      const print = document.createElement('button');
      print.type = 'button';
      print.className = button.className + ' pandy-print-btn';
      print.title = 'Print with Pandy';
      const logo = document.createElement('img'); logo.src = LOGO; logo.alt = '';
      print.append(logo, document.createTextNode('Print'));
      print.addEventListener('click', e => {
        e.preventDefault(); e.stopPropagation();
        window.__pandyPrintUntil = Date.now() + 20000;
        button.click();
      }, true);
      button.parentElement.insertBefore(print, button);
    }
  }
  // Printables' cookie notice dims the page and, in a web view, can sit out of sight and out of reach: decline it (the privacy-friendly
  // choice) so the page can be used. Only a visible Reject button sitting next to an Accept button is pressed (the notice's pair).
  const REJECT = /^(reject all|reject|decline all|decline|only necessary|necessary only|deny all)$/i;
  const ACCEPT = /^(accept all|accept|allow all|agree)$/i;
  const label = e => (e.innerText || e.textContent || '').trim();
  const shown = e => e.offsetParent !== null || getComputedStyle(e).position === 'fixed';
  function consent(){
    const buttons = [...document.querySelectorAll('button,[role="button"]')].filter(shown);
    for (const button of buttons) {
      if (!REJECT.test(label(button))) continue;
      let box = button;
      for (let i = 0; i < 4 && box.parentElement; i++) {
        box = box.parentElement;
        if ([...box.querySelectorAll('button,[role="button"]')].some(other => other !== button && shown(other) && ACCEPT.test(label(other)))) { button.click(); return; }
      }
    }
  }
  const repatch = () => { try { addPrint(); } catch (e) {} try { consent(); } catch (e) {} };
  window.__pandyRepatch = repatch;
  let queued = false;
  new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; repatch(); }); } })
    .observe(document.body || document.documentElement, { childList: true, subtree: true });
  repatch();
})();
"""
