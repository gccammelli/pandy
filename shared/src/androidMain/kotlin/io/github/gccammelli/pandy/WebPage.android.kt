package io.github.gccammelli.pandy

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * MakerWorld's "Open in Bambu Handy" (or "…Studio") button becomes Pandy's magenta "Print": tapping it does what the
 * menu's "Download 3MF" does for the chosen profile, and the download comes to Pandy's print sheet. The menu arrow
 * stays as it is. Re-applied as the page changes (it's a single-page app). Written against MakerWorld's markup of
 * October 2026; debug builds let chrome://inspect look at the live page when it changes.
 */
private const val PRINT_BUTTON_JS = """
(function(){
  if (window.__pandy) return; window.__pandy = true;
  // MakerWorld's split button: <div><span class="primaryButton"><span>Open in Bambu Handy</span></span><div class="icon-box">⌄</div></div>.
  // The label reads "Print" on Pandy's magenta; the arrow keeps MakerWorld's menu. Tapping Print asks MakerWorld's own
  // API (the one its "Download 3MF" uses, signed in through this page) for the selected profile's file link and hands
  // it to Pandy, which downloads it: no simulated clicks, no copy of the file inside the page.
  const LABEL = /^(open in bambu (handy|studio)|print)$/i;
  let busy = false;
  async function print(){
    const id = (location.hash.match(/profileId-(\d+)/) || [])[1];
    if (!id) { PandyBridge.problem('Pick a print profile first (the list above the button).'); return; }
    if (busy) return; busy = true;
    try {
      const r = await fetch('/api/v1/design-service/instance/' + id + '/f3mf?type=download', { credentials: 'include' });
      if (r.status === 401 || r.status === 403) { PandyBridge.problem('Sign in to MakerWorld first (top right).'); return; }
      const j = await r.json();
      if (!r.ok || !j.url) { PandyBridge.problem(j.message || j.error || ('MakerWorld answered ' + r.status)); return; }
      PandyBridge.file(j.name || ('profile-' + id + '.3mf'), j.url);
    } catch (e) { PandyBridge.problem('Couldn\'t get the file from MakerWorld: ' + e); }
    finally { busy = false; }
  }
  function patch(){
    for (const label of document.querySelectorAll('span.primaryButton')) {
      if (!LABEL.test((label.textContent || '').trim())) continue;
      const box = label.parentElement;
      const leaf = [...label.querySelectorAll('span')].pop() || label;
      if (leaf.textContent !== 'Print') leaf.textContent = 'Print';
      box.style.setProperty('background', '#AE006D', 'important');
      const arrow = box.querySelector('.icon-box');
      if (arrow) arrow.style.setProperty('background', '#7E004F', 'important');
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

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?) {
    var view: WebView? = null
    AndroidView({ ctx ->
        // Debug builds: the page can be inspected from a computer (chrome://inspect), to keep up with MakerWorld's changes.
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // MakerWorld's sign-in keeps its session in cookies; keep them across launches.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.message().startsWith("pandy")) android.util.Log.d("PandyWeb", m.message())
                    return super.onConsoleMessage(m)
                }
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            addJavascriptInterface(object {
                @JavascriptInterface fun blob(name: String, base64: String) {
                    val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull()
                    main.post { if (bytes != null) onDownload?.invoke(WebDownload(null, name, null, bytes)) else toast("Couldn't read that download") }
                }
                /** The Print button: a signed link to the chosen profile's 3MF, fetched by the app. */
                @JavascriptInterface fun file(name: String, url: String) {
                    if (!url.startsWith("https://")) return
                    main.post { onDownload?.invoke(WebDownload(url, name, CookieManager.getInstance().getCookie(url))) }
                }
                @JavascriptInterface fun problem(message: String) { main.post { toast(message) } }
                private fun toast(m: String) = android.widget.Toast.makeText(ctx, m.take(200), android.widget.Toast.LENGTH_LONG).show()
            }, "PandyBridge")
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) {
                    state?.canGoBack = v.canGoBack(); state?.currentUrl = u.orEmpty()
                }

                override fun onPageFinished(v: WebView, u: String?) {
                    if (u?.contains("makerworld.com") == true) v.evaluateJavascript(PRINT_BUTTON_JS, null)
                }

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    android.util.Log.d("PandyWeb", "navigate ${u.scheme}://${u.host}${u.path}")
                    if (u.scheme?.lowercase() !in SLICER_SCHEMES) return false
                    val file = u.getQueryParameter("file") ?: u.getQueryParameter("url")
                    if (file != null && onDownload != null) {
                        val name = Uri.parse(file).lastPathSegment?.substringAfterLast('/') ?: "model.3mf"
                        onDownload(WebDownload(file, name, CookieManager.getInstance().getCookie(file)))
                    }
                    return true // never leave for another app
                }
            }
            setDownloadListener { u, _, disposition, mime, _ ->
                val name = URLUtil.guessFileName(u, disposition, mime)
                android.util.Log.d("PandyWeb", "download $name (${u.take(40)}…)")
                if (u.startsWith("blob:")) {
                    // Made in the page: read it there and pass the bytes back.
                    evaluateJavascript("""(async()=>{const b=await (await fetch(${org.json.JSONObject.quote(u)})).blob();
                        const r=new FileReader();r.onload=()=>PandyBridge.blob(${org.json.JSONObject.quote(name)},r.result.split(',')[1]);r.readAsDataURL(b);})()""", null)
                } else onDownload?.invoke(WebDownload(u, name, CookieManager.getInstance().getCookie(u)))
            }
            state?.goBack = { goBack() }
            loadUrl(url)
            view = this
        }
    }, modifier, update = { view = it })
    if (state != null) BackHandler(enabled = state.canGoBack) { state.goBack() }
}
