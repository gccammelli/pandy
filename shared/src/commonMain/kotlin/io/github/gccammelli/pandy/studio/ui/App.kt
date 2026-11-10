// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.WebDownload
import io.github.gccammelli.pandy.printerPreset
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.studio.studio.requirePlainEditorModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

/** Studio's screens, as a back stack inside the Studio tab. */
sealed class StudioScreen {
    data object Home : StudioScreen()
    data object Plate : StudioScreen()
    data class Settings(val page: Int = 0) : StudioScreen()
    data object About : StudioScreen()
}

class StudioNav {
    val stack = mutableStateListOf<StudioScreen>(StudioScreen.Home)
    val current get() = stack.last()
    fun go(s: StudioScreen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

/** A sliced plate, ready for the printer: the file, the printer preset it was sliced for and the filaments it uses. */
class StudioPrint(val file: ByteArray, val name: String, val printer: String, val filaments: List<Map<String, String>>)

/** The Studio tab's state, kept while the user is on Pandy's other tabs: the plate, where they were, files to open. */
object StudioSession {
    val nav = StudioNav()
    var studio by mutableStateOf<Studio?>(null); private set
    /** Models another app handed to Pandy, waiting for the slicer's profiles (or the slicer itself). */
    val pending = mutableStateListOf<Pair<String, ByteArray>>()
    /** The sliced plate the print sheet is showing. */
    var print by mutableStateOf<StudioPrint?>(null)
    var notice by mutableStateOf<String?>(null)
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /** The plate editor is open: it takes the whole screen, without Pandy's tab bar. */
    val fullScreen get() = nav.stack.size > 1

    /** [preferredPrinter]: the connected printer's preset, chosen the first time. */
    fun start(platform: Platform, preferredPrinter: String?, scope: CoroutineScope = this.scope): Studio =
        studio ?: Studio(platform, scope).also { studio = it; it.load(preferredPrinter) }

    /**
     * A file opened with Pandy. A model goes to Studio's plate; a 3MF project (plates, profiles: what the editor can't
     * keep) goes to the print sheet MakerWorld models use. False for anything else.
     */
    fun open(name: String, bytes: ByteArray, project: Boolean = isProject(name, bytes)): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".stl") && !lower.endsWith(".3mf")) return false
        Nav.stack.clear()
        if (project) {
            Nav.tab = 0
            debugDownload.value = WebDownload(null, name, null, bytes, printSheet = true)
        } else {
            Nav.tab = STUDIO_TAB
            pending += name to bytes
        }
        return true
    }

    /** Whether a file is a 3MF project rather than a plain model. Unpacks the file: call it off the main thread for files from other apps. */
    fun isProject(name: String, bytes: ByteArray) = name.lowercase().endsWith(".3mf") && runCatching { requirePlainEditorModel(bytes) }.isFailure

    /** Puts the waiting files on the plate, once the profiles are loaded. */
    fun takePending() {
        val s = studio?.takeIf { it.library != null } ?: return
        if (pending.isEmpty()) return
        val files = pending.toList(); pending.clear()
        files.forEach { (name, bytes) -> runCatching { s.addFile(name, bytes) }.onFailure { notice = "Couldn't open $name: ${it.message ?: it}" } }
        if (s.scene.objects.isNotEmpty() && nav.stack.none { it == StudioScreen.Plate }) { nav.stack.clear(); nav.stack += listOf(StudioScreen.Home, StudioScreen.Plate) }
    }

    /** Back inside the tab; false at its first screen. */
    fun back(): Boolean = nav.back()

    fun reset() { studio = null; pending.clear(); print = null; notice = null; nav.stack.clear(); nav.stack += StudioScreen.Home }
}

/** Lets the user pick models and puts them on the plate; a file that can't be opened is reported, the rest still open. */
suspend fun pickModels(platform: Platform, studio: Studio): Boolean {
    val files = runCatching { platform.pickModels() }.getOrElse { StudioSession.notice = it.message ?: it.toString(); return false }
    if (files.isNotEmpty()) StudioSession.notice = null
    var added = false
    files.forEach { (name, bytes) -> runCatching { studio.addFile(name, bytes); added = true }.onFailure { StudioSession.notice = "Couldn't open $name: ${it.message ?: it}" } }
    return added
}

/** A shared file's name with the extension Studio goes by: its own, else what the content is (a 3MF is a ZIP archive). */
fun sharedModelName(name: String, bytes: ByteArray): String = when {
    name.lowercase().let { it.endsWith(".stl") || it.endsWith(".3mf") } -> name
    name.contains('.') && !name.lowercase().endsWith(".bin") -> name
    bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() -> "$name.3mf"
    else -> "$name.stl"
}

/** Studio's place in Pandy's bottom bar. */
const val STUDIO_TAB = 3

/** The Studio tab: the plate editor when the slicer plugin is installed, else how to get it. */
@Composable
fun StudioTab(p: Printer) {
    val ui = io.github.gccammelli.pandy.LocalPlatform.current
    val host = ui.studio ?: return
    var installed by remember { mutableStateOf(ui.phoneSlicerAvailable()) }
    if (!installed) {
        LaunchedEffect(Unit) { while (!ui.phoneSlicerAvailable()) delay(1000); installed = true }
        GetSlicer()
        return
    }
    val studio = remember { StudioSession.start(host, printerPreset(p.model, p.state("sensor.nozzle_diameter"))) }
    val nav = StudioSession.nav
    LaunchedEffect(studio.library, StudioSession.pending.size) { StudioSession.takePending() }
    CompositionLocalProvider(LocalPlatform provides host) {
        AnimatedContent(nav.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "studio") { s ->
            when (s) {
                StudioScreen.Home -> Home(studio, nav)
                StudioScreen.Plate -> PlateScreen(studio, nav)
                is StudioScreen.Settings -> SettingsScreen(studio, nav, s.page)
                StudioScreen.About -> AboutScreen(studio, nav)
            }
        }
    }
    StudioSession.print?.let { StudioPrintSheet(it, p) { StudioSession.print = null } }
}
