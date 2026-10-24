package io.github.gccammelli.pandy

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun views(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun request(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String,String>()
    }
    private fun bridgeCall(bridge: Any,name: String,vararg args: Any) =
        bridge.javaClass.declaredMethods.single { it.name==name }.apply { isAccessible=true }.invoke(bridge,*args)
    private fun bridgeToken(bridge: Any) = bridge.javaClass.declaredFields.first { it.name.contains("bridgeToken") }.apply { isAccessible=true }.get(bridge) as String
    @Suppress("UNCHECKED_CAST")
    private fun pending(bridge: Any) = bridge.javaClass.declaredFields.first { it.name.contains("pendingPrints") }.apply { isAccessible=true }.get(bridge) as Set<String>

    @Test fun untrustedNativeDownloadUrlsAreRejectedEvenWithAValidBridgeToken() {
        val downloads=mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},WebState(),null) }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow=shadowOf(web); val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            for (url in listOf("http://cdn.bblmw.com/file.3mf","https://makerworld.com@evil.example/file.3mf",
                "https://evil.example/file.3mf","https://cdn.bblmw.com/file with space.3mf","https://cdn.bblmw.com\\evil.3mf","https:///file.3mf")) {
                bridgeCall(bridge,"nativeFile","model.3mf",url,token)
                bridgeCall(bridge,"file","model.3mf",url,token)
            }
            assertTrue(pending(bridge).isEmpty())
        }
        compose.runOnIdle { assertTrue(downloads.isEmpty()) }
    }

    @Test fun malformedAndUnsolicitedChunksCannotProduceAPrint() {
        val downloads=mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},WebState(),"X1C") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow=shadowOf(web); val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            bridgeCall(bridge,"blobChunk","unsolicited","m.3mf",0,1,"AQ==",token)
            bridgeCall(bridge,"file","m.3mf","https://cdn.bblmw.com/m.3mf",token)
            val id=pending(bridge).single()
            for ((index,total) in listOf(0 to 0,0 to 1367,-1 to 1,1 to 1)) bridgeCall(bridge,"blobChunk",id,"m.3mf",index,total,"AQ==",token)
            bridgeCall(bridge,"blobChunk",id,"m.3mf",0,1,"AQ==","wrong-token")
            bridgeCall(bridge,"blobChunk",id,"m.3mf",0,1,"A".repeat(70_001),token)
            bridgeCall(bridge,"blobChunk",id,"m.3mf",0,1,"A",token)
            assertEquals(setOf(id),pending(bridge))
            bridgeCall(bridge,"blobChunk",id,"m.3mf",0,1,"AQI=",token)
        }
        compose.runOnIdle { assertEquals(1,downloads.size); assertArrayEquals(byteArrayOf(1,2),downloads.single().bytes) }
    }

    @Test fun incompletePageTransfersExpireAndCannotBeResumedAfterTimeout() {
        val downloads=mutableListOf<WebDownload>()
        lateinit var bridge: Any; lateinit var token: String; lateinit var id: String
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},WebState(),"X1C") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single(); val shadow=shadowOf(web)
            bridge=shadow.getJavascriptInterface("PandyBridge"); token=bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            bridgeCall(bridge,"file","m.3mf","https://cdn.bblmw.com/m.3mf",token); id=pending(bridge).single()
            bridgeCall(bridge,"blobChunk",id,"m.3mf",0,2,"AQI=",token)
            shadow.webViewClient.doUpdateVisitedHistory(web,"https://makerworld.com/model/1#details",false)
            assertEquals(setOf(id),pending(bridge))
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5))
            assertTrue(pending(bridge).isEmpty())
            bridgeCall(bridge,"blobChunk",id,"m.3mf",1,2,"AwQ=",token)
        }
        compose.runOnIdle { assertTrue(downloads.isEmpty()) }
    }

    @Test fun watchOnlyModeHandsSlicerLinksToTheCompanionAppWithoutImporting() {
        val downloads=mutableListOf<WebDownload>(); val state=WebState().apply { canPrint=false }
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},state,"P1S") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single(); val shadow=shadowOf(web)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            val url="bambu-handy://open?file=https%3A%2F%2Fcdn.bblmw.com%2Fm.3mf"
            assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,request(url)))
            assertEquals(url,shadowOf(compose.activity).nextStartedActivity.dataString)
            assertTrue(downloads.isEmpty())
        }
    }

    @Test fun optionalPageStateAndDownloadHandlerDoNotEnableNativeImports() {
        compose.setContent { WebPage("https://makerworld.com",Modifier,null,null,null) }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single(); val shadow=shadowOf(web)
            val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web,"https://sub.makerworld.com/model/1")
            bridgeCall(bridge,"file","m.3mf","https://cdn.bblmw.com/m.3mf",token)
            assertTrue(pending(bridge).isEmpty())
            bridgeCall(bridge,"nativeFile","m.3mf","https://cdn.bblmw.com/m.3mf",token)
            bridgeCall(bridge,"blob","m.3mf","A",token)
            shadow.webViewClient.doUpdateVisitedHistory(web,null,false)
            shadow.webViewClient.onPageFinished(web,null)
        }
        compose.waitForIdle()
    }
    @Test fun navigationAndDownloadsStayInsideMakerWorldTrustBoundary() {
        val downloads = mutableListOf<WebDownload>()
        val state = WebState()
        compose.setContent { WebPage("https://makerworld.com", Modifier, { downloads += it }, state, "X1C") }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web)
            shadow.webChromeClient.onConsoleMessage(android.webkit.ConsoleMessage("pandy fixture diagnostic","test",1,android.webkit.ConsoleMessage.MessageLevel.LOG))
            assertFalse(shadow.webChromeClient.onConsoleMessage(android.webkit.ConsoleMessage("ordinary page message","test",2,android.webkit.ConsoleMessage.MessageLevel.LOG)))
            val client = shadow.webViewClient
            client.onPageFinished(web,"https://makerworld.com/en/models")
            client.doUpdateVisitedHistory(web,"https://makerworld.com/en/models",false)
            assertEquals("https://makerworld.com/en/models",state.currentUrl)
            assertFalse(client.shouldOverrideUrlLoading(web,request("https://makerworld.com/en/models/1")))
            assertFalse(client.shouldOverrideUrlLoading(web,request("https://cdn.example/frame",false)))
            assertTrue(client.shouldOverrideUrlLoading(web,request("file:///etc/passwd")))
            assertTrue(client.shouldOverrideUrlLoading(web,request("https://example.com")))
            assertTrue(client.shouldOverrideUrlLoading(web,request("bambustudioopen://open?file=https%3A%2F%2Fcdn.bblmw.com%2Fmodel.3mf")))
            assertEquals("https://cdn.bblmw.com/model.3mf",downloads.single().url)
            assertTrue(downloads.single().printSheet)
            client.shouldOverrideUrlLoading(web,request("bambustudioopen://open?file=http%3A%2F%2Fexample.com%2Fevil.3mf"))
            assertEquals(1,downloads.size)
            client.shouldOverrideUrlLoading(web,request("bambustudioopen://open"))
            assertTrue(shadow.lastEvaluatedJavascript.contains("__pandyPrint"))
            shadow.downloadListener.onDownloadStart("https://cdn.bblmw.com/download.3mf","","attachment; filename=\"download.3mf\"","application/octet-stream",3)
            assertEquals(2,downloads.size)
            shadow.downloadListener.onDownloadStart("https://evil.example/model.3mf","","","application/octet-stream",3)
            assertEquals(2,downloads.size)
            shadow.downloadListener.onDownloadStart("blob:https://makerworld.com/test","","","application/octet-stream",3)
            assertTrue(shadow.lastEvaluatedJavascript.contains("FileReader"))
        }
    }

    @Test fun javascriptBridgeRejectsUnknownTokensAndUntrustedPages() {
        val downloads = mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com", Modifier, { downloads += it }, WebState(), "X1C") }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web); val bridge = shadow.getJavascriptInterface("PandyBridge")
            val blob = bridge.javaClass.getDeclaredMethod("blob",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/en/models")
            blob.invoke(bridge,"test.3mf","AQID","invalid-token")
            shadow.webViewClient.onPageFinished(web,"https://evil.example")
            blob.invoke(bridge,"test.3mf","AQID","invalid-token")
            assertTrue(downloads.isEmpty())
            val tokenField = bridge.javaClass.declaredFields.first { it.name.contains("bridgeToken") }.apply { isAccessible=true }
            val token = tokenField.get(bridge) as String
            val native = bridge.javaClass.getDeclaredMethod("nativeFile",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }
            native.invoke(bridge,"test.3mf","https://cdn.bblmw.com/test.3mf",token)
            assertTrue(downloads.isEmpty())
            shadow.webViewClient.doUpdateVisitedHistory(web,"https://makerworld.com/models",false)
            native.invoke(bridge,"bad.3mf","https://attacker.example/test.3mf",token)
            assertTrue(downloads.isEmpty())
            blob.invoke(bridge,"test.3mf","AQID",token)
            native.invoke(bridge,"native.3mf","https://cdn.bblmw.com/native.3mf",token)
            val error=bridge.javaClass.getDeclaredMethod("problem",String::class.java,String::class.java).apply { isAccessible=true }
            error.invoke(bridge,"Test download failed",token)
        }
        compose.runOnIdle {
            assertEquals(2,downloads.size)
            assertArrayEquals(byteArrayOf(1,2,3),downloads.first().bytes)
            assertEquals("https://cdn.bblmw.com/native.3mf",downloads.last().url)
        }
    }
    @Test fun chunkedPrintTransferCompletesOnceAndCannotSurvivePageNavigation() {
        val downloads=mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},WebState(),"X1C") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow=shadowOf(web)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            val bridge=shadow.getJavascriptInterface("PandyBridge")
            val token=bridge.javaClass.declaredFields.first { it.name.contains("bridgeToken") }.apply { isAccessible=true }.get(bridge) as String
            @Suppress("UNCHECKED_CAST")
            val pending=bridge.javaClass.declaredFields.first { it.name.contains("pendingPrints") }.apply { isAccessible=true }.get(bridge) as Set<String>
            val file=bridge.javaClass.getDeclaredMethod("file",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }
            val chunk=bridge.javaClass.getDeclaredMethod("blobChunk",String::class.java,String::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java,String::class.java).apply { isAccessible=true }
            file.invoke(bridge,"test.3mf","https://cdn.bblmw.com/test.3mf",token)
            val id=pending.single()
            file.invoke(bridge,"other.3mf","https://cdn.bblmw.com/other.3mf",token)
            assertEquals(1,pending.size)
            chunk.invoke(bridge,id,"test.3mf",0,2,"AQI=",token)
            assertTrue(downloads.isEmpty())
            chunk.invoke(bridge,id,"test.3mf",1,2,"AwQ=",token)
            assertTrue(pending.isEmpty())
            file.invoke(bridge,"old.3mf","https://cdn.bblmw.com/old.3mf",token)
            val old=pending.single()
            chunk.invoke(bridge,old,"old.3mf",0,2,"BQY=",token)
            shadow.webViewClient.doUpdateVisitedHistory(web,"https://makerworld.com/model/2",false)
            assertTrue(pending.isEmpty())
            chunk.invoke(bridge,old,"old.3mf",1,2,"Bwg=",token)
        }
        compose.runOnIdle {
            assertEquals(1,downloads.size)
            assertArrayEquals(byteArrayOf(1,2,3,4),downloads.single().bytes)
            assertTrue(downloads.single().printSheet)
        }
    }

}
