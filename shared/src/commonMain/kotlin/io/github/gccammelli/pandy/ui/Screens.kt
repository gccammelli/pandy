package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.amsId
import io.github.gccammelli.pandy.isSingleSlot
import io.github.gccammelli.pandy.calibrate
import io.github.gccammelli.pandy.decodeImage
import io.github.gccammelli.pandy.load
import io.github.gccammelli.pandy.setAmsSettings
import io.github.gccammelli.pandy.setPrintOption
import io.github.gccammelli.pandy.setXcam
import io.github.gccammelli.pandy.skip
import io.github.gccammelli.pandy.toggleCamera
import io.github.gccammelli.pandy.unload

// ---------- Skip parts ----------

/**
 * Skip Parts: the plate picture (tap a part to pick it) and the list of parts. Picking uses the integration's "pick"
 * image, where each part is painted in a colour that encodes its id (red + green·256 + blue·65536).
 */
@Composable
fun SkipScreen(p: Printer) {
    val err = errorToast()
    val chosen = remember { mutableStateListOf<Int>() }
    var confirm by remember { mutableStateOf(false) }
    val objects = p.objects
    val skipped = p.skipped
    val pick by produceState<ImageBitmap?>(null, p.pickImage) { value = p.pickImage?.let { runCatching { decodeImage(Repo.ha!!.bytes(it), 512) }.getOrNull() } }
    val cover by produceState<ImageBitmap?>(null, p.coverImage) { value = p.coverImage?.let { runCatching { decodeImage(Repo.ha!!.bytes(it), 512) }.getOrNull() } }
    Page("Skip Parts", { Nav.back() }, bottom = {
        Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("${chosen.size}/${objects.size - skipped.size} Selected", 12.sp, C.Sub)
                Row(Modifier.clickable {
                    val left = objects.keys.filter { it !in skipped }
                    if (chosen.size == left.size) chosen.clear() else { chosen.clear(); chosen.addAll(left) }
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckDot(chosen.isNotEmpty() && chosen.size == objects.size - skipped.size)
                    Spacer(Modifier.width(6.dp)); T("Select All", 14.sp)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (chosen.isEmpty()) C.Track else C.Accent).clickable(enabled = chosen.isNotEmpty()) { confirm = true }
                .padding(horizontal = 24.dp, vertical = 10.dp)) { T("Skip", 15.sp, if (chosen.isEmpty()) C.Sub else C.OnAccent, FontWeight.Medium) }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(C.Fill)
                .pointerInput(pick, skipped) {
                    detectTapGestures { o ->
                        val img = pick ?: return@detectTapGestures
                        val x = (o.x / size.width * img.width).toInt().coerceIn(0, img.width - 1)
                        val y = (o.y / size.height * img.height).toInt().coerceIn(0, img.height - 1)
                        val px = IntArray(1); img.readPixels(px, x, y, 1, 1)
                        val c = px[0]
                        if ((c ushr 24) == 0) return@detectTapGestures
                        val id = ((c shr 16) and 0xFF) or (((c shr 8) and 0xFF) shl 8) or ((c and 0xFF) shl 16)
                        if (id in objects && id !in skipped) { if (id in chosen) chosen.remove(id) else chosen.add(id) }
                    }
                }) {
                cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                // Picked parts get the accent over them; skipped ones are greyed out.
                val img = pick
                if (img != null) {
                    val acc = C.Accent
                    val px = remember(img) { IntArray(img.width * img.height).also { img.readPixels(it) } }
                    Canvas(Modifier.fillMaxSize()) {
                        val sx = size.width / img.width; val sy = size.height / img.height
                        val step = 2
                        for (y in 0 until img.height step step) for (x in 0 until img.width step step) {
                            val c = px[y * img.width + x]
                            if ((c ushr 24) == 0) continue
                            val id = ((c shr 16) and 0xFF) or (((c shr 8) and 0xFF) shl 8) or ((c and 0xFF) shl 16)
                            val col = when (id) { in chosen -> acc.copy(alpha = .55f); in skipped -> Color(0xAA888888); else -> continue }
                            drawRect(col, androidx.compose.ui.geometry.Offset(x * sx, y * sy), androidx.compose.ui.geometry.Size(sx * step, sy * step))
                        }
                    }
                }
                T("Front", 11.sp, C.Sub, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
            }
            T("Parts on the plate (${objects.size})", 14.sp, C.Sub, modifier = Modifier.padding(horizontal = 16.dp))
            Gap(8.dp)
            objects.forEach { (id, name) ->
                val done = id in skipped
                Row(Modifier.fillMaxWidth().clickable(enabled = !done) { if (id in chosen) chosen.remove(id) else chosen.add(id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CheckDot(id in chosen, disabled = done)
                    Spacer(Modifier.width(12.dp))
                    T(name.removeSuffix(".stl").removeSuffix(".STL"), 15.sp, if (done) C.Faint else C.Text, modifier = Modifier.weight(1f))
                    if (done) T("Skipped", 12.sp, C.Faint)
                }
            }
            Gap(16.dp)
        }
    }
    if (confirm) AlertDialog({ confirm = false }, containerColor = C.Card,
        title = { T("Skip ${chosen.size} part${if (chosen.size == 1) "" else "s"}?", 17.sp, weight = FontWeight.Bold) },
        text = { T("Skipped parts can't be printed again in this job.", 14.sp, C.Sub) },
        confirmButton = { TextButton({ confirm = false; p.skip(chosen.toList(), err); chosen.clear() }) { T("Skip", 15.sp, C.Accent, FontWeight.Medium) } },
        dismissButton = { TextButton({ confirm = false }) { T("Cancel", 15.sp, C.Sub) } })
}

@Composable
fun CheckDot(on: Boolean, disabled: Boolean = false) =
    Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) C.Accent else Color.Transparent)
        .border(1.5.dp, if (on) C.Accent else if (disabled) C.Line else C.Faint, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = C.OnAccent)
    }

