// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.ApiException
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.failureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where the slicer plugin is published. */
const val PLUGIN_RELEASE = "https://api.github.com/repos/gccammelli/pandy-slicer-plugin/releases/latest"
const val PLUGIN_ASSET = "pandy-slicer-plugin.apk"
/** Larger than any plugin build; a download past this is not the plugin. */
const val PLUGIN_MAX_BYTES = 400_000_000L

/** The plugin file of a release: where to download it, and what it must be. */
data class PluginDownload(val url: String, val sha256: String, val size: Long, val version: String)

/**
 * Reads GitHub's description of a release. The plugin is only installed when the release states the file's SHA-256
 * (GitHub records it at upload), and, unless [anyHost] (debug builds testing a local copy), when it comes from GitHub.
 */
fun pluginDownload(releaseJson: String, anyHost: Boolean = false): PluginDownload {
    val release = runCatching { Json.parseToJsonElement(releaseJson).jsonObject }.getOrNull() ?: throw ApiException("The slicer's release could not be read.")
    val asset = runCatching { release["assets"]!!.jsonArray.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.contentOrNull == PLUGIN_ASSET } }.getOrNull()
        ?: throw ApiException("The slicer isn't published yet.")
    val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val sha = asset["digest"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("sha256:").lowercase()
    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
    if (!Regex("[0-9a-f]{64}").matches(sha)) throw ApiException("The slicer's release has no checksum; not installing it.")
    if (size !in 1..PLUGIN_MAX_BYTES) throw ApiException("The slicer's release has an unexpected size; not installing it.")
    if (!anyHost && !url.startsWith("https://github.com/gccammelli/pandy-slicer-plugin/releases/download/")) throw ApiException("The slicer's release points outside GitHub; not installing it.")
    return PluginDownload(url, sha, size, release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty())
}

/** Shown on the Studio tab until the slicer plugin is installed. */
@Composable
fun GetSlicer() {
    val ui = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var handed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Gap(20.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                T("Studio", 26.sp, weight = FontWeight.Bold)
                T("Slice your own models on this phone", 14.sp, C.Sub)
            }
        }
        Gap(24.dp)
        Card {
            T("Get the slicer", 18.sp, weight = FontWeight.Bold)
            Gap(6.dp)
            T("Studio opens your STL and 3MF files, lets you arrange them and choose the print settings, then slices and " +
                "prints them on your printer, without a computer.", 14.sp, C.Sub, lh = 20.sp)
            Gap(10.dp)
            T("Slicing is done by Bambu Studio's own engine, which Pandy installs as a separate add-on (about 90 MB). " +
                "It is free software under the AGPL licence, published with its source; Pandy itself stays MIT.", 14.sp, C.Sub, lh = 20.sp)
            Gap(16.dp)
            if (busy) {
                val p = progress
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Track)
                else LinearProgressIndicator({ p }, Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Track)
                Gap(8.dp)
                T(if (p == null) "Checking…" else "Downloading… ${(p * 100).toInt()}%", 13.sp, C.Sub)
            } else AccentButton(if (handed) "Install again" else "Download and install") {
                busy = true; error = null; progress = null
                scope.launch {
                    try { ui.installSlicerPlugin { progress = it }; handed = true }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { error = failureText(e) }
                    finally { busy = false }
                }
            }
            if (handed && !busy) { Gap(8.dp); T("Confirm the installation when Android asks. Studio opens here as soon as it is done.", 13.sp, C.Sub, lh = 18.sp) }
            error?.let { Gap(8.dp); T(it, 13.sp, C.Red, lh = 18.sp) }
        }
        Gap(12.dp)
        SetupNote("How Pandy slices on the phone, and how the slicer is tested against Bambu Studio.", "docs/studio.md")
        Gap(28.dp)
    }
}
