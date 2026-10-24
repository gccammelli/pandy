package io.github.gccammelli.pandy

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeCameraBoundaryTest {
    @Test fun theNativeCameraEntryPointRejectsAnUntrustedLoopbackPeerWithoutDeliveringFrames() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val provider = Class.forName("org.jetbrains.compose.resources.AndroidContextProvider") as Class<android.content.ContentProvider>
        Robolectric.buildContentProvider(provider).create()
        // Own the protocol's fixed port before calling the native entry point.
        ServerSocket(6000, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5000
            val accepted = AtomicBoolean()
            val peer = thread(isDaemon = true, name = "untrusted-camera-peer") {
                listener.accept().use { accepted.set(true) }
            }
            var frames = 0
            try {
                assertFailsWith<IOException> {
                    lanJpegStream(LanConfig("127.0.0.1", "TEST-NATIVE-CAMERA", "test-code", "P1S")) { frames++ }
                }
                peer.join(5000)
                assertTrue(accepted.get())
                assertFalse(peer.isAlive)
                assertEquals(0, frames)
            } finally { listener.close(); peer.join(1000) }
        }
    }
}
