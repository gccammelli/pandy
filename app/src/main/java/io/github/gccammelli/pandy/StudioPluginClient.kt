package io.github.gccammelli.pandy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.gccammelli.pandy.studio.slicer.ProfileSource
import io.github.gccammelli.pandy.studio.slicer.SliceJob
import io.github.gccammelli.pandy.studio.slicer.SliceOutcome
import io.github.gccammelli.pandy.studio.slicer.SliceRun
import io.github.gccammelli.pandy.studio.slicer.StudioEngine
import io.github.gccammelli.pandy.studio.slicer.pluginRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The editor talks only to a same-signature plugin; the engine is never loaded into Pandy. */
class StudioPluginClient(private val context: Context) : StudioEngine {
    val pkg get() = "io.github.gccammelli.pandy.slicer" + if (BuildConfig.APPLICATION_ID.endsWith(".testbed")) ".testbed" else ""
    /**
     * Installed, and signed with Pandy's own key. The plugin's permission keeps other apps from calling it; this keeps
     * Pandy from handing models to, and taking G-code from, another app that took the plugin's name.
     */
    fun available(): Boolean = context.packageManager.let { pm ->
        pm.resolveService(Intent(ACTION).setPackage(pkg), 0) != null && pm.checkSignatures(context.packageName, pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH
    }

    private suspend fun request(code: Int, params: Bundle = Bundle(), progress: (String) -> Unit = {}): String = withTimeout(15 * 60 * 1000L) {
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            var remote: Messenger? = null
            var bound = false
            lateinit var connection: ServiceConnection
            fun unbind() { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
            val listener = Messenger(Handler(Looper.getMainLooper()) { reply ->
                if (reply.data.getString("request_id") == id && continuation.isActive) {
                    val text = reply.data.getString("text").orEmpty()
                    when (reply.what) {
                        100 -> progress(text)
                        101 -> { unbind(); continuation.resume(text) }
                        102 -> { unbind(); continuation.resumeWithException(ApiException(text.ifBlank { "The slicer failed." })) }
                    }
                }
                true
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!continuation.isActive) return
                    try {
                        remote = Messenger(binder)
                        params.putString("request_id", id)
                        remote!!.send(Message.obtain(null, code).apply { data = params; replyTo = listener })
                    } catch (e: Exception) { unbind(); if (continuation.isActive) continuation.resumeWithException(e) }
                }
                override fun onServiceDisconnected(name: ComponentName?) { unbind(); if (continuation.isActive) continuation.resumeWithException(ApiException("The slicer plugin disconnected.")) }
                override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
            }
            try {
                if (!available()) throw ApiException("Install the Pandy slicer plugin.")
                bound = context.bindService(Intent(ACTION).setPackage(pkg), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resumeWithException(ApiException("Install the Pandy slicer plugin."))
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            continuation.invokeOnCancellation {
                runCatching { remote?.send(Message.obtain(null, 3).apply { data = Bundle().apply { putString("request_id", id) }; replyTo = listener }) }
                unbind()
            }
        }
    }
    suspend fun capabilities() = JSONObject(request(1))

    suspend fun resources(): File {
        val dir = File(context.cacheDir, "studio-profiles")
        val archive = File(context.cacheDir, "studio-resources-${UUID.randomUUID()}.zip")
        try {
            val pfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            val reply = try { request(4, Bundle().apply { putParcelable("output", pfd) }) } finally { pfd.close() }
            return withContext(Dispatchers.IO) {
                val metadata = JSONObject(reply); require(metadata.getInt("version") == 1)
                val digest = MessageDigest.getInstance("SHA-256")
                archive.inputStream().use { stream -> val buf = ByteArray(65536); while (true) { val n = stream.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(hash == metadata.getString("sha256")) { "Slicer profiles failed their integrity check." }
                val target = File(dir, hash)
                if (!File(target, ".complete").exists()) {
                    val partial = File(dir, "$hash.tmp"); partial.deleteRecursively(); partial.mkdirs()
                    try {
                        var count = 0; var total = 0L
                        ZipInputStream(archive.inputStream().buffered()).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                require(++count <= 10_000) { "Too many profile files." }
                                val dest = File(partial, entry.name).canonicalFile
                                require(dest.path.startsWith(partial.canonicalPath + File.separator)) { "Invalid profile path." }
                                if (entry.isDirectory) dest.mkdirs() else {
                                    dest.parentFile?.mkdirs()
                                    dest.outputStream().use { out -> val buf = ByteArray(65536); while (true) { val n = zip.read(buf); if (n < 0) break; total += n; require(total <= 250_000_000) { "Profiles are too large." }; out.write(buf, 0, n) } }
                                }
                                zip.closeEntry()
                            }
                        }
                        File(partial, ".complete").writeText(hash)
                        target.deleteRecursively(); check(partial.renameTo(target)) { "Could not install slicer profiles." }
                    } finally { partial.deleteRecursively() }
                }
                target
            }
        } finally { archive.delete() }
    }

    override suspend fun profiles(): ProfileSource {
        val root = resources().canonicalFile
        return ProfileSource { path ->
            val file = File(root, if (path.startsWith("pandy/")) path else "resources/$path").canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "Invalid profile path." }
            file.takeIf { it.isFile }?.readText()
        }
    }

    override suspend fun slice(job: SliceJob, onLog: (String) -> Unit): SliceRun {
        val started = SystemClock.elapsedRealtime()
        val log = mutableListOf<String>()
        val (bytes, response) = sliceFile("editor", job.pluginRequest(), job.model3mf) { line ->
            if (log.size < 1000) log.add(line)
            onLog(line)
        }
        require(JSONObject(response).getInt("version") == 1) { "Unsupported slicer response." }
        return SliceRun(SliceOutcome(0, "Success."), 0, bytes, SystemClock.elapsedRealtime() - started, log, null)
    }

    suspend fun serverSlice(json: String, model: ByteArray): PhoneSlicerResponse {
        val (bytes, metadata) = sliceFile("server", json, model) {}
        return PhoneSlicerResponse(metadata, bytes)
    }

    private suspend fun sliceFile(mode: String, json: String, model: ByteArray, onLog: (String) -> Unit): Pair<ByteArray, String> {
        require(model.size in 1..100_000_000)
        val dir = File(context.cacheDir, "studio-jobs").apply { mkdirs() }
        val id = UUID.randomUUID().toString(); val input = File(dir, "$id.input"); val output = File(dir, "$id.output")
        try {
            withContext(Dispatchers.IO) { input.writeBytes(model) }
            val inFd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val outFd = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            val response = try {
                request(2, Bundle().apply { putString("mode", mode); putString("request_json", json); putParcelable("input", inFd); putParcelable("output", outFd) }, onLog)
            } finally { inFd.close(); outFd.close() }
            return withContext(Dispatchers.IO) { require(output.length() in 1..100_000_000L) { "Invalid sliced file." }; output.readBytes() to response }
        } finally { input.delete(); output.delete() }
    }
    companion object { private const val ACTION = "io.github.gccammelli.pandy.slicer.BIND_V1" }
}
