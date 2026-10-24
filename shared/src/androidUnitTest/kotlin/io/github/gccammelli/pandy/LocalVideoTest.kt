package io.github.gccammelli.pandy

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class LocalVideoTest {
    @Test fun aTimelapseOnThePrinterIsCopiedToThePhoneForPlayback() = runBlocking<Unit> {
        val before = appContext
        appContext = ApplicationProvider.getApplicationContext()
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String) = emptyList<FileEntry>()
            override suspend fun read(path: String, offset: Long, length: Int): ByteArray { assertEquals("/timelapse/clip.mp4", path); return byteArrayOf(1, 2, 3) }
            override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST123", "code", "X1C"), storageFactory = { storage })
        try {
            val uri = printer.localVideo("TEST123/timelapse/clip.mp4")
            assertTrue(uri.startsWith("file://") && uri.endsWith("clip.mp4"))
            assertContentEquals(byteArrayOf(1, 2, 3), java.io.File(java.net.URI(uri)).readBytes())
        } finally { printer.stop(); appContext = before }
    }
}
