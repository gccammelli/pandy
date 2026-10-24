package io.github.gccammelli.pandy

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import android.view.TextureView
import androidx.media3.common.VideoSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, muted: Boolean, modifier: Modifier, onError: (String) -> Unit, controls: Boolean, loop: Boolean, headers: Map<String, String>) {
    // "rtsps://…#SERIAL": a Bambu printer's own camera (direct mode), RTSP inside TLS checked against Bambu's CAs.
    val rtsps = url.startsWith("rtsps://")
    val tls by androidx.compose.runtime.produceState<javax.net.ssl.SSLSocketFactory?>(null, url) {
        if (rtsps) value = runCatching { BambuTls.factory(url.substringAfter('#', "")) }.getOrNull()
    }
    if (rtsps && tls == null) return Box(modifier)
    VideoPlayerInner(url, muted, modifier, onError, controls, loop, headers, tls)
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerInner(url: String, muted: Boolean, modifier: Modifier, onError: (String) -> Unit, controls: Boolean, loop: Boolean, headers: Map<String, String>, tls: javax.net.ssl.SSLSocketFactory?) {
    val ctx = LocalContext.current
    val player = remember(url) {
        val sources = if (tls != null) androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory().setForceUseRtpTcp(true).setSocketFactory(tls)
        else DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx,
            androidx.media3.datasource.DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers))).setLoadErrorHandlingPolicy(RetryEarlyParts)
        ExoPlayer.Builder(ctx).setMediaSourceFactory(sources).build().apply {
            setMediaItem(MediaItem.Builder().setUri(if (tls != null) url.substringBefore('#') else url)
                // Stay close to the live edge (Home Assistant serves low-latency HLS).
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2_500).build()).build())
            playWhenReady = true
            if (loop) repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    var aspect by remember(url) { mutableFloatStateOf(16 / 9f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // A TextureView draws in the normal view order; PlayerView's default SurfaceView sits behind the window
    // and ends up hidden under Compose backgrounds.
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView({ TextureView(it) }, Modifier.aspectRatio(aspect), update = { player.setVideoTextureView(it) })
        if (controls) Controls(player, Modifier.align(Alignment.BottomCenter))
    }
}

/** Play/pause, a scrubber and the times, for recorded clips. */
@Composable
internal fun Controls(player: Player, modifier: Modifier) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            delay(250)
        }
    }
    fun mmss(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }
    Row(modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play",
            Modifier.size(36.dp).clickable {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.playWhenReady = !playing
            }, tint = Color.White)
        Text(mmss(dragging?.toLong() ?: pos), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Slider(dragging ?: pos.toFloat(), { dragging = it }, Modifier.weight(1f), valueRange = 0f..dur.coerceAtLeast(1).toFloat(),
            onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()); pos = it.toLong() }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x66FFFFFF)))
        Text(mmss(dur), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Home Assistant's low-latency HLS advertises the next part before it exists; asking for it a moment early returns
 * HTTP 416. Retry those shortly instead of failing (the default would drop the only playlist and stop).
 */
@OptIn(UnstableApi::class)
internal object RetryEarlyParts : DefaultLoadErrorHandlingPolicy() {
    private fun early(info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 416

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        if (early(info)) 300L else super.getRetryDelayMsFor(info)

    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions, info: LoadErrorHandlingPolicy.LoadErrorInfo) =
        if (early(info)) null else super.getFallbackSelectionFor(options, info)

    // A part is ready within about a second; real failures still end up as an error.
    override fun getMinimumLoadableRetryCount(dataType: Int) = 10
}
