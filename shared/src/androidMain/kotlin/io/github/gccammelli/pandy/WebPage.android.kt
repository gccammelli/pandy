package io.github.gccammelli.pandy

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((url: String, name: String, cookies: String?) -> Unit)?, state: WebState?) {
    var view: WebView? = null
    AndroidView({ ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // MakerWorld's sign-in keeps its session in cookies; keep them across launches.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) {
                    state?.canGoBack = v.canGoBack(); state?.currentUrl = u.orEmpty()
                }
            }
            setDownloadListener { u, _, disposition, mime, _ ->
                onDownload?.invoke(u, URLUtil.guessFileName(u, disposition, mime), CookieManager.getInstance().getCookie(u))
            }
            state?.goBack = { goBack() }
            loadUrl(url)
            view = this
        }
    }, modifier, update = { view = it })
    if (state != null) BackHandler(enabled = state.canGoBack) { state.goBack() }
}
