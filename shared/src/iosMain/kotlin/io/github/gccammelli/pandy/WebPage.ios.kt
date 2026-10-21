@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.github.gccammelli.pandy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUUID
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.native.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")
private const val BRIDGE_CHUNK_SIZE = 49_152
private const val MAX_PRINT_BYTES = 64 * 1024 * 1024
private const val MAX_BRIDGE_BASE64 = 89_478_488
private const val MAX_PRINT_CHUNKS = 1_366
private const val PRINT_TRANSFER_TIMEOUT_MS = 5 * 60 * 1000L

/** The Android page's PandyBridge (file, problem), as WebKit message posts. */
private const val BRIDGE_JS = """
window.PandyBridge = {
  file: (name, url, token) => window.webkit.messageHandlers.pandy.postMessage({ t: 'file', name: name, url: url, token: token }),
  nativeFile: (name, url, token) => window.webkit.messageHandlers.pandy.postMessage({ t: 'nativeFile', name: name, url: url, token: token }),
  problem: (m, token) => window.webkit.messageHandlers.pandy.postMessage({ t: 'problem', m: String(m), token: token }),
  blob: (name, b64, token) => window.webkit.messageHandlers.pandy.postMessage({ t: 'blob', name: name, b64: b64, token: token }),
  blobChunk: (id, name, i, total, b64, token) => window.webkit.messageHandlers.pandy.postMessage({ t: 'blobChunk', id: id, name: name, i: i, total: total, b64: b64, token: token })
};
"""

private fun makerWorldOnly(script: String) = """
(function(){const h=location.hostname.toLowerCase();if(h==='makerworld.com'||h.endsWith('.makerworld.com')){$script}})();
"""

/**
 * MakerWorld in a WKWebView: the same Print button script as on Android, and downloads (an attachment, or an "open in
 * Bambu Studio" link) handed to [onDownload] with the page's cookies instead of leaving the app.
 */
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?, printer: String?) {
    val toast = LocalPlatform.current::toast
    val currentPrinter = rememberUpdatedState(printer)
    val bridgeToken = remember(url) { NSUUID.UUID().UUIDString }
    fun printerJs() = "window.__pandyBridgeToken = ${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(bridgeToken))}; window.__pandyPrinter = ${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(currentPrinter.value.orEmpty()))};"
    // WebKit holds delegates weakly: they live as long as this composable.
    val handlers = remember(url) { Handlers(onDownload, state, toast, currentPrinter.value, bridgeToken) }
    UIKitView(
        factory = {
            val content = WKUserContentController()
            content.addUserScript(WKUserScript(makerWorldOnly(BRIDGE_JS + printerJs()), WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true))
            content.addUserScript(WKUserScript(makerWorldOnly(PRINT_BUTTON_JS), WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd, true))
            content.addScriptMessageHandler(handlers.messages, "pandy")
            val config = WKWebViewConfiguration().apply { userContentController = content; websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
            WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = config).apply {
                handlers.webView = WeakReference(this)
                navigationDelegate = handlers.navigation
                allowsBackForwardNavigationGestures = true // iOS has no back button: swipe from the edge
                state?.goBack = { goBack() }
                loadRequest(NSURLRequest(uRL = NSURL.URLWithString(url)!!))
            }
        },
        modifier = modifier,
        update = { view ->
            handlers.onDownload = onDownload
            handlers.state = state
            handlers.printer = currentPrinter.value
            val host = view.URL?.host?.lowercase()
            if (host == "makerworld.com" || host?.endsWith(".makerworld.com") == true) {
                view.evaluateJavaScript(printerJs(), null)
                view.evaluateJavaScript(canPrintJs(state?.canPrint != false), null)
            }
        },
    )
}

private class Handlers(var onDownload: ((WebDownload) -> Unit)?, var state: WebState?, val toast: (String) -> Unit, var printer: String?, private val bridgeToken: String) {
    var webView: WeakReference<WKWebView>? = null
    private val chunks = mutableMapOf<String, ChunkCollector>()
    private val pendingPrints = mutableSetOf<String>()
    private val transferTimeouts = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var transferPage: String? = null

    private fun clearTransfers() {
        chunks.clear()
        pendingPrints.clear()
    }
    private fun pageChanged(url: String?) {
        val page = url?.substringBefore('#')
        if (page != transferPage) {
            clearTransfers()
            transferPage = page
        }
    }

