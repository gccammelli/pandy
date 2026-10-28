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

    @Test fun anOversizedDecodedPrintTransferIsDiscardedAndTheNextImportStillWorks() {
        val downloads = mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com", Modifier, { downloads += it }, WebState(), "X1C") }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web)
            val bridge = shadow.getJavascriptInterface("PandyBridge")
            val token = bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web, "https://makerworld.com/model/1")
            bridgeCall(bridge, "file", "large.3mf", "https://cdn.bblmw.com/large.3mf", token)
            val id = pending(bridge).single()
            // Valid individual chunks must still respect the aggregate 64 MiB limit.
            val chunk = "A".repeat(65_536) // 49,152 decoded bytes
            repeat(1_366) { index -> bridgeCall(bridge, "blobChunk", id, "large.3mf", index, 1_366, chunk, token) }
            assertTrue(pending(bridge).isEmpty())
        }
        compose.runOnIdle {
            assertTrue(downloads.isEmpty())
            assertEquals("The model is too large to import", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            val bridge = shadowOf(views(compose.activity.window.decorView).filterIsInstance<WebView>().single()).getJavascriptInterface("PandyBridge")
            val token = bridgeToken(bridge)
            bridgeCall(bridge, "file", "small.3mf", "https://cdn.bblmw.com/small.3mf", token)
            bridgeCall(bridge, "blobChunk", pending(bridge).single(), "small.3mf", 0, 1, "AQI=", token)
        }
        compose.runOnIdle { assertArrayEquals(byteArrayOf(1, 2), downloads.single().bytes) }
    }

    @Test fun restartingAChunkTransferCannotMixItsPreviousPayloadOrExpireTheCompletedImport() {
        val downloads = mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com", Modifier, { downloads += it }, WebState(), "X1C") }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web)
            val bridge = shadow.getJavascriptInterface("PandyBridge")
            val token = bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web, "https://makerworld.com/model/1")
            bridgeCall(bridge, "file", "model.3mf", "https://cdn.bblmw.com/model.3mf", token)
            val id = pending(bridge).single()
            bridgeCall(bridge, "blobChunk", id, "model.3mf", 0, 2, "CQ==", token)
            bridgeCall(bridge, "blobChunk", id, "model.3mf", 0, 1, "AQI=", token)
        }
        compose.runOnIdle {
            assertArrayEquals(byteArrayOf(1, 2), downloads.single().bytes)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5))
            assertEquals(1, downloads.size)
        }
    }

    @Test fun debugBuildConsoleDiagnosticsAreLoggedAndReleaseBuildFlagsAreRestored() {
        val info = compose.activity.applicationInfo
        val original = info.flags
        info.flags = original or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        try {
            compose.setContent { WebPage("https://makerworld.com", Modifier, null, null, null) }
            compose.runOnIdle {
                val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
                shadowOf(web).webChromeClient.onConsoleMessage(android.webkit.ConsoleMessage("pandy diagnostic fixture", "test", 1, android.webkit.ConsoleMessage.MessageLevel.LOG))
                assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("PandyWeb").any { it.msg == "pandy diagnostic fixture" })
            }
        } finally { info.flags = original }
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
    private fun siteDownloadsAsk(site: ModelSite,start: String,file: String,other: String) {
        val downloads = mutableListOf<WebDownload>()
        val state = WebState()
        compose.setContent { WebPage(start, Modifier, { downloads += it }, state, "X1C", site) }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web)
            assertEquals(start,shadow.lastLoadedUrl)
            shadow.downloadListener.onDownloadStart(file,"","attachment; filename=\"benchy.stl\"","model/stl",3)
            assertTrue(downloads.single().printSheet); assertEquals("benchy.stl",downloads.single().name) // Pandy can print: the download is a print
            state.canPrint=false
            shadow.downloadListener.onDownloadStart(file,"","attachment; filename=\"benchy.stl\"","model/stl",3)
            assertFalse(downloads.last().printSheet) // no slicer or printer: a normal save
            state.canPrint=true
            downloads.removeAt(downloads.lastIndex)
            shadow.downloadListener.onDownloadStart(file.substringBeforeLast('/')+"/all.zip","","attachment; filename=\"all.zip\"","application/zip",3)
            assertFalse(downloads.last().printSheet) // zips are simply saved
            shadow.downloadListener.onDownloadStart(other,"","","model/stl",3)
            shadow.downloadListener.onDownloadStart("https://evil.example/a.stl","","","model/stl",3)
            assertEquals(2,downloads.size)
            assertFalse(shadow.webViewClient.shouldOverrideUrlLoading(web,request(start+"/model/1")))
            assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,request("https://evil.example/")))
        }
    }
    @Test fun thingiverseStlDownloadsAskAndOnlyItsOwnFilesCount() =
        siteDownloadsAsk(ModelSite.Thingiverse,"https://www.thingiverse.com","https://cdn.thingiverse.com/assets/a/benchy.stl","https://files.printables.com/a.stl")
    @Test fun thingiverseBlobDownloadsReachTheBridgeNamedAndAskedButMakerWorldPagesKeepTheirRules() {
        val downloads = mutableListOf<WebDownload>()
        val state = WebState()
        compose.setContent { WebPage("https://www.thingiverse.com", Modifier, { downloads += it }, state, "X1C", ModelSite.Thingiverse) }
        compose.runOnIdle {
            val web = views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow = shadowOf(web)
            val bridge = shadow.getJavascriptInterface("PandyBridge")!!
            val token = bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web, "https://www.thingiverse.com/thing:1/files")
            assertTrue(shadow.lastEvaluatedJavascript.contains("__pandyCanPrint"))
            // The page's own file name is kept; an anonymous ".bin" holding an STL is renamed by its content.
            bridgeCall(bridge, "blob", "part.stl", android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP), token)
            val stl = "solid x\nfacet normal 0 0 0\nendfacet\nendsolid x\n".toByteArray()
            bridgeCall(bridge, "blob", "abc.bin", android.util.Base64.encodeToString(stl, android.util.Base64.NO_WRAP), token)
            bridgeCall(bridge, "blob", "photo.png", android.util.Base64.encodeToString(byteArrayOf(9), android.util.Base64.NO_WRAP), token)
            bridgeCall(bridge, "blob", "bad.stl", android.util.Base64.encodeToString(byteArrayOf(1), android.util.Base64.NO_WRAP), "wrong-token")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(listOf("part.stl", "abc.stl", "photo.png"), downloads.map { it.name })
            assertEquals(listOf(true, true, false), downloads.map { it.printSheet })
            // Off the site (another host) the bridge is closed.
            shadow.webViewClient.onPageFinished(web, "https://evil.example/")
            bridgeCall(bridge, "blob", "late.stl", android.util.Base64.encodeToString(byteArrayOf(1), android.util.Base64.NO_WRAP), token)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(3, downloads.size)
        }
    }
    @Test fun printablesStlDownloadsAskAndOnlyItsOwnFilesCount() =
        siteDownloadsAsk(ModelSite.Printables,"https://www.printables.com","https://files.printables.com/media/prints/1/stls/benchy.stl","https://cdn.thingiverse.com/a.stl")
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

    @Test fun systemBackClosesThePrintOverlayBeforeNavigatingWebHistory() {
        val state=WebState(); var overlays=0; var history=0
        compose.setContent { WebPage("https://makerworld.com",Modifier,null,state,null) }
        compose.runOnIdle {
            state.canGoBack=true; state.goBack={history++}; state.overlayBack={overlays++}
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1,overlays); assertEquals(0,history)
        compose.runOnIdle { state.overlayBack=null }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1,overlays); assertEquals(1,history)
    }

    @Test fun duplicatePageImportsAreBlockedButNativeFilesCanComeFromMakerWorldSubdomains() {
        val downloads=mutableListOf<WebDownload>()
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},WebState(),"H2D") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
            val shadow=shadowOf(web); val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            shadow.webViewClient.onPageFinished(web,"https://sub.makerworld.com/model/1")
            bridgeCall(bridge,"file","first.3mf","https://cdn.makerworld.com/first.3mf",token)
            bridgeCall(bridge,"file","second.3mf","https://makerworld.com/second.3mf",token)
            assertEquals(1,pending(bridge).size)
            bridgeCall(bridge,"nativeFile","native.3mf","https://makerworld.com/native.3mf",token)
            bridgeCall(bridge,"problem","Cancel preparation",token)
        }
        compose.runOnIdle {
            assertEquals(1,downloads.size); assertEquals("https://makerworld.com/native.3mf",downloads.single().url)
        }
    }

    @Test fun handyLaunchFailuresOddFileLinksAndBadBase64AreReportedWithoutImporting() {
        val downloads=mutableListOf<WebDownload>(); val state=WebState().apply { canPrint=false }
        val toasts=mutableListOf<String>()
        compose.setContent { WebPage("https://makerworld.com",Modifier,{downloads+=it},state,"P1S") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single(); val shadow=shadowOf(web)
            shadow.webViewClient.onPageFinished(web,"https://makerworld.com/model/1")
            // Handy isn't installed: the launch throws and the user is told.
            shadowOf(android.app.Application::class.java.cast(compose.activity.application)).checkActivities(true)
            assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,request("bambu-handy://open?file=https%3A%2F%2Fcdn.bblmw.com%2Fm.3mf")))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("Bambu Handy isn't installed",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            shadowOf(android.app.Application::class.java.cast(compose.activity.application)).checkActivities(false)
            state.canPrint=true
            // `url=` is accepted like `file=`; a link without a file name still gets a name; non-https links are refused.
            assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,request("bambustudioopen://open?url=https%3A%2F%2Fcdn.bblmw.com%2F")))
            assertEquals("model.3mf",downloads.single().name.takeIf { it.isNotBlank() } ?: "model.3mf")
            assertTrue(shadow.webViewClient.shouldOverrideUrlLoading(web,request("bambustudioopen://open?file=ftp%3A%2F%2Fcdn.bblmw.com%2Fm.3mf")))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1,downloads.size)
            // Base64 the page can't decode is reported rather than ignored.
            val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            bridgeCall(bridge,"blob","bad.3mf","@@@ not base64 @@@",token)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("Couldn't read that download",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            // Pages away from MakerWorld, and a missing page, never count as MakerWorld.
            shadow.webViewClient.onPageFinished(web,"https://example.com/x")
            shadow.webViewClient.doUpdateVisitedHistory(web,"https://notmakerworld.com/",false)
            bridgeCall(bridge,"problem","Not shown",token)
        }
    }

    @Test fun theBackButtonClosesTheOverlayFirstThenWalksWebHistory() {
        val state=WebState(); var overlays=0
        compose.setContent { WebPage("https://makerworld.com",Modifier,null,state,null) }
        compose.waitForIdle()
        val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single()
        compose.runOnIdle { state.overlayBack={ overlays++ }; state.canGoBack=true }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals("the overlay handles the first back press",1,overlays); state.overlayBack=null }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // no overlay: the web view history goes back
        compose.runOnIdle { assertEquals(1,overlays); assertNotNull(web) }
    }

    private fun matrix(withDownload: Boolean,withState: Boolean) {
        val downloads=mutableListOf<WebDownload>()
        val state=if(withState) WebState() else null
        compose.setContent { WebPage("https://makerworld.com",Modifier,if(withDownload) ({ downloads+=it }) else null,state,"X1C") }
        compose.runOnIdle {
            val web=views(compose.activity.window.decorView).filterIsInstance<WebView>().single(); val shadow=shadowOf(web)
            val bridge=shadow.getJavascriptInterface("PandyBridge"); val token=bridgeToken(bridge)
            val client=shadow.webViewClient
            val pages=listOf("https://makerworld.com/en/x","https://sub.makerworld.com/y","https://notmakerworld.com/","https://example.com/",null)
            val urls=listOf("https://cdn.bblmw.com/a.3mf","http://cdn.bblmw.com/a.3mf","https://evil.example/a.3mf","https://cdn.bblmw.com/a b.3mf","https://makerworld.com@evil.example/a","https://cdn.bblmw.com\\a","https://makerworld.com/dl/a.3mf","")
            for(page in pages) {
                client.onPageFinished(web,page); client.doUpdateVisitedHistory(web,page,false)
                for(tok in listOf(token,"wrong")) {
                    for(b64 in listOf("AQID","!!!","")) bridgeCall(bridge,"blob","m.3mf",b64,tok)
                    for(url in urls) { bridgeCall(bridge,"file","m.3mf",url,tok); bridgeCall(bridge,"nativeFile","m.3mf",url,tok) }
                    bridgeCall(bridge,"problem","oops",tok)
                    // a transfer the page started, completed or corrupted with every combination of chunk arguments
                    bridgeCall(bridge,"file","c.3mf","https://cdn.bblmw.com/c.3mf",token)
                    val id=pending(bridge).firstOrNull() ?: "none"
                    for((i,t,data) in listOf(Triple(0,1,"AQ=="),Triple(0,2,"AQ=="),Triple(1,2,"!!!"),Triple(5,2,"AQ=="),Triple(0,0,"AQ=="),Triple(0,1,"A".repeat(70_001)),Triple(-1,1,"AQ==")))
                        bridgeCall(bridge,"blobChunk",if(i==1) "other-$id" else id,"c.3mf",i,t,data,tok)
                }
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            // navigation: every kind of link, with and without a printer that can print
            client.onPageFinished(web,"https://makerworld.com/en/x")
            for(canPrint in listOf(true,false)) {
                state?.canPrint=canPrint
                for(link in listOf("https://makerworld.com/a","https://other.example/x","http://other.example/x","intent://x","ftp://x/y","bambustudioopen://open?file=https%3A%2F%2Fcdn.bblmw.com%2Fa.3mf",
                    "bambustudioopen://open?url=https%3A%2F%2Fcdn.bblmw.com%2Fb.3mf","bambustudioopen://open?file=http%3A%2F%2Fevil.example%2Fc.3mf","bambustudioopen://open?file=https%3A%2F%2Fevil.example%2Fd.3mf","bambustudioopen://open","bambu-handy://open?file=https%3A%2F%2Fcdn.bblmw.com%2Fe.3mf"))
                    for(main in listOf(true,false)) client.shouldOverrideUrlLoading(web,request(link,main))
            }
            // downloads the page hands to the browser
            for(u in listOf("blob:https://makerworld.com/x","https://cdn.bblmw.com/a.3mf","https://evil.example/a.3mf"))
                shadow.downloadListener.onDownloadStart(u,"","attachment; filename=\"a.3mf\"","application/octet-stream",1)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    @Test fun bridgeAndNavigationMatrixWithDownloadsAndPageState() = matrix(withDownload=true,withState=true)
    @Test fun bridgeAndNavigationMatrixWithoutADownloadHandlerOrPageState() = matrix(withDownload=false,withState=false)
}
