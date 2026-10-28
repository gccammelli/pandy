package io.github.gccammelli.pandy

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Small key-value settings store (SharedPreferences on Android). */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?) // null removes the key
    fun clear()
}

/** The Home Assistant token, encrypted with the Android Keystore. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String?) // null removes
}

/** Private app files. */
interface AppFiles {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

class PlatformStorage(val settings: KeyValue, val secrets: SecretStore, val files: AppFiles)

/** UI-side platform actions, provided by MainActivity via [LocalPlatform]. */
interface PlatformUi {
    val appVersion: String
    fun openUrl(url: String)
    fun toast(message: String)
    /** Light or dark status/navigation bar icons to match the app's theme. */
    fun setDarkBars(dark: Boolean)
    /** Let the user pick a photo (system photo picker); [done] gets its bytes, or null if cancelled. */
    fun pickImage(done: (ByteArray?) -> Unit)
    /** Saves a JPEG to the phone's photos; [done] gets null on success or the error. */
    fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit)
    /** Saves a downloaded file (Downloads on Android, the standard Files/share sheet on iOS). */
    fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit)
    /** Opens another app (Bambu Handy), or its store page when it isn't installed. */
    fun openApp(androidPackage: String, storeUrl: String)
    /** Full-screen live view turns the phone sideways; false restores the normal orientation. */
    fun landscape(on: Boolean)
    /** Print alerts need Pandy to keep running in the background, which only Android allows; iOS hides the setting. */
    val canNotify: Boolean get() = false
    /** Asks for the notification permission (Android 13 and later), if it isn't granted yet. */
    fun requestNotifications() {}
    /** Starts or stops the background connection to match the setting. */
    fun watchInBackground(on: Boolean) {}
}

val LocalPlatform = staticCompositionLocalOf<PlatformUi> { error("No platform provided") }

/** Decodes an image, downscaled so its longest side is at most [maxSide] pixels. */
expect fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap?

/** Plays an HLS or MP4 address (from Home Assistant). [onError] gets a short message if playback fails; [controls] adds play/pause and a scrubber (timelapses); [headers] go with every request (Home Assistant's token for its file API). */
@androidx.compose.runtime.Composable
expect fun VideoPlayer(url: String, muted: Boolean, modifier: androidx.compose.ui.Modifier, onError: (String) -> Unit, controls: Boolean = false, loop: Boolean = false, headers: Map<String, String> = emptyMap())

/**
 * A file a web page handed over: an address to fetch (with the page's [cookies] for it), or the [bytes] themselves
 * when the page made the file in the browser (a blob).
 */
class WebDownload(val url: String?, val name: String, val cookies: String?, val bytes: ByteArray? = null, val printSheet: Boolean = false,
                  /** A model file from Thingiverse or Printables: Pandy asks whether to print or save it. */ val ask: Boolean = false)

/**
 * A web page (MakerWorld). [onDownload] gets each file the page downloads, and each "open in Bambu Studio" link, instead
 * of the browser saving or following it; null lets downloads go nowhere. [printer] is the user's printer as MakerWorld
 * names it ("X1 Carbon"), so its Print button prefers profiles made for it.
 */
@androidx.compose.runtime.Composable
expect fun WebPage(url: String, modifier: androidx.compose.ui.Modifier, onDownload: ((WebDownload) -> Unit)?, state: WebState? = null, printer: String? = null, site: ModelSite = ModelSite.MakerWorld)

/** Back navigation inside a [WebPage]: [canGoBack] and [goBack] are filled in by the page. */
class WebState {
    var canGoBack by androidx.compose.runtime.mutableStateOf(false)
    var goBack: () -> Unit = {}
    var currentUrl by androidx.compose.runtime.mutableStateOf("")
    var overlayBack by androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null)
    /**
     * Pandy can print on the user's printer now. When false (the printer takes commands only from Bambu's apps)
     * MakerWorld's own buttons are left alone, and "Open in Bambu Handy" opens Handy.
     */
    var canPrint by androidx.compose.runtime.mutableStateOf(true)
}

/** Tells the page whether Pandy can print, and re-applies or removes the Print button to match. */
fun canPrintJs(on: Boolean) = "window.__pandyCanPrint = $on; window.__pandyRepatch && window.__pandyRepatch();"
