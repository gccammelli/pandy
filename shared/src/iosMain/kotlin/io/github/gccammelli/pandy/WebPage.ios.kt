@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.gccammelli.pandy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Links MakerWorld uses to hand a model to a slicer app; the model's address is in their "file" parameter. */
private val SLICER_SCHEMES = setOf("bambustudioopen", "bambustudio", "orcaslicer", "bambu-handy", "bambuhandy")

/** The Android page's PandyBridge (file, problem), as WebKit message posts. */
private const val BRIDGE_JS = """
window.PandyBridge = {
  file: (name, url) => window.webkit.messageHandlers.pandy.postMessage({ t: 'file', name: name, url: url }),
  problem: m => window.webkit.messageHandlers.pandy.postMessage({ t: 'problem', m: String(m) }),
  blob: (name, b64) => window.webkit.messageHandlers.pandy.postMessage({ t: 'blob', name: name, b64: b64 })
};
"""

/**
 * MakerWorld in a WKWebView: the same Print button script as on Android, and downloads (an attachment, or an "open in
 * Bambu Studio" link) handed to [onDownload] with the page's cookies instead of leaving the app.
 */
@Composable
actual fun WebPage(url: String, modifier: Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState?, printer: String?) {
    val toast = LocalPlatform.current::toast
    // WebKit holds delegates weakly: they live as long as this composable.
    val handlers = remember(url) { Handlers(onDownload, state, toast) }
    UIKitView(
        factory = {
            val content = WKUserContentController()
            val printerJs = "window.__pandyPrinter = ${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(printer.orEmpty()))};"
            content.addUserScript(WKUserScript(BRIDGE_JS + printerJs, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true))
            content.addUserScript(WKUserScript(PRINT_BUTTON_JS, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd, true))
            content.addScriptMessageHandler(handlers.messages, "pandy")
            val config = WKWebViewConfiguration().apply { userContentController = content; websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
            WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = config).apply {
                navigationDelegate = handlers.navigation
                allowsBackForwardNavigationGestures = true // iOS has no back button: swipe from the edge
                state?.goBack = { goBack() }
                loadRequest(NSURLRequest(uRL = NSURL.URLWithString(url)!!))
            }
        },
        modifier = modifier,
    )
}

private class Handlers(val onDownload: ((WebDownload) -> Unit)?, val state: WebState?, val toast: (String) -> Unit) {
    /** The page's cookies for [url] (MakerWorld's sign-in), as a Cookie header. */
    fun cookies(url: String, done: (String?) -> Unit) {
        val host = NSURL.URLWithString(url)?.host ?: return done(null)
        WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { all ->
            val list = all.orEmpty().filterIsInstance<NSHTTPCookie>().filter { c -> host == c.domain.trimStart('.') || host.endsWith(c.domain.let { if (it.startsWith(".")) it else ".$it" }) }
            done(list.joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null })
        }
    }

    fun hand(url: String, name: String) = cookies(url) { c -> onDownload?.invoke(WebDownload(url, name, c)) }

    val messages: WKScriptMessageHandlerProtocol = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val m = didReceiveScriptMessage.body as? Map<*, *> ?: return
            when (m["t"]) {
                "file" -> (m["url"] as? String)?.takeIf { it.startsWith("https://") }?.let { hand(it, m["name"] as? String ?: "model.3mf") }
                "problem" -> toast((m["m"] as? String).orEmpty().take(200))
                "blob" -> (m["b64"] as? String)?.let { b64 ->
                    runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull()
                        ?.let { onDownload?.invoke(WebDownload(null, m["name"] as? String ?: "model.3mf", null, it)) } ?: toast("Couldn't read that download")
                }
            }
        }
    }

    val navigation: WKNavigationDelegateProtocol = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val u = decidePolicyForNavigationAction.request.URL
            if (u?.scheme?.lowercase() in SLICER_SCHEMES) {
                val items = NSURLComponents(uRL = u!!, resolvingAgainstBaseURL = false).queryItems.orEmpty().filterIsInstance<NSURLQueryItem>()
                (items.firstOrNull { it.name == "file" } ?: items.firstOrNull { it.name == "url" })?.value?.let { f ->
                    hand(f, NSURL.URLWithString(f)?.lastPathComponent ?: "model.3mf")
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
            if (url != null && (attachment || !decidePolicyForNavigationResponse.canShowMIMEType)) {
                hand(url, r.suggestedFilename ?: "model.3mf")
                return decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            }
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            state?.canGoBack = webView.canGoBack; state?.currentUrl = webView.URL?.absoluteString.orEmpty()
        }
    }
}
