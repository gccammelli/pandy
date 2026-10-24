package io.github.gccammelli.pandy

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class DemoResourcesTest {
    @Test fun theOfflineDemoServesItsPackagedPicturesAndLeavesUnknownPathsEmpty() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val provider = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider") as Class<android.content.ContentProvider>
        Robolectric.buildContentProvider(provider).create()
        val backend = FakeHomeAssistant()
        val cover = backend.bytes("/api/image_proxy/cover_image")
        val pick = backend.bytes("/api/image_proxy/pick_image")
        val camera = backend.bytes("/api/camera_proxy/demo")
        assertContentEquals(byteArrayOf(-119, 80, 78, 71), cover.take(4).toByteArray())
        assertContentEquals(byteArrayOf(-119, 80, 78, 71), pick.take(4).toByteArray())
        assertContentEquals(byteArrayOf(-1, -40), camera.take(2).toByteArray())
        assertContentEquals(cover, backend.bytes("/api/bambu_lab/file_cache/demo"))
        assertTrue(backend.bytes("/unknown").isEmpty())
        assertTrue(!cover.contentEquals(pick))
    }
}