// ---------- Filament ----------

/** Filament: each AMS with its humidity and temperature and four slots; tap a slot for its details, load and unload. */
@Composable
fun FilamentScreen(p: Printer) {
    val err = errorToast()
    var open by remember { mutableStateOf<io.github.gccammelli.pandy.Spool?>(null) }
    Page("Filament", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            p.ams.forEach { (a, trays) ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (a.isSingleSlot()) "AMS HT" else "AMS ${'A' + a.index - 1}", 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        p.amsNum(a, "sensor.humidity_index")?.let { h ->
                            Icon(Icons.Outlined.WaterDrop, null, Modifier.size(16.dp), tint = humidityColor(h.toInt()))
                            T(" ${h.toInt()}", 13.sp, C.Sub)
                        }
                        p.amsNum(a, "sensor.humidity")?.let { T("  ${it.toInt()}%", 13.sp, C.Sub) }
                        p.amsNum(a, "sensor.ams_temp")?.let { T("  ${it.toInt()}°C", 13.sp, C.Sub) }
                    }
                    Gap(12.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        trays.forEachIndexed { i, s ->
                            Box(Modifier.weight(1f).clickable(enabled = s != null) { open = s }) { SlotTile(s, "${'A' + a.index - 1}${i + 1}", Modifier.fillMaxWidth()) }
                        }
                    }
                    Gap(10.dp)
                    T("Humidity level 1 (dry) to 5 (wet). Dry the filament above 3.", 12.sp, C.Sub)
                }
                Gap(12.dp)
            }
            if (p.external.isNotEmpty()) Card {
                T("External Spool", 16.sp, weight = FontWeight.Bold)
                Gap(12.dp)
                p.external.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { open = s }, verticalAlignment = Alignment.CenterVertically) {
                        SlotTile(s, "Ext", Modifier.width(64.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            T(if (s.known) s.name else "Not set", 15.sp)
                            T(if (s.active) "In use" else s.type, 12.sp, if (s.active) C.Accent else C.Sub)
                        }
                    }
                }
            }
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Unload", C.Text, Modifier.weight(1f)) { if (p.printing) err("Can't unload while printing") else p.unload(err) }
            }
        }
    }
    open?.let { s ->
        Sheet({ open = null }, s.name.ifBlank { "Empty slot" }, centeredTitle = true, confirm = if (p.printing || !s.known) null else "Load", onConfirm = { p.load(s, err); open = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(s.color)))
                Spacer(Modifier.width(16.dp))
                Column {
                    InfoLine("Type", s.type.ifBlank { "--" })
                    InfoLine("Nozzle", if (s.nozzleMin != null) "${s.nozzleMin}–${s.nozzleMax}°C" else "--")
                    InfoLine("Remaining", if (s.remain >= 0) "${s.remain}%" else "Unknown")
                }
            }
            if (s.active) { Gap(10.dp); T("This filament is loaded in the nozzle.", 13.sp, C.Accent) }
            Gap(8.dp)
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) = Row(Modifier.padding(vertical = 2.dp)) {
    T("$label  ", 13.sp, C.Sub); T(value, 13.sp)
}

// ---------- Printer settings ----------

