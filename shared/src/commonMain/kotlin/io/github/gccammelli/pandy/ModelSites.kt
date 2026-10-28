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

/** Files Pandy can slice: STL and 3MF. [name] may carry a query string. */
fun isPrintableModel(name: String) = name.substringBefore('?').let { '.' in it && it.substringAfterLast('.').lowercase() in setOf("stl", "3mf") }

/**
 * Thingiverse and Printables pages. Thingiverse makes its downloads in the page and saves them as an anonymous blob, so this
 * remembers the name the page gives the download link, for the blob that follows. While Pandy can print (window.__pandyCanPrint,
 * see [canPrintJs]) the "Download" button of an STL or 3MF file reads "Print": the download then opens Pandy's print sheet.
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
  function relabel(){
    for (const button of document.querySelectorAll('button,a')) {
      let text = null;
      const walk = document.createTreeWalker(button, NodeFilter.SHOW_TEXT);
      for (let n = walk.nextNode(); n && !text; n = walk.nextNode()) if (/^(download|print)$/i.test(n.nodeValue.trim())) text = n;
      if (!text) continue;
      if (button.__pandyLabel === undefined) {
        let row = button, found = false;
        for (let i = 0; i < 5 && row.parentElement && !found; i++) { row = row.parentElement; found = row.innerText.length < 240 && MODEL.test(row.innerText); }
        button.__pandyLabel = found && !/gcode|print file/i.test(row.innerText) ? text.nodeValue : null;
      }
      if (button.__pandyLabel === null) continue;
      const want = canPrint() ? 'Print' : button.__pandyLabel;
      if (text.nodeValue !== want) text.nodeValue = want;
    }
  }
  window.__pandyRepatch = relabel;
  let queued = false;
  new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; relabel(); }); } })
    .observe(document.body || document.documentElement, { childList: true, subtree: true });
  relabel();
})();
"""
