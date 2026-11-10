package io.github.gccammelli.pandy

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedUriTest {
    private val pkg = "io.github.gccammelli.pandy"
    private val data = "/data/user/0/$pkg"
    private fun allowed(uri: String) = sharedUriAllowed(Uri.parse(uri), pkg)

    @Test fun filesFromOtherAppsAndSharedStorageOpen() {
        assertTrue(allowed("content://com.android.providers.downloads.documents/document/12"))
        assertTrue(allowed("content://media/external/file/7"))
    }

    @Test fun pandysOwnDataIsNeverReadForAnotherApp() {
        assertFalse(allowed("file://$data/files/datastore/settings.preferences_pb"))
        assertFalse(allowed("file:///data/data/$pkg/shared_prefs/pandy.xml"))
        assertFalse(allowed("file:///storage/../data/user/0/$pkg/files/x.stl"))
        assertFalse(allowed("file:///data/user/10/$pkg/files/x.stl"))
        assertFalse(allowed("file:///proc/self/environ"))
        assertFalse(allowed("content://$pkg.slicerfiles/phone_slicer/pandy-slicer-plugin.apk"))
        assertFalse(allowed("content://0@$pkg.files/x"))
    }

    @Test fun aMalformedShareIsIgnoredRatherThanCrashing() {
        val uri = Uri.parse("content://media/external/file/7")
        assertEquals(uri, sharedUri(Intent(Intent.ACTION_VIEW, uri)))
        assertEquals(uri, sharedUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(sharedUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a file")))
        assertNull(sharedUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, 7)))
        assertNull(sharedUri(Intent(Intent.ACTION_SEND)))
        assertNull(sharedUri(Intent(Intent.ACTION_MAIN, uri)))
        assertNull(sharedUri(null))
    }

    @Test fun otherSchemesAreRefused() {
        assertFalse(allowed("file:///storage/emulated/0/Download/benchy.stl"))
        assertFalse(allowed("http://example.com/model.stl"))
        assertFalse(allowed("android.resource://$pkg/raw/x"))
        assertFalse(allowed("file:"))
    }
}
