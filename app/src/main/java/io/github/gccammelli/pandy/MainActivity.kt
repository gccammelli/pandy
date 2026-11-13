package io.github.gccammelli.pandy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.gccammelli.pandy.ui.AppTheme
import io.github.gccammelli.pandy.ui.Nav
import io.github.gccammelli.pandy.ui.Screen
import io.github.gccammelli.pandy.ui.Root
import io.github.gccammelli.pandy.studio.ui.sharedModelName

open class MainActivity : ComponentActivity(), PlatformUi {
    private val phoneGate = PhoneExchangeGate()
    private val studioPlugin by lazy { StudioPluginClient(applicationContext) }
    override val phoneSlicerSupported get() = true
    override fun phoneSlicerAvailable() = studioPlugin.available()
    override suspend fun phoneSlicer(action: String, request: String, model: ByteArray?): PhoneSlicerResponse = withContext(Dispatchers.Main) {
        phoneGate.run {
            when (action) {
                "CAPABILITIES_V1" -> PhoneSlicerResponse(studioPlugin.capabilities().toString(), null)
                "SLICE_V1" -> studioPlugin.serverSlice(request, model ?: throw ApiException("Choose a model before slicing."))
                else -> throw ApiException("This slicer action is not available yet.")
            }
        }
    }
    override val studio by lazy { StudioHost(this, studioPlugin) }
    override suspend fun installSlicerPlugin(progress: (Float) -> Unit) = SlicerInstaller(applicationContext, studioPlugin.pkg).install(progress)
    override fun onResume() {
        super.onResume()
        if (studioPlugin.available()) SlicerInstaller(applicationContext, studioPlugin.pkg).clear()
    }
    private var pendingModels: ((List<Uri>) -> Unit)? = null
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingModels?.let { pendingModels = null; it(uris) }
    }

    /** The system file picker, for the Studio tab: the chosen STL and 3MF files. */
    suspend fun pickModels(): List<Pair<String, ByteArray>> {
        val uris = kotlinx.coroutines.suspendCancellableCoroutine<List<Uri>> { c ->
            pendingModels?.invoke(emptyList())
            pendingModels = { c.resumeWith(Result.success(it)) }
            modelPicker.launch(arrayOf("*/*"))
        }
        return withContext(Dispatchers.IO) { uris.map { readModel(it) } }
    }

    /** A model file's name and bytes. Apps often share them without a usable name: then the content says which it is. */
    private fun readModel(uri: Uri): Pair<String, ByteArray> {
        if (!sharedUriAllowed(uri, packageName)) throw ApiException("Pandy can't open that file.")
        val listed = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model"
        val bytes = (contentResolver.openInputStream(uri) ?: throw ApiException("Couldn't read $listed.")).use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 100_000_000) throw ApiException("$listed is larger than 100 MB.") }
            out.toByteArray()
        }
        return sharedModelName(listed, bytes) to bytes
    }

    /** Opens an STL or 3MF another app sent ("Open with Pandy"): in Studio, or the print sheet for a 3MF project. */
    private fun openShared(i: Intent?) {
        val uri = sharedUri(i) ?: return
        i?.action = null // handled: a rotation or relaunch must not open it again
        CoroutineScope(Dispatchers.Main).launch {
            val session = io.github.gccammelli.pandy.studio.ui.StudioSession
            // Reading and unpacking happen off the main thread; OutOfMemoryError included: the file comes from another app.
            runCatching { withContext(Dispatchers.IO) { readModel(uri).let { (name, bytes) -> Triple(name, bytes, session.isProject(name, bytes)) } } }
                .onSuccess { (name, bytes, project) -> if (!session.open(name, bytes, project)) toast("Pandy opens STL and 3MF files.") }
                .onFailure { toast((it as? ApiException)?.message ?: "Couldn't open the file.") }
        }
    }

    private var pendingPick: ((ByteArray?) -> Unit)? = null
    private var pendingFileBytes: ByteArray? = null
    private var pendingFileDone: ((Throwable?) -> Unit)? = null
    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        done(uri?.let { u -> runCatching { contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() })
    }
    private val saveFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pendingFileBytes
        val done = pendingFileDone
        pendingFileBytes = null
        pendingFileDone = null
        val uri = result.data?.data
        if (done == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || bytes == null || uri == null) {
            return@registerForActivityResult
        }
        Thread {
            val error = runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.exceptionOrNull()
            runOnUiThread { done(error) }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!Nav.back()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        debugOpen(intent)
        if (savedInstanceState == null) openShared(intent)
        setContent { CompositionLocalProvider(LocalPlatform provides this) { AppTheme { Root() } } }
    }

    // Live updates while Pandy is on screen, and in the background too when print alerts are on.
    override fun onStart() {
        super.onStart()
        Repo.visible = true
        Repo.start()
        // Also when the user connects while Pandy is open: the watcher starts then, not at the next launch.
        watchJob = CoroutineScope(Dispatchers.Main).launch { Repo.state.map { it.on }.distinctUntilChanged().collect { syncWatch() } }
    }

    private var watchJob: Job? = null

    private fun syncWatch() {
        if (!Repo.watching) return
        watchInBackground(true)
        // Asked once, the first time Pandy is connected with alerts on; Settings asks again if the user turns them back on.
        if (Repo.setting("notify_asked") == null) { Repo.setSetting("notify_asked", "true"); requestNotifications() }
    }

    override fun onStop() {
        super.onStop()
        watchJob?.cancel()
        Repo.visible = false
        if (!isChangingConfigurations && !Repo.watching) Repo.stop()
    }

    override val canNotify get() = true

    override fun requestNotifications() {
        if (!canNotify()) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun watchInBackground(on: Boolean) {
        val service = Intent(this, WatchService::class.java)
        // Without the permission the alerts would be silent, so don't spend battery on watching.
        if (on && Repo.watching && canNotify()) androidx.core.content.ContextCompat.startForegroundService(this, service) else stopService(service)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        watchInBackground(true)
    }

    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun openApp(androidPackage: String, storeUrl: String) =
        startActivity(packageManager.getLaunchIntentForPackage(androidPackage) ?: Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl)))
    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun share(text: String, imageBytes: ByteArray?, title: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val isPng = imageBytes.size >= 4 && imageBytes[0] == 0x89.toByte() && imageBytes[1] == 0x50.toByte()
                val mime = if (isPng) "image/png" else "image/jpeg"
                val ext = if (isPng) "png" else "jpg"
                type = mime
                val uri = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "pandy_share_${System.currentTimeMillis()}.$ext")
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pandy")
                        }
                        val u = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (u != null) contentResolver.openOutputStream(u)?.use { it.write(imageBytes) }
                        u
                    } else null
                }.getOrNull()
                if (uri != null) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                type = "text/plain"
            }
        }
        startActivity(Intent.createChooser(intent, title ?: "Share print"))
    }

    override fun pickImage(done: (ByteArray?) -> Unit) {
        pendingPick = done
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // shortcut: Android 10+ only (no storage permission needed there); older phones get an error toast.
    override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) {
        val result = runCatching {
            check(android.os.Build.VERSION.SDK_INT >= 29) { "needs Android 10 or later" }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pandy")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(jpeg) }
        }
        done(result.exceptionOrNull())
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) {
        val safe = name.substringAfterLast('/').ifBlank { "download.bin" }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            if (pendingFileDone != null) { done(IllegalStateException("Another download is being saved")); return }
            pendingFileBytes = bytes
            pendingFileDone = done
            saveFilePicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, safe)
            })
            return
        }
        Thread {
            val result = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Pandy")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                try {
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Throwable) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
            runOnUiThread { done(result.exceptionOrNull()) }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugOpen(intent)
        openShared(intent)
    }

    /** Debug builds: `adb shell am start -n …/.MainActivity --es screen control` opens a screen directly (for testing). */
    private fun debugOpen(i: Intent?) {
        if (!BuildConfig.DEBUG) return
        // `--es plugin_release http://127.0.0.1:8765/latest.json`: where "Get the slicer" looks for the plugin (with `adb reverse`).
        i?.getStringExtra("plugin_release")?.let { SlicerInstaller.debugRelease = it }
        // `--es print_url http://…/model.3mf`: hands the MakerWorld print sheet a file, as a download from the web view would.
        i?.getStringExtra("print_url")?.let { u -> Nav.stack.clear(); Nav.tab = 0; io.github.gccammelli.pandy.ui.debugDownload.value = WebDownload(u, u.substringAfterLast('/'), null, printSheet = true); return }
        // `--es site thingiverse|printables|makerworld`: the Models tab shows that site.
        i?.getStringExtra("site")?.let { n -> io.github.gccammelli.pandy.ModelSite.entries.firstOrNull { it.name.equals(n, true) }?.let { Nav.stack.clear(); Nav.site = it; Nav.tab = 0; return } }
        // `--es notify_test 1`: shows a sample print alert.
        i?.getStringExtra("notify_test")?.let { Repo.notifier?.show("test", "Print finished", "Benchy is done on Test printer"); return }
        val name = i?.getStringExtra("screen") ?: return
        val screens = mapOf("control" to Screen.Control, "motion" to Screen.Motion, "skip" to Screen.Skip, "live" to Screen.LiveFull,
            "printer" to Screen.PrinterSettings, "calibration" to Screen.Calibration, "print_options" to Screen.PrintOptions,
            "ams_settings" to Screen.AmsSettings, "liveview_settings" to Screen.LiveviewSettings,
            "assistant" to Screen.Assistant, "files" to Screen.Files, "storage" to Screen.PrinterFiles, "filament" to Screen.Filament,
            "history" to Screen.History, "settings" to Screen.Settings, "about" to Screen.About, "slicer" to Screen.Slicer)
        Nav.stack.clear()
        when (name) { "models" -> Nav.tab = 0; "devices" -> Nav.tab = 1; "me" -> Nav.tab = 2; "studio" -> Nav.tab = 3; else -> screens[name]?.let { Nav.go(it) } }
    }

    override fun landscape(on: Boolean) {
        requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun setDarkBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(style, style)
    }
}

/** The file a VIEW or SEND intent hands over; null for anything else. Any app can send these: nothing in them is trusted. */
fun sharedUri(i: Intent?): Uri? = runCatching {
    @Suppress("DEPRECATION")
    when (i?.action) { Intent.ACTION_VIEW -> i.data; Intent.ACTION_SEND -> i.extras?.get(Intent.EXTRA_STREAM) as? Uri; else -> null }
}.getOrNull()

/**
 * Whether Pandy may read a file another app asked it to open: only what that app shares through a content provider,
 * never a path (Pandy could be pointed at its own files: printer access codes, tokens) or Pandy's own providers.
 */
fun sharedUriAllowed(uri: Uri, packageName: String): Boolean =
    uri.scheme == "content" && uri.authority?.substringAfterLast('@')?.startsWith(packageName) != true
