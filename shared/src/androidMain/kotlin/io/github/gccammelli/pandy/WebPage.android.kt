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
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?) {
    var view: WebView? = null
    AndroidView({ ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // MakerWorld's sign-in keeps its session in cookies; keep them across launches.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
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

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
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
