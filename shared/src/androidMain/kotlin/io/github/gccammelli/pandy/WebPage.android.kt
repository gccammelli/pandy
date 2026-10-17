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

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?, printer: String?) {
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
                    if (u?.contains("makerworld.com") == true) {
                        v.evaluateJavascript("window.__pandyPrinter = ${org.json.JSONObject.quote(printer.orEmpty())};", null)
                        v.evaluateJavascript(PRINT_BUTTON_JS, null)
                    }
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
