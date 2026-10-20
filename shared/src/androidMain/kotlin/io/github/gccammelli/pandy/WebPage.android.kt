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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")
private const val BRIDGE_CHUNK_SIZE = 49_152
private const val MAX_PRINT_BYTES = 64 * 1024 * 1024
private const val MAX_BRIDGE_BASE64 = 89_478_488
private const val MAX_PRINT_CHUNKS = 1_366
private const val PRINT_TRANSFER_TIMEOUT_MS = 5 * 60 * 1000L

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?, printer: String?) {
    val currentPrinter = rememberUpdatedState(printer)
    var view: WebView? = null
    AndroidView({ ctx ->
        // Debug builds: the page can be inspected from a computer (chrome://inspect), to keep up with MakerWorld's changes.
        val isDebuggable = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebuggable) WebView.setWebContentsDebuggingEnabled(true)
        fun debugLog(message: String) { if (isDebuggable) android.util.Log.d("PandyWeb", message) }
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // MakerWorld's sign-in keeps its session in cookies; keep them across launches.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.message().startsWith("pandy")) debugLog(m.message())
                    return super.onConsoleMessage(m)
                }
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val web = this
            val chunks = mutableMapOf<String, ChunkCollector>()
            val pendingPrints = mutableSetOf<String>()
            val makerWorld = java.util.concurrent.atomic.AtomicBoolean(false)
            val bridgeToken = java.util.UUID.randomUUID().toString()
            var transferPage: String? = null
            fun makerWorldPage() = makerWorld.get()
            fun clearTransfers() {
                synchronized(chunks) { chunks.clear() }
                synchronized(pendingPrints) { pendingPrints.clear() }
            }
            fun pageChanged(u: String?) {
                val page = u?.substringBefore('#')
                if (page != transferPage) {
                    clearTransfers()
                    transferPage = page
                }
            }
            fun validToken(token: String) = token == bridgeToken
            fun toast(message: String) = android.widget.Toast.makeText(ctx, message.take(200), android.widget.Toast.LENGTH_LONG).show()
            fun trustedFile(url: String): Boolean {
                if (!url.startsWith("https://") || url.any { it == '\\' || it == '@' || it.isWhitespace() }) return false
                return Uri.parse(url).host?.lowercase()?.let { it.endsWith(".bblmw.com") || it == "makerworld.com" || it.endsWith(".makerworld.com") } == true
            }
            fun deliver(name: String, bytes: ByteArray?, printSheet: Boolean = false) = main.post {
                if (bytes != null) onDownload?.invoke(WebDownload(null, name, null, bytes, printSheet)) else
                    android.widget.Toast.makeText(ctx, "Couldn't read that download", android.widget.Toast.LENGTH_LONG).show()
            }
            fun fetchInPage(name: String, url: String): Boolean {
                val n = org.json.JSONObject.quote(name)
                val u = org.json.JSONObject.quote(url)
                val id = org.json.JSONObject.quote("print-" + System.nanoTime().toString(36))
                synchronized(pendingPrints) {
                    if (pendingPrints.isNotEmpty()) return false
                    pendingPrints += id.trim('"')
                }
                main.postDelayed({
                    if (synchronized(pendingPrints) { pendingPrints.remove(id.trim('"')) }) synchronized(chunks) { chunks.remove(id.trim('"')) }
                }, PRINT_TRANSFER_TIMEOUT_MS)
                web.post {
                    web.evaluateJavascript("""(async()=>{
                        try {
                            const r = await fetch($u);
                            if (!r.ok) { PandyBridge.nativeFile($n, $u, window.__pandyBridgeToken); return; }
                            const bytes = new Uint8Array(await r.arrayBuffer());
                            if (bytes.length > $MAX_PRINT_BYTES) { PandyBridge.problem('The model is too large to import.', window.__pandyBridgeToken); return; }
                            const size = $BRIDGE_CHUNK_SIZE, total = Math.max(1, Math.ceil(bytes.length / size));
                            for (let i = 0; i < total; i++) {
                                const from = i * size, to = Math.min(bytes.length, from + size);
                                let binary = '';
                                for (let j = from; j < to; j++) binary += String.fromCharCode(bytes[j]);
                                PandyBridge.blobChunk($id, $n, i, total, btoa(binary), window.__pandyBridgeToken);
                            }
                    } catch (e) { PandyBridge.nativeFile($n, $u, window.__pandyBridgeToken); }
                    })()""", null)
                }
                return true
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun blob(name: String, base64: String, token: String) {
                    if (!validToken(token) || !makerWorldPage() || base64.length > MAX_BRIDGE_BASE64) return
                    val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull()?.takeIf { it.size <= MAX_PRINT_BYTES }
                    deliver(name, bytes)
                }
                @JavascriptInterface fun blobChunk(id: String, name: String, index: Int, total: Int, base64: String, token: String) {
                    if (!validToken(token) || !makerWorldPage() || total !in 1..MAX_PRINT_CHUNKS || index !in 0 until total || base64.length > 70_000 || synchronized(pendingPrints) { id !in pendingPrints }) return
                    val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull() ?: return
                    val complete = synchronized(chunks) {
                        val transfer = chunks[id]?.takeIf { it.totalChunks == total } ?: ChunkCollector(total, MAX_PRINT_BYTES).also { chunks[id] = it }
                        transfer.add(index, bytes)
                    }
                    if (complete != null) {
                        synchronized(chunks) { chunks.remove(id) }
                        synchronized(pendingPrints) { pendingPrints.remove(id) }
                        deliver(name, complete, true)
                    } else if (synchronized(chunks) { chunks[id]?.rejected == true }) {
                        synchronized(chunks) { chunks.remove(id) }
                        synchronized(pendingPrints) { pendingPrints.remove(id) }
                        main.post { toast("The model is too large to import") }
                    }
                }
                /** The Print button: fetch the signed link in the authenticated WebView, then pass its bytes to Pandy. */
                @JavascriptInterface fun file(name: String, url: String, token: String) {
                    if (validToken(token) && onDownload != null && makerWorldPage() && url.startsWith("https://") && trustedFile(url) && !fetchInPage(name, url)) main.post { toast("A print is already being prepared") }
                }
                /** Fallback for a CDN that does not allow a page-context fetch. */
                @JavascriptInterface fun nativeFile(name: String, url: String, token: String) {
                    if (!validToken(token) || !makerWorldPage() || !url.startsWith("https://") || !trustedFile(url)) return
                    clearTransfers()
                    main.post { onDownload?.invoke(WebDownload(url, name, CookieManager.getInstance().getCookie(url), printSheet = true)) }
                }
                @JavascriptInterface fun problem(message: String, token: String) {
                    if (validToken(token) && makerWorldPage()) {
                        clearTransfers()
                        main.post { toast(message) }
                    }
                }
            }, "PandyBridge")
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) {
                    val host = u?.let { Uri.parse(it).host }
                    makerWorld.set(host?.let { it == "makerworld.com" || it.endsWith(".makerworld.com") } == true)
                    pageChanged(u)
                    state?.canGoBack = v.canGoBack(); state?.currentUrl = u.orEmpty()
                }

                override fun onPageFinished(v: WebView, u: String?) {
                    val host = u?.let { Uri.parse(it).host }
                    makerWorld.set(host?.let { it == "makerworld.com" || it.endsWith(".makerworld.com") } == true)
                    pageChanged(u)
                    if (makerWorld.get()) {
                        v.evaluateJavascript("window.__pandyBridgeToken = ${org.json.JSONObject.quote(bridgeToken)}; window.__pandyPrinter = ${org.json.JSONObject.quote(currentPrinter.value.orEmpty())};", null)
                        v.evaluateJavascript(canPrintJs(state?.canPrint != false), null)
                        v.evaluateJavascript(PRINT_BUTTON_JS, null)
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    debugLog("navigate ${u.scheme}://${u.host}${u.path}")
                    if (u.scheme?.lowercase() !in SLICER_SCHEMES) {
                        if (!r.isForMainFrame || inAppPage(u.scheme, u.host)) return false
                        // Other web pages open in the browser; other schemes (intent:, file:…) don't open at all.
                        if (u.scheme?.lowercase() in setOf("http", "https")) runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
                        return true
                    }
                    if (state?.canPrint == false) {
                        // Pandy can't print right now: let MakerWorld's link open Bambu Handy, as it would in a browser.
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }
                            .onFailure { main.post { toast("Bambu Handy isn't installed") } }
                        return true
                    }
                    val file = u.getQueryParameter("file") ?: u.getQueryParameter("url")
                    val safeFile = file?.takeIf { it.startsWith("https://") && trustedFile(it) }
                    if (file != null) {
                        if (safeFile == null) {
                            main.post { toast("MakerWorld provided an invalid file link") }
                            return true
                        }
                        val name = Uri.parse(safeFile).lastPathSegment?.substringAfterLast('/') ?: "model.3mf"
                        onDownload?.invoke(WebDownload(safeFile, name, CookieManager.getInstance().getCookie(safeFile), printSheet = true))
                    } else {
                        // MakerWorld's Handy menu sometimes emits only this deep link, without the file URL.
                        v.evaluateJavascript("window.__pandyPrint && window.__pandyPrint();", null)
                    }
                    return true // never leave for another app
                }
            }
            setDownloadListener { u, _, disposition, mime, _ ->
                // Some MakerWorld responses append the RFC 5987 filename parameter to the guessed name.
                val name = URLUtil.guessFileName(u, disposition, mime).substringBefore(';')
                debugLog("download $name (${u.take(40)}…)")
                if (u.startsWith("blob:")) {
                    // Made in the page: read it there and pass the bytes back.
                    evaluateJavascript("""(async()=>{const b=await (await fetch(${org.json.JSONObject.quote(u)})).blob();
                        if (b.size > $MAX_PRINT_BYTES) { PandyBridge.problem('The download is too large to save.',window.__pandyBridgeToken); return; }
                        const r=new FileReader();r.onload=()=>PandyBridge.blob(${org.json.JSONObject.quote(name)},r.result.split(',')[1],window.__pandyBridgeToken);r.readAsDataURL(b);})()""", null)
                } else if (trustedFile(u)) onDownload?.invoke(WebDownload(u, name, CookieManager.getInstance().getCookie(u)))
                else toast("Pandy only opens downloads from MakerWorld")
            }
            state?.goBack = { goBack() }
            loadUrl(url)
            view = this
        }
    }, modifier, update = {
        view = it
        if (it.url?.let { u -> Uri.parse(u).host?.let { h -> h == "makerworld.com" || h.endsWith(".makerworld.com") } == true } == true) {
            it.evaluateJavascript("window.__pandyPrinter = ${org.json.JSONObject.quote(currentPrinter.value.orEmpty())};", null)
            it.evaluateJavascript(canPrintJs(state?.canPrint != false), null)
        }
    })
    if (state != null) BackHandler(enabled = state.overlayBack != null || state.canGoBack) {
        state.overlayBack?.invoke() ?: state.goBack()
    }
}