    private fun makerWorldPage() = webView?.get()?.URL?.host?.let { it == "makerworld.com" || it.endsWith(".makerworld.com") } == true
    private fun trustedFile(url: String): Boolean {
        if (!url.startsWith("https://") || url.any { it == '\\' || it == '@' || it.isWhitespace() }) return false
        return NSURL.URLWithString(url)?.host?.lowercase()?.let {
            it == "makerworld.com" || it.endsWith(".makerworld.com") || it == "makerworld.bblmw.com" || it.endsWith(".bblmw.com")
        } == true
    }

    /** The page's cookies for [url] (MakerWorld's sign-in), as a Cookie header. */
    fun cookies(url: String, done: (String?) -> Unit) {
        val host = NSURL.URLWithString(url)?.host ?: return done(null)
        WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { all ->
            val list = all.orEmpty().filterIsInstance<NSHTTPCookie>().filter { c -> host == c.domain.trimStart('.') || host.endsWith(c.domain.let { if (it.startsWith(".")) it else ".$it" }) }
            done(list.joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null })
        }
    }

    fun hand(url: String, name: String, printSheet: Boolean = false) = cookies(url) { c -> onDownload?.invoke(WebDownload(url, name, c, printSheet = printSheet)) }

    fun fetchInPage(name: String, url: String) {
        if (pendingPrints.isNotEmpty()) {
            toast("A print is already being prepared")
            return
        }
        val view = webView?.get() ?: return hand(url, name, printSheet = true)
        val n = json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(name))
        val u = json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(url))
        val id = "print-" + kotlin.time.Clock.System.now().toEpochMilliseconds().toString(36) + "-" + name.hashCode().toString(36)
        pendingPrints += id
        transferTimeouts.launch {
            delay(PRINT_TRANSFER_TIMEOUT_MS)
            if (pendingPrints.remove(id)) chunks.remove(id)
        }
        val encodedId = json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(id))
        val encodedToken = json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(bridgeToken))
        view.evaluateJavaScript("""(async()=>{
            try {
                const r = await fetch($u);
                if (!r.ok) { PandyBridge.nativeFile($n, $u, $encodedToken); return; }
                const bytes = new Uint8Array(await r.arrayBuffer());
            if (bytes.length > $MAX_PRINT_BYTES) { PandyBridge.problem('The model is too large to import.', $encodedToken); return; }
            const size = $BRIDGE_CHUNK_SIZE, total = Math.max(1, Math.ceil(bytes.length / size));
            for (let i = 0; i < total; i++) {
                    const from = i * size, to = Math.min(bytes.length, from + size);
                    let binary = '';
                    for (let j = from; j < to; j++) binary += String.fromCharCode(bytes[j]);
                PandyBridge.blobChunk($encodedId, $n, i, total, btoa(binary), $encodedToken);
                }
            } catch (e) { PandyBridge.nativeFile($n, $u, $encodedToken); }
        })()""", null)
    }

    val messages: WKScriptMessageHandlerProtocol = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val m = didReceiveScriptMessage.body as? Map<*, *> ?: return
            if (m["token"] as? String != bridgeToken) return
            when (m["t"]) {
                "file" -> (m["url"] as? String)?.takeIf { makerWorldPage() && it.startsWith("https://") && trustedFile(it) }?.let { fetchInPage(m["name"] as? String ?: "model.3mf", it) }
                "nativeFile" -> (m["url"] as? String)?.takeIf { makerWorldPage() && it.startsWith("https://") && trustedFile(it) }?.let {
                    clearTransfers()
                    hand(it, m["name"] as? String ?: "model.3mf", printSheet = true)
                }
                "problem" -> if (makerWorldPage()) {
                    clearTransfers()
                    toast((m["m"] as? String).orEmpty().take(200))
                }
                "blob" -> (m["b64"] as? String)?.let { b64 ->
                    if (!makerWorldPage() || b64.length > MAX_BRIDGE_BASE64) return
                    runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull()?.takeIf { it.size <= MAX_PRINT_BYTES }
                        ?.let { onDownload?.invoke(WebDownload(null, m["name"] as? String ?: "model.3mf", null, it)) } ?: toast("Couldn't read that download")
                }
                "blobChunk" -> {
                    val id = m["id"] as? String ?: return
                    val name = m["name"] as? String ?: "model.3mf"
                    val index = (m["i"] as? Number)?.toInt() ?: return
                    val total = (m["total"] as? Number)?.toInt() ?: return
                    val b64 = m["b64"] as? String ?: return
                    if (!makerWorldPage() || total !in 1..MAX_PRINT_CHUNKS || index !in 0 until total || b64.length > 70_000 || id !in pendingPrints) return
                    val bytes = runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull() ?: return
                    val transfer = chunks[id]?.takeIf { it.totalChunks == total } ?: ChunkCollector(total, MAX_PRINT_BYTES).also { chunks[id] = it }
                    val complete = transfer.add(index, bytes)
                    if (complete != null) {
                        chunks.remove(id)
                        pendingPrints.remove(id)
                        onDownload?.invoke(WebDownload(null, name, null, complete, printSheet = true))
                    } else if (transfer.rejected) {
                        chunks.remove(id)
                        pendingPrints.remove(id)
                        toast("The model is too large to import")
                    }
                }
            }
        }
    }

    val navigation: WKNavigationDelegateProtocol = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val u = decidePolicyForNavigationAction.request.URL
            val mainFrame = decidePolicyForNavigationAction.targetFrame?.isMainFrame() ?: true // no frame: a new window
            if (mainFrame && u != null && u.scheme?.lowercase() !in SLICER_SCHEMES && !inAppPage(u.scheme, u.host)) {
                // Other web pages open in the browser; other schemes don't open at all.
                if (u.scheme?.lowercase() in setOf("http", "https")) platform.UIKit.UIApplication.sharedApplication.openURL(u, emptyMap<Any?, Any>(), null)
                return decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
            if (decidePolicyForNavigationAction.targetFrame?.isMainFrame() == true && u != null && u.scheme?.lowercase() !in SLICER_SCHEMES) pageChanged(u.absoluteString)
            if (u?.scheme?.lowercase() in SLICER_SCHEMES && state?.canPrint == false) {
                // Pandy can't print right now: let MakerWorld's link open Bambu Handy.
                platform.UIKit.UIApplication.sharedApplication.openURL(u!!, emptyMap<Any?, Any>(), null)
                return decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
            if (u?.scheme?.lowercase() in SLICER_SCHEMES) {
                val items = NSURLComponents(uRL = u!!, resolvingAgainstBaseURL = false).queryItems.orEmpty().filterIsInstance<NSURLQueryItem>()
                val f = (items.firstOrNull { it.name == "file" } ?: items.firstOrNull { it.name == "url" })?.value
                if (f != null) {
                    if (!f.startsWith("https://") || !trustedFile(f)) {
                        toast("MakerWorld provided an invalid file link")
                    } else {
                        hand(f, NSURL.URLWithString(f)?.lastPathComponent ?: "model.3mf", printSheet = true)
                    }
                } else {
                    // MakerWorld's Handy menu sometimes emits only this deep link, without the file URL.
                    webView.evaluateJavaScript("window.__pandyPrint && window.__pandyPrint();", null)
                }
                return decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel) // never leave for another app
            }
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
            val r = decidePolicyForNavigationResponse.response
            val attachment = (r as? NSHTTPURLResponse)?.allHeaderFields?.entries?.any { (k, v) -> (k as? String).equals("Content-Disposition", true) && (v as? String)?.contains("attachment", true) == true } == true
            val url = r.URL?.absoluteString
            if (url != null && makerWorldPage() && trustedFile(url) && (attachment || !decidePolicyForNavigationResponse.canShowMIMEType)) {
                hand(url, r.suggestedFilename ?: "model.3mf")
                return decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            }
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            pageChanged(webView.URL?.absoluteString)
            if (makerWorldPage()) {
                webView.evaluateJavaScript("window.__pandyPrinter = ${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(printer.orEmpty()))};", null)
                webView.evaluateJavaScript(canPrintJs(state?.canPrint != false), null)
                webView.evaluateJavaScript(makerWorldOnly(PRINT_BUTTON_JS), null)
            }
            state?.canGoBack = webView.canGoBack; state?.currentUrl = webView.URL?.absoluteString.orEmpty()
        }
    }
}
