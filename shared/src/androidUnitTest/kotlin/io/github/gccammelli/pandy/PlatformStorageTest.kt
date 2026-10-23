package io.github.gccammelli.pandy

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PlatformStorageTest {
    @Test fun settingsAndFilesRoundTripAndDamagedTokensAreRemoved() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val storage = androidStorage(ctx)
        storage.settings.putString("theme", "dark"); assertEquals("dark", storage.settings.getString("theme"))
        storage.settings.putString("theme", null); assertNull(storage.settings.getString("theme"))
        storage.settings.putString("a", "b"); storage.settings.clear(); assertNull(storage.settings.getString("a"))
        storage.files.write("test-header.img", byteArrayOf(1,2)); assertArrayEquals(byteArrayOf(1,2), storage.files.read("test-header.img"))
        storage.files.delete("test-header.img"); assertNull(storage.files.read("test-header.img"))
        assertNull(storage.secrets.get("missing"))
        ctx.getSharedPreferences("auth", Context.MODE_PRIVATE).edit().putString("damaged", "enc1:not-valid").commit()
        assertNull(storage.secrets.get("damaged")); storage.secrets.set("damaged", null)
    }
    @Test fun imagesAreDecodedAndDownsampledWhileInvalidBytesAreRejected() {
        val stream = ByteArrayOutputStream()
        Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,stream)
        val image = decodeImage(stream.toByteArray(),20)!!
        assertTrue(image.width <= 20); assertTrue(image.height <= 20)
        assertNull(decodeImage(byteArrayOf(1,2,3),20))
    }
    @Test fun printerVideoCacheWritesInsideApplicationCache() = kotlinx.coroutines.runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        appContext=context
        try {
            val url=cacheFile("folder/sample.mp4",byteArrayOf(1,2,3))
            val file=java.io.File(android.net.Uri.parse(url).path!!)
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath+"/"))
            assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())
            file.delete()
        } finally { appContext=null }
    }

}
