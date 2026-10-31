package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.Spool
import io.github.gccammelli.pandy.UiState
import io.github.gccammelli.pandy.button
import io.github.gccammelli.pandy.isSingleSlot
import io.github.gccammelli.pandy.light

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesTab(st: UiState, p: Printer) {
    val err = errorToast()
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding()) {
        TopBar(st, p)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (Repo.demo) DemoNote()
            if (p.online && p.controlBlocked) ControlBlockedNote()
            Hero(p)
            Gap(12.dp)
            PrintCard(p, err)
            SectionHead("Device Control") { Nav.go(Screen.Control) }
            NozzleCard(p) { Nav.go(Screen.Control) }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniCard("Motion", "XYZ", Modifier.weight(1f)) { Nav.go(Screen.Motion) }
                LightCard(p, Modifier.weight(1f), err)
            }
            SectionHead("Filament") { Nav.go(Screen.Filament) }
            FilamentCard(p) { Nav.go(Screen.Filament) }
            Gap(20.dp)
        }
    }
}

@Composable
fun DemoNote() = Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.AccentBg).padding(12.dp)) {
    T("Demo: a pretend printer. Every control works on it. To connect yours: Me › Settings › Leave the demo.", 13.sp, C.Text, lh = 18.sp)
}

/**
 * The printer takes commands only from Bambu's apps (Developer Mode off): Pandy can watch it but not control it. Says
 * so once, with the two ways forward.
 */
@Composable
fun ControlBlockedNote() {
    val pl = LocalPlatform.current
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.AccentBg).padding(14.dp)) {
        T("Watching only", 15.sp, weight = FontWeight.Bold)
        Gap(4.dp)
        T("Your printer only takes commands from Bambu's own apps right now, so Pandy can show it but not control it or print.", 13.sp, C.Text, lh = 18.sp)
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(C.Accent).clickable { pl.openApp(io.github.gccammelli.pandy.HANDY_PACKAGE, io.github.gccammelli.pandy.HANDY_PLAY) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) { T("Open Bambu Handy", 14.sp, C.OnAccent, FontWeight.Medium) }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).border(1.dp, C.Accent, RoundedCornerShape(20.dp)).clickable { pl.openUrl(io.github.gccammelli.pandy.guide("docs/setup.md#direct-mode-the-default")) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) { T("Use Pandy instead", 14.sp, C.Accent, FontWeight.Medium) }
        }
        Gap(6.dp)
        T("To use Pandy: on the printer, turn on LAN Only mode and Developer Mode (Settings › Network, or WLAN on P1/A1).", 12.sp, C.Sub, lh = 17.sp)
    }
}

/** A toast for failed actions, as Handy shows them. */
@Composable
fun errorToast(): (String) -> Unit { val pl = LocalPlatform.current; return { pl.toast(it) } }

@Composable
private fun TopBar(st: UiState, p: Printer) {
    var pick by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { pick = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(p.name, 20.sp, weight = FontWeight.Bold)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(22.dp), tint = C.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when {
                    !st.live && !Repo.demo -> "Connecting…" to C.Faint
                    !p.online -> "Offline" to C.Faint
                    p.printing -> "Busy" to C.Orange
                    else -> "Online" to C.Ok
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                T(label, 12.sp, C.Sub)
            }
        }
        IconBox(Icons.Outlined.Settings) { Nav.go(Screen.PrinterSettings) }
    }
    if (pick) Sheet({ pick = false }, "Printers", centeredTitle = true, confirm = null) {
        if (Repo.direct && Repo.directPrinters.isNotEmpty()) {
            Repo.directPrinters.forEach { c ->
                val active = c.serial == Repo.lan?.serial
                Row(Modifier.fillMaxWidth().clickable { pick = false; Repo.switchDirectPrinter(c.serial) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(c.name.ifBlank { c.model }, 16.sp, if (active) C.Accent else C.Text, FontWeight.Medium, Modifier.weight(1f))
                    T(if (!active) "Unknown" else if (!st.live) "Connecting…" else if (p.online) p.status.replaceFirstChar { it.uppercase() } else "Offline", 13.sp, C.Sub)
                }
            }
        } else {
            st.printers.orEmpty().forEach { o ->
                Row(Modifier.fillMaxWidth().clickable { Repo.selected = o.serial; pick = false; Repo.restart() }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(o.name, 16.sp, if (o.serial == p.serial) C.Accent else C.Text, FontWeight.Medium, Modifier.weight(1f))
                    T(if (o.online) o.status.replaceFirstChar { it.uppercase() } else "Offline", 13.sp, C.Sub)
                }
            }
        }
        if (Repo.direct) T("Add printer", 16.sp, C.Accent, modifier = Modifier.fillMaxWidth().clickable { pick = false; Nav.go(Screen.AddDirect) }.padding(vertical = 14.dp))
        Gap(16.dp)
    }
}

