package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.VideoPlayer
import io.github.gccammelli.pandy.decodeImage
import io.github.gccammelli.pandy.hlsUrl
import io.github.gccammelli.pandy.snapshot
import io.github.gccammelli.pandy.toggleCamera
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * The printer's camera through Home Assistant. Plays Home Assistant's HLS stream (X1 and H2 series cameras); when
 * that isn't offered (P1 and A1 cameras, the demo) it shows the camera picture, refreshed every second, which is what
 * those cameras send anyway.
 */
const val CUSTOM_STREAM_KEY = "custom_stream_url"

@Composable
fun LiveView(p: Printer, modifier: Modifier, onFull: (() -> Unit)? = null, zoom: Boolean = false) {
    val custom = Repo.setting(CUSTOM_STREAM_KEY)?.takeIf { it.isNotBlank() }
    if (custom != null) return CustomStream(custom, modifier, zoom)
    val cam = p.camera ?: return Box(modifier.background(Color.Black))
    val err = errorToast()
    val camOn = p["switch.camera"]?.on != false
    val autoplay = Repo.setting("liveview_autoplay") ?: "wifi"
    val autoplayAllowed = when (autoplay) {
        "always" -> true
        "wifi" -> Repo.direct || Repo.demo
        else -> false
    }
    var started by remember(cam, autoplayAllowed) { mutableStateOf(autoplayAllowed) }
    var hls by remember(cam) { mutableStateOf<String?>(null) }
    var stills by remember(cam) { mutableStateOf(false) }
    var frame by remember(cam) { mutableStateOf<ImageBitmap?>(null) }
    var lastJpeg by remember(cam) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(cam, camOn, started) {
        if (!camOn || !started) return@LaunchedEffect
        val b = Repo.ha ?: return@LaunchedEffect
        hls = runCatching { b.hlsUrl(cam) }.getOrNull()
        if (hls == null) stills = true
    }
    LaunchedEffect(cam, stills, camOn, started) {
        if (!stills || !camOn || !started) return@LaunchedEffect
        while (true) {
            runCatching { Repo.ha!!.snapshot(cam) }.getOrNull()?.let { j -> lastJpeg = j; decodeImage(j, 1280)?.let { frame = it } }
            delay(1_000)
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.background(Color.Black).let {
        if (zoom) it.pointerInput(Unit) { detectTransformGestures { _, move, z, _ -> scale = (scale * z).coerceIn(1f, 5f); pan = if (scale == 1f) Offset.Zero else pan + move } } else it
    }) {
        val inner = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
        when {
            !camOn -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.VideocamOff, null, Modifier.size(32.dp), tint = Color.White.copy(alpha = .7f))
                Gap(8.dp)
                T("Live view is off", 13.sp, Color.White.copy(alpha = .8f))
                Gap(8.dp)
                T("Turn on", 13.sp, C.Accent, modifier = Modifier.clickable { p.toggleCamera(true, err) }.padding(8.dp))
            }
            !started -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Videocam, null, Modifier.size(32.dp), tint = Color.White.copy(alpha = .7f))
                Gap(8.dp)
                T("Tap to view", 13.sp, C.Accent, modifier = Modifier.clickable { started = true }.padding(8.dp))
            }
            hls != null -> VideoPlayer(hls!!, muted = true, modifier = inner, onError = { hls = null; stills = true })
            frame != null -> Image(frame!!, null, inner, contentScale = ContentScale.Fit)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = C.Accent, strokeWidth = 2.dp)
        }
        if (camOn) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
            val pl = LocalPlatform.current
            CamButton(Icons.Outlined.CameraAlt) {
                // Snapshot to the phone's photos, as Handy's camera button does.
                val name = "Pandy_${Clock.System.now().toEpochMilliseconds()}"
                Repo.act({ pl.toast(it) }) {
                    val jpeg = lastJpeg ?: snapshot(cam)
                    pl.saveImage(jpeg, name) { e -> pl.toast(if (e == null) "Saved to Photos" else "Couldn't save: ${e.message}") }
                }
            }
            if (onFull != null) CamButton(Icons.Outlined.Fullscreen, onFull)
        }
    }
}

/** A camera the user added themselves (RTSP, or an HTTP stream the player can open), shown in place of the printer's. */
@Composable
private fun CustomStream(url: String, modifier: Modifier, zoom: Boolean) {
    var failed by remember(url) { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.background(Color.Black).let {
        if (zoom) it.pointerInput(Unit) { detectTransformGestures { _, move, z, _ -> scale = (scale * z).coerceIn(1f, 5f); pan = if (scale == 1f) Offset.Zero else pan + move } } else it
    }) {
        val msg = failed
        if (msg != null) Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.VideocamOff, null, Modifier.size(32.dp), tint = Color.White.copy(alpha = .7f))
            Gap(8.dp)
            T("Couldn't play the custom stream ($msg)", 13.sp, Color.White.copy(alpha = .8f))
            Gap(8.dp)
            T("Try again", 13.sp, C.Accent, modifier = Modifier.clickable { failed = null }.padding(8.dp))
        } else VideoPlayer(url, muted = true, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }, onError = { failed = it })
    }
}

@Composable
private fun CamButton(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.padding(start = 6.dp).size(34.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
    }

/** Full-screen live view with pinch to zoom. */
@Composable
fun LiveFullScreen(p: Printer) {
    val pl = LocalPlatform.current
    LaunchedEffect(Unit) { pl.landscape(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pl.landscape(false) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveView(p, Modifier.fillMaxSize(), zoom = true)
        Box(Modifier.statusBarsPadding().padding(8.dp).size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = Color.White)
        }
    }
}
