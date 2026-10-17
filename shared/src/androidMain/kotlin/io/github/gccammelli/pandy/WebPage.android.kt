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
 * dropdown's "Download 3MF" does for the chosen profile, and the download comes to Pandy's print sheet. The dropdown
 * stays as it is. Re-applied as the page changes (it's a single-page app).
 */
private const val PRINT_BUTTON_JS = """
(function(){
  if (window.__pandy) return; window.__pandy = true;
  const label = /open in bambu (handy|studio)/i;
  function textOf(el){ return (el.innerText || el.textContent || '').trim(); }
  function find(re){ return [...document.querySelectorAll('button, a, div, span, li')].filter(e => re.test(textOf(e)) && e.children.length < 4); }
  function download3mf(btn){
    let item = find(/^download 3mf$/i)[0];
    if (item) { item.click(); return; }
    // The menu isn't open: open it with the arrow next to the button, then pick "Download 3MF".
    const group = btn.closest('div') && btn.closest('div').parentElement;
    const arrow = group && [...group.querySelectorAll('button, div, span')].find(e => e !== btn && !btn.contains(e) && !e.contains(btn) && textOf(e) === '');
    if (arrow) arrow.click();
    setTimeout(() => { const i = find(/^download 3mf$/i)[0]; if (i) i.click(); else console.log('pandy: no Download 3MF item'); }, 400);
  }
  function apply(){
    for (const el of find(label)) {
      const btn = el.closest('button, a, [role=button]') || el;
      if (btn.dataset.pandy) continue;
      btn.dataset.pandy = '1';
      const leaf = [...btn.querySelectorAll('*')].reverse().find(e => label.test(textOf(e)) && e.children.length === 0) || btn;
      leaf.textContent = 'Print';
      btn.style.setProperty('background', '#AE006D', 'important');
      btn.style.setProperty('border-color', '#AE006D', 'important');
      btn.style.setProperty('color', '#fff', 'important');
      btn.addEventListener('click', e => { e.preventDefault(); e.stopImmediatePropagation(); console.log('pandy: print tapped'); download3mf(btn); }, true);
      // The split button's arrow keeps MakerWorld's dropdown, in a darker magenta.
      const sib = btn.nextElementSibling; if (sib) sib.style.setProperty('background', '#7E004F', 'important');
    }
  }
  apply();
  new MutationObserver(apply).observe(document.documentElement, {childList: true, subtree: true});
})();
"""

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?) {
    var view: WebView? = null
    AndroidView({ ctx ->
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
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    main.post { onDownload?.invoke(WebDownload(null, name, null, bytes)) }
                }
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
