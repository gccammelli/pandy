@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gccammelli.pandy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.setMuted
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.Foundation.NSNotificationCenter
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeMake

/**
 * AVPlayer plays Home Assistant's HLS and MP4s natively. Live view: no controls; timelapses: the system's own controls
 * (play/pause and a scrubber), aspect-fit either way.
 */
@Composable
actual fun VideoPlayer(url: String, muted: Boolean, modifier: Modifier, onError: (String) -> Unit, controls: Boolean, loop: Boolean, headers: Map<String, String>) {
    // AVPlayer has no RTSP: the X1/H2 camera in direct mode can't play here (Home Assistant's HLS can).
    if (url.startsWith("rtsp")) { LaunchedEffect(url) { onError("RTSP isn't supported on iOS") }; return }
    val player = remember(url) {
        val asset = platform.AVFoundation.AVURLAsset(uRL = NSURL.URLWithString(url)!!, options = if (headers.isEmpty()) null else mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
        AVPlayer(playerItem = platform.AVFoundation.AVPlayerItem(asset = asset))
    }
    LaunchedEffect(player, muted) { player.setMuted(muted) }
    // AVPlayer reports failure on the item's status (KVO); polling it is enough here.
    LaunchedEffect(player) {
        while (true) {
            if (player.currentItem?.status == AVPlayerItemStatusFailed) {
                onError(player.currentItem?.error?.localizedDescription ?: "Playback failed"); break
            }
            delay(1000)
        }
    }
    DisposableEffect(player) {
        // Looping (labelling clips): back to the start whenever the item ends.
        val end = if (loop) NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, player.currentItem, null) { _ ->
            player.seekToTime(CMTimeMake(0, 1)); player.play()
        } else null
        player.play()
        onDispose { player.pause(); end?.let { NSNotificationCenter.defaultCenter.removeObserver(it) } }
    }
    UIKitViewController(
        factory = { AVPlayerViewController().apply { this.player = player; showsPlaybackControls = controls } },
        modifier = modifier,
        update = { it.showsPlaybackControls = controls },
    )
}