@Composable
fun IconBox(icon: ImageVector, onClick: () -> Unit) = Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, null, Modifier.size(24.dp), tint = C.Text)
}

/** Live view and the printer picture with its temperatures, swiped between as in Handy (live view first while printing). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(p: Printer) {
    val pages = if (p.camera == null) listOf("printer") else if (p.printing) listOf("live", "printer") else listOf("printer", "live")
    // A fresh pager when printing starts or stops, so it opens on the page that leads (live view while printing).
    val pager = androidx.compose.runtime.key(p.printing) { rememberPagerState { pages.size } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(16 / 10f), key = { pages[it] }) { i ->
            when (pages[i]) {
                "live" -> LiveView(p, Modifier.fillMaxSize(), onFull = { Nav.go(Screen.LiveFull) })
                else -> PrinterPage(p)
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 2.dp).size(width = if (pager.currentPage == i) 12.dp else 5.dp, height = 3.dp).clip(CircleShape)
                    .background(if (pager.currentPage == i) C.Accent else C.Track))
            }
        }
    }
}

@Composable
private fun PrinterPage(p: Printer) = Box(Modifier.fillMaxSize().background(C.Card).padding(10.dp)) {
    val amsColors = p.ams.firstOrNull()?.second.orEmpty().map { Color(if (it?.known == true) it.color else 0xFFDDDDDD) }
    PrinterArt(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 4.dp), ams = p.info.ams.isNotEmpty(), lit = p.lightOn, amsColors = amsColors)
    TempChip(Icons.Outlined.Settings, "nozzle", p.nozzle, p.nozzleTarget, Modifier.align(Alignment.TopStart))
    if (p.chamber != null) TempChip(null, "chamber", p.chamber, p.chamberTarget, Modifier.align(Alignment.TopEnd))
    TempChip(null, "bed", p.bed, p.bedTarget, Modifier.align(Alignment.BottomStart))
    p.ams.firstOrNull()?.let { (a, _) ->
        p.amsNum(a, "sensor.humidity_index")?.let { h ->
            Row(Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(C.Fill).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WaterDrop, null, Modifier.size(14.dp), tint = humidityColor(h.toInt()))
                Spacer(Modifier.width(4.dp)); T("${h.toInt()}", 13.sp, weight = FontWeight.Bold)
            }
        }
    }
}

fun humidityColor(level: Int) = when { level <= 2 -> Color(0xFF00A33E); level == 3 -> Color(0xFFFFB020); else -> Color(0xFFE5484D) }

/** "28 /0°C" with a small symbol for which heater; an arrow while heating (orange) or cooling (blue). */
@Composable
private fun TempChip(icon: ImageVector?, which: String, now: Double?, target: Double?, modifier: Modifier) =
    Row(modifier.clip(CircleShape).background(C.Fill).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaterGlyph(which, Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        TempText(now, target, 14.sp, 10.sp)
    }

@Composable
fun HeaterGlyph(which: String, modifier: Modifier) {
    val c = C.Sub
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (which) {
            "nozzle" -> { drawRect(c, androidx.compose.ui.geometry.Offset(w * .2f, 0f), androidx.compose.ui.geometry.Size(w * .6f, h * .6f))
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * .3f, h * .6f); lineTo(w * .7f, h * .6f); lineTo(w * .5f, h); close() }, c) }
            "bed" -> { for (i in 0..2) drawLine(c, androidx.compose.ui.geometry.Offset(w * (.25f + i * .25f), 0f), androidx.compose.ui.geometry.Offset(w * (.25f + i * .25f), h * .65f), 2f)
                drawRect(c, androidx.compose.ui.geometry.Offset(0f, h * .75f), androidx.compose.ui.geometry.Size(w, h * .2f)) }
            else -> { drawRect(c, androidx.compose.ui.geometry.Offset(w * .05f, h * .05f), androidx.compose.ui.geometry.Size(w * .9f, h * .9f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                drawRect(c, androidx.compose.ui.geometry.Offset(w * .25f, h * .55f), androidx.compose.ui.geometry.Size(w * .5f, h * .25f)) }
        }
    }
}

