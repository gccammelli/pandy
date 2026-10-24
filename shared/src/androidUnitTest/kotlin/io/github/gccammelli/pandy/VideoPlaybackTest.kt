package io.github.gccammelli.pandy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoPlaybackTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun resourceContext() {
        @Suppress("UNCHECKED_CAST")
        val provider=Class.forName("org.jetbrains.compose.resources.AndroidContextProvider") as Class<android.content.ContentProvider>
        org.robolectric.Robolectric.buildContentProvider(provider).create()
    }

    @Test fun nativePlayerSendsHeadersReportsHttpFailuresAndCanBeRemoved() {
        val errors=ConcurrentLinkedQueue<String>(); val requests=ConcurrentLinkedQueue<Pair<String,String?>>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            requests+=exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(404,-1); exchange.close()
        }
        server.start()
        val url=mutableStateOf<String?>("http://127.0.0.1:${server.address.port}/first.mp4")
        val muted=mutableStateOf(true)
        try {
            compose.setContent {
                val source=url.value
                if(source==null) Box(Modifier.size(320.dp,200.dp)) else VideoPlayer(source,muted.value,Modifier.size(320.dp,200.dp),
                    { errors+=it },controls=true,loop=true,headers=mapOf("Authorization" to "Bearer test-token"))
            }
            compose.waitUntil(10000) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100)); compose.waitForIdle(); errors.isNotEmpty() }
            assertTrue(errors.first().startsWith("ERROR_CODE_")); assertEquals("Bearer test-token",requests.first().second)
            compose.onAllNodesWithText("0:00").assertCountEquals(2)
            compose.runOnIdle { muted.value=false; url.value="http://127.0.0.1:${server.address.port}/second.mp4" }
            compose.waitUntil(10000) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100)); compose.waitForIdle(); requests.any { it.first=="/second.mp4" } && errors.size>=2 }
            compose.runOnIdle { url.value=null }
            compose.onNodeWithContentDescription("Play").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
            assertTrue(requests.all { it.second=="Bearer test-token" })
        } finally { compose.runOnIdle { url.value=null }; server.stop(0) }
    }

    @Test fun directCameraUsesItsTlsTransportAndReportsARefusedLoopbackConnection() {
        val errors=ConcurrentLinkedQueue<String>()
        val visible=mutableStateOf(true)
        try {
            compose.setContent {
                if(visible.value) VideoPlayer("rtsps://127.0.0.1:1/stream#TEST-CAMERA",true,Modifier.size(320.dp,200.dp),{errors+=it})
            }
            compose.waitUntil(15000) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100)); compose.waitForIdle(); errors.isNotEmpty() }
            assertTrue(errors.single().startsWith("ERROR_CODE_"))
        } finally { compose.runOnIdle { visible.value=false } }
    }
}
