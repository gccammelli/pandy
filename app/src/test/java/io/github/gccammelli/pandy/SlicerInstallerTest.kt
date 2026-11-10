package io.github.gccammelli.pandy

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Get the slicer": only the published file, made for this Pandy by Pandy's own developer, reaches Android's installer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlicerInstallerTest {
    private val app = org.robolectric.RuntimeEnvironment.getApplication() as Application
    private val plugin = "io.github.gccammelli.pandy.slicer.testbed"
    private val apk = ByteArray(300_000) { (it * 31).toByte() }
    private val file get() = File(app.cacheDir, "phone-slicer/pandy-slicer-plugin.apk")
    private lateinit var server: ServerSocket
    private var served = apk
    private var release = ""
    private var releaseStatus = 200
    private val progress = mutableListOf<Float>()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun signedBy(name: String, pkg: String) = PackageInfo().apply {
        packageName = pkg
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(name.toByteArray()))) }
    }
    private fun releaseJson(sha256: String = sha(apk), size: Int = apk.size) =
        """{"tag_name":"v1.0.0","assets":[{"name":"pandy-slicer-plugin.apk","browser_download_url":"http://127.0.0.1:${server.localPort}/plugin.apk","size":$size,"digest":"sha256:$sha256"}]}"""
    /** What Android reads from the downloaded file: its package and who signed it. */
    private fun archiveIs(pkg: String, signer: String) = shadowOf(app.packageManager).setPackageArchiveInfo(file.path, signedBy(signer, pkg))
    private fun install() = runBlocking { SlicerInstaller(app, plugin).install { progress += it } }
    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Before fun serve() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).signingInfo = signedBy("pandy developer", app.packageName).signingInfo
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        // One request per connection: the release's description, or the file.
        Thread {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { c ->
                    val path = c.getInputStream().bufferedReader().readLine().orEmpty().split(" ").getOrElse(1) { "" }
                    val (status, body) = if (path == "/latest.json") releaseStatus to release.toByteArray() else 200 to served
                    runCatching {
                        c.getOutputStream().apply { write("HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        release = releaseJson()
        SlicerInstaller.debugRelease = "http://127.0.0.1:${server.localPort}/latest.json"
    }

    @After fun stop() { server.close(); SlicerInstaller.debugRelease = null; file.delete() }

    @Test fun thePublishedPluginSignedLikePandyIsHandedToAndroidsInstaller() {
        archiveIs(plugin, "pandy developer")
        install()
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content://${app.packageName}.slicerfiles/phone_slicer/pandy-slicer-plugin.apk", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(file.readBytes().contentEquals(apk))
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        // Once the plugin is installed the download (about 90 MB) is removed.
        SlicerInstaller(app, plugin).clear()
        assertFalse(file.exists())
    }

    private fun refused(why: String) {
        assertTrue(why in assertFailsWith<ApiException> { install() }.message!!, why)
        assertNull(started())
        assertFalse(file.exists())
    }

    @Test fun aDownloadThatIsNotThePublishedFileIsDeleted() {
        archiveIs(plugin, "pandy developer")
        served = apk.copyOf().also { it[1000] = 7 }
        refused("doesn't match the published file")
        served = apk + 1
        refused("larger than published")
        served = apk.copyOf(1000)
        refused("doesn't match the published file")
    }

    @Test fun aPluginSignedBySomeoneElseOrForAnotherAppIsDeleted() {
        archiveIs(plugin, "someone else")
        refused("different signature")
        archiveIs("io.github.gccammelli.pandy.slicer", "pandy developer") // the release build's plugin, not this testbed's
        refused("isn't made for this Pandy")
        shadowOf(app.packageManager).setPackageArchiveInfo(file.path, null) // not an APK at all
        refused("isn't made for this Pandy")
    }

    @Test fun aMissingOrUnusableReleaseIsReported() {
        releaseStatus = 404
        refused("isn't published yet")
        releaseStatus = 503
        refused("GitHub answered 503")
        releaseStatus = 200; release = releaseJson(sha256 = "")
        refused("no checksum")
        release = """{"assets":[]}"""
        refused("isn't published yet")
    }

    @Test fun signersMatchOnlyWhenBothAreKnownAndTheSame() {
        assertTrue(sameSigners(setOf("a"), setOf("a")))
        assertFalse(sameSigners(setOf("a"), setOf("b")))
        assertFalse(sameSigners(setOf("a"), setOf("a", "b")))
        assertFalse(sameSigners(emptySet(), emptySet()))
        assertEquals(setOf(sha("pandy developer".toByteArray())), signedBy("pandy developer", "x").signers())
        assertEquals(emptySet(), PackageInfo().signers())
    }
}