@Composable
fun TempText(now: Double?, target: Double?, big: androidx.compose.ui.unit.TextUnit, small: androidx.compose.ui.unit.TextUnit) =
    Row(verticalAlignment = Alignment.Bottom) {
        val n = now?.toInt()
        val t = target?.toInt() ?: 0
        val heating = n != null && t > 0 && n < t - 2
        val cooling = n != null && t > 0 && n > t + 2
        T(n?.toString() ?: "--", big, if (cooling) C.Blue else if (heating) C.Orange else C.Text, FontWeight.Bold)
        if (heating || cooling) T(if (heating) "↑" else "↓", small, if (heating) C.Orange else C.Blue, FontWeight.Bold)
        T(" /$t°C", small, C.Sub)
    }

@Composable
private fun SectionHead(title: String, more: () -> Unit) = Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    T(title, 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
    Row(Modifier.clickable(onClick = more), verticalAlignment = Alignment.CenterVertically) {
        T("More", 13.sp, C.Sub); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = C.Sub)
    }
}

// ---------- Print card ----------

/** Handy's job card: the plate picture, name, % and time left, the stage, and Skip / Pause / Stop. */
@Composable
private fun PrintCard(p: Printer, err: (String) -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    Card(padding = androidx.compose.foundation.layout.PaddingValues(12.dp), onClick = if (p.printing) null else { { Nav.go(Screen.History) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
                if (p.printing || p.status == "finish") HaImage(p.coverImage, Modifier.fillMaxSize().padding(4.dp))
                else PrinterArt(Modifier.fillMaxSize().padding(8.dp), ams = false, lit = false)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    p.printing -> {
                        T(p.task ?: "Printing", 15.sp, weight = FontWeight.Medium, maxLines = 1)
                        Gap(4.dp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            T("${p.progress}", 20.sp, weight = FontWeight.Bold); T("%", 12.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp))
                            Spacer(Modifier.weight(1f))
                            T(if (p.paused) "Paused" else "About ${minutes(p.remaining)} left", 12.sp, C.Sub)
                        }
                        Gap(4.dp)
                        Bar(p.progress / 100f, C.Accent)
                        Gap(6.dp)
                        T(listOfNotNull(stageText(p.stage), p.layer?.let { l -> "Layer $l/${p.layers ?: "?"}" }).joinToString(" · "), 12.sp, C.Sub, maxLines = 1)
                    }
                    p.status == "finish" -> { T(p.task ?: "Print", 15.sp, weight = FontWeight.Medium, maxLines = 1); Gap(4.dp); T("Printing completed", 13.sp, C.Ok) }
                    p.status == "failed" -> { T(p.task ?: "Print", 15.sp, weight = FontWeight.Medium, maxLines = 1); Gap(4.dp); T("Printing stopped", 13.sp, C.Red) }
                    !p.online -> { T("Device Offline", 15.sp, weight = FontWeight.Medium); Gap(4.dp); T("Check the printer's power and network", 13.sp, C.Sub) }
                    else -> { T("Device Idle", 15.sp, weight = FontWeight.Medium); Gap(4.dp); T("Start printing Now!", 13.sp, C.Sub) }
                }
            }
            if (!p.printing) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Faint)
        }
        if (p.printing) {
            Gap(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.objects.size > 1) JobButton(Icons.Outlined.ContentCut, "Skip", C.Text) { Nav.go(Screen.Skip) }
                if (p.paused) JobButton(Icons.Default.PlayArrow, "Resume", C.Text) { p.button("resume", err) }
                else JobButton(Icons.Default.Pause, "Pause", C.Text) { p.button("pause", err) }
                JobButton(Icons.Default.Stop, "Stop", C.Red) { confirmStop = true }
            }
        }
    }
    if (confirmStop) AlertDialog({ confirmStop = false }, containerColor = C.Card,
        title = { T("Stop printing?", 17.sp, weight = FontWeight.Bold) },
        text = { T("The print can't be resumed after it's stopped.", 14.sp, C.Sub) },
        confirmButton = { TextButton({ confirmStop = false; p.button("stop", err) }) { T("Stop", 15.sp, C.Red, FontWeight.Medium) } },
        dismissButton = { TextButton({ confirmStop = false }) { T("Cancel", 15.sp, C.Sub) } })
}

