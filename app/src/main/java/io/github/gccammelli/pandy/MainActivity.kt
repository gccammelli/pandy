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
import io.github.gccammelli.pandy.ui.AppTheme
import io.github.gccammelli.pandy.ui.Nav
import io.github.gccammelli.pandy.ui.Screen
import io.github.gccammelli.pandy.ui.Root

class MainActivity : ComponentActivity(), PlatformUi {
    private var pendingPick: ((ByteArray?) -> Unit)? = null
    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        done(uri?.let { u -> runCatching { contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() })
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
        setContent { CompositionLocalProvider(LocalPlatform provides this) { AppTheme { Root() } } }
    }

    // Live updates only while Pandy is on screen.
    override fun onStart() {
        super.onStart()
        Repo.start()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Repo.stop()
    }

    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugOpen(intent)
    }

    /** Debug builds: `adb shell am start -n …/.MainActivity --es screen control` opens a screen directly (for testing). */
    private fun debugOpen(i: Intent?) {
        if (!BuildConfig.DEBUG) return
        // `--es print_url http://…/model.3mf`: hands the MakerWorld print sheet a file, as a download from the web view would.
        i?.getStringExtra("print_url")?.let { u -> Nav.stack.clear(); Nav.tab = 0; io.github.gccammelli.pandy.ui.debugDownload.value = WebDownload(u, u.substringAfterLast('/'), null); return }
        val name = i?.getStringExtra("screen") ?: return
        val screens = mapOf("control" to Screen.Control, "motion" to Screen.Motion, "skip" to Screen.Skip, "live" to Screen.LiveFull,
            "printer" to Screen.PrinterSettings, "assistant" to Screen.Assistant, "files" to Screen.Files, "filament" to Screen.Filament,
            "history" to Screen.History, "settings" to Screen.Settings, "about" to Screen.About, "slicer" to Screen.Slicer)
        Nav.stack.clear()
        when (name) { "models" -> Nav.tab = 0; "devices" -> Nav.tab = 1; "me" -> Nav.tab = 2; else -> screens[name]?.let { Nav.go(it) } }
    }

    override fun landscape(on: Boolean) {
        requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun setDarkBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(style, style)
    }
}
