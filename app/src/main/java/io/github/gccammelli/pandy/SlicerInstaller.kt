package io.github.gccammelli.pandy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import io.github.gccammelli.pandy.studio.ui.PLUGIN_RELEASE
import io.github.gccammelli.pandy.studio.ui.PluginDownload
import io.github.gccammelli.pandy.studio.ui.pluginDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** SHA-256 of each certificate that signed a package, however this Android version reports them. */
@Suppress("DEPRECATION")
fun PackageInfo.signers(): Set<String> {
    val certs = (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else null)?.takeIf { it.isNotEmpty() } ?: signatures.orEmpty()
    return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
}

@Suppress("DEPRECATION")
private val SIGNER_FLAGS = PackageManager.GET_SIGNATURES or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0

/** Whether a package (or an APK file's) signers are exactly Pandy's own: the plugin must come from Pandy's developer. */
fun sameSigners(ours: Set<String>, theirs: Set<String>) = ours.isNotEmpty() && ours == theirs

/** Gets the slicer plugin: the latest release's APK, checked against the release's SHA-256 and Pandy's own signature. */
class SlicerInstaller(private val context: Context, private val pluginPackage: String) {
    private val apk get() = File(File(context.cacheDir, "phone-slicer"), "pandy-slicer-plugin.apk")

    /** Removes the downloaded file (about 90 MB) once the plugin is installed. */
    fun clear() { apk.delete() }

    suspend fun install(progress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val override = debugRelease.takeIf { BuildConfig.DEBUG }
            val release = pluginDownload(text(override ?: PLUGIN_RELEASE), anyHost = override != null)
            apk.also { it.parentFile?.mkdirs(); download(release, it, progress); verify(it) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.slicerfiles", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        setRequestProperty("User-Agent", "Pandy")
    }

    private fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 404) throw ApiException("The slicer isn't published yet.")
            if (c.responseCode != 200) throw ApiException("GitHub answered ${c.responseCode}; try again later.")
            return c.inputStream.use { it.readNBytesCompat(2_000_000) }.decodeToString()
        } finally { c.disconnect() }
    }

    private suspend fun download(d: PluginDownload, to: File, progress: (Float) -> Unit) {
        val c = open(d.url)
        try {
            if (c.responseCode != 200) throw ApiException("The slicer download failed (${c.responseCode}).")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input -> to.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > d.size) throw ApiException("The slicer download is larger than published; not installing it.")
                    digest.update(buf, 0, n); out.write(buf, 0, n)
                    progress(total.toFloat() / d.size)
                }
            } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != d.size || sha != d.sha256) throw ApiException("The slicer download doesn't match the published file; not installing it.")
        } catch (e: Throwable) { to.delete(); throw e } finally { c.disconnect() }
    }

    /** The file must be the plugin, signed by whoever signed this Pandy. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val theirs = pm.getPackageArchiveInfo(apk.path, SIGNER_FLAGS)
        val ours = pm.getPackageInfo(context.packageName, SIGNER_FLAGS)
        if (theirs?.packageName != pluginPackage || !sameSigners(ours.signers(), theirs.signers())) {
            apk.delete()
            throw ApiException("The downloaded slicer isn't made for this Pandy (it has a different signature); not installing it.")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 14)
        while (out.size() <= max) { val n = read(buf); if (n < 0) return out.toByteArray(); out.write(buf, 0, n) }
        throw ApiException("The slicer's release could not be read.")
    }

    companion object {
        /** Debug builds: another address for the release description (`--es plugin_release http://127.0.0.1:8765/latest.json`). */
        @Volatile var debugRelease: String? = null
    }
}