@Composable
private fun RowScope.JobButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) =
    Row(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint); Spacer(Modifier.width(4.dp)); T(label, 13.sp, C.Text)
    }

/** Minutes → "1h 23min" / "56min". */
fun minutes(m: Double?): String {
    val t = m?.toInt() ?: return "--"
    return if (t >= 60) "${t / 60}h ${t % 60}min" else "${t}min"
}

/** The integration's stage keys as Handy words them. */
fun stageText(s: String?): String? = when (s) {
    null, "idle", "offline", "unknown" -> null
    "printing" -> "Printing"
    "auto_bed_leveling" -> "Auto bed leveling"
    "heatbed_preheating" -> "Heatbed preheating"
    "heating_hotend" -> "Heating hotend"
    "calibrating_extrusion", "calibrating_extrusion_flow" -> "Calibrating extrusion"
    "scanning_bed_surface" -> "Scanning bed surface"
    "inspecting_first_layer" -> "Inspecting first layer"
    "identifying_build_plate_type" -> "Identifying build plate type"
    "cleaning_nozzle_tip" -> "Cleaning nozzle tip"
    "homing_toolhead" -> "Homing toolhead"
    "changing_filament" -> "Changing filament"
    "paused", "paused_user" -> "Paused"
    else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

// ---------- Device control cards ----------

@Composable
fun NozzleCard(p: Printer, modifier: Modifier = Modifier, onClick: () -> Unit) = Card(modifier = modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Nozzle & Extruder", 13.sp, C.Sub, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = C.Faint)
    }
    Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(p.nozzle?.toInt()?.toString() ?: "--", 26.sp, tempColor(p.nozzle, p.nozzleTarget), FontWeight.Bold)
                T("/${p.nozzleTarget?.toInt() ?: 0}°C", 11.sp, C.Sub)
            }
        }
        ToolheadArt(Modifier.size(56.dp, 84.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            p.state("sensor.nozzle_diameter")?.let { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T("$d mm", 15.sp, weight = FontWeight.Bold)
                    T(nozzleType(p.state("sensor.nozzle_type")), 11.sp, C.Sub)
                }
            }
        }
    }
}

@Composable
fun tempColor(now: Double?, target: Double?): Color {
    val n = now ?: return C.Text
    val t = target ?: 0.0
    return if (t > 0 && n < t - 2) C.Orange else if (t > 0 && n > t + 2) C.Blue else C.Text
}