/** Handy's printer Settings list, with direct-LAN actions where the printer protocol is known. */
@Composable
fun PrinterSettingsScreen(p: Printer) {
    val err = errorToast()
    var delete by remember { mutableStateOf(false) }
    Page("Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            Group {
                NavRow("Machine Assistant", value = p.hms.size.takeIf { it > 0 }?.let { "$it" }) { Nav.go(Screen.Assistant) }
                if (Repo.direct || Repo.demo) NavRow("Calibration") { Nav.go(Screen.Calibration) }
                else if (Repo.showUnavailable) NavRow("Calibration", value = "Not available", labelColor = C.Sub, chevron = false)
                if (Repo.direct || Repo.demo) NavRow("Print Options") { Nav.go(Screen.PrintOptions) }
                else if (Repo.showUnavailable) NavRow("Print Options", value = "Not available", labelColor = C.Sub, chevron = false)
                if (p.ams.isNotEmpty() && (Repo.direct || Repo.demo)) NavRow("AMS Settings") { Nav.go(Screen.AmsSettings) }
                else if (p.ams.isNotEmpty() && Repo.showUnavailable) NavRow("AMS Settings", value = "Not available", labelColor = C.Sub, chevron = false)
                NavRow("Liveview Settings") { Nav.go(Screen.LiveviewSettings) }
            }
            Gap(12.dp)
            Group { NavRow("Files") { Nav.go(Screen.Files) } }
            Gap(12.dp)
            Group {
                NavRow("Device Name", value = p.name, chevron = false)
                NavRow("Firmware Version", value = (p.info.firmware ?: "--") + if (p["binary_sensor.firmware_update"]?.on == true) "  •  Update available" else "", chevron = false)
            }
            Gap(12.dp)
            if (Repo.direct) Group { NavRow("Disconnect printer", labelColor = C.Red, chevron = false) { delete = true } }
            Gap(12.dp)
            T("${p.model}  •  ${p.serial}", 12.sp, C.Sub, modifier = Modifier.padding(horizontal = 6.dp))
            T("Connection: ${if (Repo.direct) "LAN" else if (Repo.demo) "Demo" else "Home Assistant"}", 12.sp, C.Sub, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { T("Disconnect printer?", weight = FontWeight.Medium) },
        text = { T("This removes the saved direct-LAN connection from Pandy. It does not reset or change the printer, Home Assistant, or slicer.", 14.sp, C.Sub) },
        confirmButton = { TextButton(onClick = { delete = false; Nav.stack.clear(); Repo.removeDirectPrinter() }) { T("Disconnect", color = C.Red) } },
        dismissButton = { TextButton(onClick = { delete = false }) { T("Cancel", color = C.Text) } },
    )
}