fun nozzleType(t: String?) = when (t) {
    "hardened_steel" -> "Hardened steel"; "stainless_steel" -> "Stainless steel"; "brass" -> "Brass"; "tungsten_carbide" -> "Tungsten carbide"
    "high_flow_hardened_steel" -> "High flow hardened steel"; "high_flow_stainless_steel" -> "High flow stainless steel"
    null -> ""; else -> t.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun MiniCard(label: String, value: String, modifier: Modifier, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?) =
    Row(modifier.height(72.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(label, 13.sp, C.Sub)
            Gap(4.dp)
            T(value, 16.sp, weight = FontWeight.Bold, maxLines = 1)
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = C.Faint)
    }

@Composable
fun LightCard(p: Printer, modifier: Modifier, err: (String) -> Unit) =
    MiniCard("Light", if (p.lightOn) "On" else "Off", modifier, trailing = { Toggle(p.lightOn) { p.light(it, err) } }, onClick = null)

// ---------- Filament ----------

@Composable
private fun FilamentCard(p: Printer, onClick: () -> Unit) = Card(padding = androidx.compose.foundation.layout.PaddingValues(14.dp), onClick = onClick) {
    if (p.ams.isEmpty() && p.external.isEmpty()) { T("No AMS or external spool in Home Assistant", 13.sp, C.Sub); return@Card }
    p.ams.forEach { (a, trays) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (a.isSingleSlot()) "AMS HT" else "AMS ${'A' + a.index - 1}", 13.sp, C.Sub, modifier = Modifier.weight(1f))
            p.amsNum(a, "sensor.humidity_index")?.let { h ->
                Icon(Icons.Outlined.WaterDrop, null, Modifier.size(14.dp), tint = humidityColor(h.toInt()))
                T(" ${h.toInt()}", 12.sp, C.Sub)
            }
            p.amsTemp(a)?.let { T("   ${it.toInt()}°C", 12.sp, C.Sub) }
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trays.forEachIndexed { i, s -> SlotTile(s, "${'A' + a.index - 1}${i + 1}", Modifier.weight(1f)) }
        }
        Gap(12.dp)
    }
    p.external.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SlotTile(s, "Ext", Modifier.width(64.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                T("External Spool", 14.sp, weight = FontWeight.Medium)
                T(if (s.known) s.name else "Not set", 12.sp, C.Sub)
            }
        }
    }
}

/** One AMS slot: the spool's colour with its type, the slot name under it, a green ring when it's feeding the nozzle. */
@Composable
fun SlotTile(s: Spool?, label: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    val active = s?.active == true
    Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(10.dp))
        .border(if (active) 2.dp else 0.dp, if (active) C.Accent else Color.Transparent, RoundedCornerShape(10.dp)).padding(if (active) 3.dp else 0.dp)) {
        val color = Color(s?.color ?: 0xFFDDDDDD)
        Canvas(Modifier.fillMaxSize()) { spool(color, s?.remain ?: -1, s == null || !s.known) }
        val dark = (color.red * .3f + color.green * .59f + color.blue * .11f) < .5f
        T(if (s?.known == true) s.type else "?", 12.sp, if (s?.known == true && dark) Color.White else if (s?.known == true) Color(0xFF1F1F1F) else C.Sub, FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center))
    }
    Gap(4.dp)
    T(label, 11.sp, if (active) C.Accent else C.Sub)
}

/** A picture through Home Assistant (image or camera proxy), loaded once per path. */
@Composable
fun HaImage(path: String?, modifier: Modifier) {
    if (path == null) return
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        // Cached files come from Home Assistant even in direct mode.
        val from = if (path.startsWith("/api/bambu_lab/")) Repo.extras else Repo.ha
        value = runCatching { from?.bytes(path)?.let { io.github.gccammelli.pandy.decodeImage(it, 600) } }.getOrNull()
    }
    img?.let { androidx.compose.foundation.Image(it, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}