/** Starts the printer's machine calibration with the four documented option bits. */
@Composable
fun CalibrationScreen(p: Printer) {
    val err = errorToast()
    var confirm by remember { mutableStateOf(false) }
    var lidar by remember { mutableStateOf(p.model.startsWith("X1") || p.model.startsWith("H2")) }
    var bed by remember { mutableStateOf(true) }
    var vibration by remember { mutableStateOf(true) }
    var motor by remember { mutableStateOf(false) }
    val canSend = (Repo.direct || Repo.demo) && p.online
    val option = (if (lidar) 1 else 0) or (if (bed) 2 else 0) or (if (vibration) 4 else 0) or (if (motor) 8 else 0)
    Page("Calibration", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            T("Choose the checks to run on the printer. Calibration can move the toolhead and heat the bed.", 14.sp, C.Sub, lh = 19.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Gap(12.dp)
            Group {
                if (p.model.startsWith("X1") || p.model.startsWith("H2")) ToggleRow("Micro lidar calibration", lidar, enabled = canSend && !p.printing) { lidar = it }
                ToggleRow("Bed leveling", bed, enabled = canSend && !p.printing) { bed = it }
                ToggleRow("Vibration compensation", vibration, enabled = canSend && !p.printing) { vibration = it }
                ToggleRow("Motor noise cancellation", motor, enabled = canSend && !p.printing) { motor = it }
            }
            Gap(16.dp)
            if (!Repo.direct && !Repo.demo) T("Home Assistant does not expose this printer command. Connect directly to use it.", 13.sp, C.Sub, lh = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))
            if (p.printing) T("Stop the current print before starting calibration.", 13.sp, C.Orange, modifier = Modifier.padding(horizontal = 6.dp))
            Gap(10.dp)
            AccentButton("Start calibration", enabled = canSend && !p.printing && option != 0) { confirm = true }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { T("Start calibration?", weight = FontWeight.Medium) },
        text = { T("The printer may move the toolhead and heat the bed. Make sure it is clear before continuing.", 14.sp, C.Sub) },
        confirmButton = { TextButton(onClick = { confirm = false; p.calibrate(option, err) }) { T("Start", color = C.Accent) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { T("Cancel", color = C.Text) } },
    )
}

/** Print options that are represented by verified XCam/print-option LAN commands. */
@Composable
fun PrintOptionsScreen(p: Printer) {
    val err = errorToast()
    val canSend = (Repo.direct || Repo.demo) && p.online
    Page("Print Options", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            T("These settings are stored by the printer. Some options are model- or firmware-specific.", 14.sp, C.Sub, lh = 19.sp, modifier = Modifier.padding(horizontal = 6.dp))
            Gap(12.dp)
            val hasReportedOptions = listOf("printing_monitor", "buildplate_marker_detector", "first_layer_inspector", "auto_recovery").any { p.flag(it) != null }
            if (Repo.showUnavailable || canSend && hasReportedOptions) Group {
                    PrinterFlagToggle(p, "AI Print Monitoring", "printing_monitor", canSend) { next, revert -> p.setXcam("printing_monitor", next, p.flagAttribute("printing_monitor", "print_halt") ?: false) { message -> revert(); err(message) } }
                    PrinterFlagToggle(p, "Build Plate Detection", "buildplate_marker_detector", canSend) { next, revert -> p.setXcam("buildplate_marker_detector", next, p.flagAttribute("buildplate_marker_detector", "print_halt") ?: false) { message -> revert(); err(message) } }
                    PrinterFlagToggle(p, "First Layer Inspection", "first_layer_inspector", canSend) { next, revert -> p.setXcam("first_layer_inspector", next, p.flagAttribute("first_layer_inspector", "print_halt") ?: false) { message -> revert(); err(message) } }
                    PrinterFlagToggle(p, "Auto-Recover from Step Loss", "auto_recovery", canSend) { next, revert -> p.setPrintOption("auto_recovery", next) { message -> revert(); err(message) } }
                }
            Gap(12.dp)
            if (Repo.showUnavailable) Group {
                NavRow("Open Door Detection", value = "Not available", labelColor = C.Sub, chevron = false)
                NavRow("Store Sent Files on External Storage", value = "Not available", labelColor = C.Sub, chevron = false)
            }
            Gap(12.dp)
            T(if (!canSend) "Home Assistant does not expose these printer settings; direct LAN or demo mode is required." else "Only switches reported by this printer are enabled. Unsupported firmware options stay read-only.", 12.sp, C.Sub, lh = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun PrinterFlagToggle(p: Printer, label: String, key: String, enabled: Boolean, onChange: (Boolean, () -> Unit) -> Unit) {
    val remote = p.flag(key)
    if (remote == null) {
        if (Repo.showUnavailable) NavRow(label, value = "Not available", labelColor = C.Sub, chevron = false)
    } else {
        var value by remember(remote) { mutableStateOf(remote) }
        ToggleRow(label, value, enabled = enabled) { next ->
            val old = value
            value = next
            onChange(next) { value = old }
        }
    }
}

/** AMS RFID/remaining-filament preferences, one card per connected AMS unit. */
@Composable
fun AmsSettingsScreen(p: Printer) {
    val err = errorToast()
    Page("AMS Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            if (p.info.ams.isEmpty()) Empty("No AMS connected") else {
                p.info.ams.forEach { ams -> AmsSettingsCard(p, ams, err); Gap(12.dp) }
                val canSend = (Repo.direct || Repo.demo) && p.online
                val autoRefill = p.flag("auto_switch_filament")
                if (Repo.showUnavailable || canSend && autoRefill != null) Group {
                    if (autoRefill == null) {
                        NavRow("AMS Auto-Refill", value = "Not available", labelColor = C.Sub, chevron = false)
                    } else PrinterFlagToggle(p, "AMS Auto-Refill", "auto_switch_filament", canSend) { next, revert -> p.setPrintOption("auto_switch_filament", next) { message -> revert(); err(message) } }
                }
                Gap(12.dp)
                T(if (!Repo.direct && !Repo.demo) "Home Assistant shows AMS state but does not expose these printer settings."
                    else "Changes are sent to the printer and will appear again after its next status report.",
                    12.sp, C.Sub, lh = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun AmsSettingsCard(p: Printer, ams: io.github.gccammelli.pandy.AmsInfo, err: (String) -> Unit) {
    val startupRemote = p.amsFlag(ams, "ams_read_on_startup")
    val insertionRemote = p.amsFlag(ams, "ams_read_on_insertion")
    val remainingRemote = p.amsFlag(ams, "ams_remaining_estimation")
    var startup by remember(ams.haId, startupRemote) { mutableStateOf(startupRemote ?: false) }
    var insertion by remember(ams.haId, insertionRemote) { mutableStateOf(insertionRemote ?: false) }
    var remaining by remember(ams.haId, remainingRemote) { mutableStateOf(remainingRemote ?: false) }
    val settingsKnown = startupRemote != null && insertionRemote != null && remainingRemote != null
    val canSend = (Repo.direct || Repo.demo) && p.online && settingsKnown
    fun update(start: Boolean = startup, insert: Boolean = insertion, remain: Boolean = remaining, revert: () -> Unit = {}) {
        if (!settingsKnown) { revert(); return }
        p.setAmsSettings(ams.amsId(), start, insert, remain) { message -> revert(); err(message) }
    }
    T(if (ams.isSingleSlot()) "AMS HT" else "AMS ${'A' + ams.index - 1}", 14.sp, C.Sub, modifier = Modifier.padding(horizontal = 6.dp))
    Gap(8.dp)
    if (Repo.showUnavailable || canSend) Group {
            if (insertionRemote == null) {
                NavRow("Read Filament on Insertion", value = "Not available", labelColor = C.Sub, chevron = false)
            } else ToggleRow("Read Filament on Insertion", insertion, enabled = canSend) { next -> val old = insertion; insertion = next; update(insert = next) { insertion = old } }
            if (startupRemote == null) {
                NavRow("Read Filament on Startup", value = "Not available", labelColor = C.Sub, chevron = false)
            } else ToggleRow("Read Filament on Startup", startup, enabled = canSend) { next -> val old = startup; startup = next; update(start = next) { startup = old } }
            if (remainingRemote == null) {
                NavRow("Remaining Filament Estimation", value = "Not available", labelColor = C.Sub, chevron = false)
            } else ToggleRow("Remaining Filament Estimation", remaining, enabled = canSend) { next -> val old = remaining; remaining = next; update(remain = next) { remaining = old } }
        }
}

/** Handy's two live-view autoplay choices. This preference is local to Pandy, not a printer command. */
@Composable
fun LiveviewSettingsScreen() {
    var mode by remember { mutableStateOf(Repo.setting("liveview_autoplay") ?: "wifi") }
    fun choose(value: String) { mode = value; Repo.setSetting("liveview_autoplay", value) }
    Page("Liveview Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            Group {
                Radio(mode == "always", "Always Autoplay") { choose("always") }
                Radio(mode == "wifi", "Autoplay on Wi-Fi only") { choose("wifi") }
                Radio(mode == "off", "Autoplay off") { choose("off") }
            }
            Gap(12.dp)
            T("Autoplay controls whether Pandy starts the live view when it appears. The printer's camera remains available from the Devices screen.", 12.sp, C.Sub, lh = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

/** White rounded block of rows, as Handy groups settings. */
@Composable
fun Group(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)) { content() }

// ---------- Machine assistant (HMS) ----------

@Composable
fun AssistantScreen(p: Printer) {
    val pl = LocalPlatform.current
    Page("Machine Assistant", { Nav.back() }) {
        val items = buildList {
            p.printError?.let { add(Triple("Print error", it, null as String?)) }
            p.hms.forEach { (code, msg) -> add(Triple(code.removePrefix("HMS_").replace('_', '-'), msg, p["binary_sensor.hms_errors"]?.attrs?.let { a -> a.entries.firstOrNull { it.key.endsWith("-Wiki") && a["${it.key.substringBefore('-')}-Code"]?.toString()?.trim('"') == code }?.value?.toString()?.trim('"') })) }
        }
        if (items.isEmpty()) Empty("No messages", Icons.Outlined.CheckCircle)
        else Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            items.forEach { (code, msg, wiki) ->
                Card(onClick = wiki?.let { { pl.openUrl(it) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = C.Orange)
                        Spacer(Modifier.width(8.dp)); T(code, 13.sp, C.Sub)
                    }
                    Gap(6.dp)
                    T(msg, 15.sp, lh = 21.sp)
                    if (wiki != null) { Gap(6.dp); T("View solution ›", 13.sp, C.Blue) }
                }
                Gap(10.dp)
            }
        }
    }
}

@Composable
fun Empty(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Inbox) =
    Column(Modifier.fillMaxSize().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(72.dp), tint = C.Track)
        Gap(12.dp)
        T(text, 14.sp, C.Sub)
    }
