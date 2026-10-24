package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.extrude
import io.github.gccammelli.pandy.move
import io.github.gccammelli.pandy.setFan
import io.github.gccammelli.pandy.setSpeed
import io.github.gccammelli.pandy.setTarget
import kotlin.math.atan2
import kotlin.math.hypot

/** Handy's speed levels: Home Assistant's option → label. */
val SPEEDS = listOf("ludicrous" to "Ludicrous (166%)", "sport" to "Sport (124%)", "standard" to "Standard (100%)", "silent" to "Silent (50%)")
fun speedLabel(s: String?) = SPEEDS.firstOrNull { it.first == s }?.second?.substringBefore(" (") ?: "--"

/** Device Control: the printer with its temperatures, then a tile per control; each opens Handy's sheet. */
@Composable
fun ControlScreen(p: Printer) {
    val err = errorToast()
    var sheet by remember { mutableStateOf<String?>(null) }
    Page("Device Control", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Box(Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp)) {
                PrinterArt(Modifier.fillMaxSize().padding(horizontal = 60.dp), ams = p.info.ams.isNotEmpty(), lit = p.lightOn)
                Chip("nozzle", p.nozzle, p.nozzleTarget, Modifier.align(Alignment.TopStart))
                if (p.chamber != null) Chip("chamber", p.chamber, p.chamberTarget, Modifier.align(Alignment.TopEnd))
                Chip("bed", p.bed, p.bedTarget, Modifier.align(Alignment.BottomStart))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniCard("Speed", speedLabel(p.speed), Modifier.weight(1f)) { if (p.printing) sheet = "speed" else err("Speed can only be changed while printing") }
                LightCard(p, Modifier.weight(1f), err)
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NozzleCard(p, Modifier.weight(1f)) { sheet = "nozzle" }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TempCard("Heatbed", p.bed, p.bedTarget, Modifier.fillMaxWidth()) { sheet = "bed" }
                    TempCard("Chamber", p.chamber, p.chamberTarget, Modifier.fillMaxWidth(), p.chamber?.let { { sheet = if (p.hasChamberHeater) "chamber" else "chamberInfo" } })
                }
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniCard("Fan", "${p.fansOn} fan on", Modifier.weight(1f)) { sheet = "fan" }
                MiniCard("Motion", "XYZ", Modifier.weight(1f)) { Nav.go(Screen.Motion) }
            }
            Gap(24.dp)
        }
    }
    when (sheet) {
        "speed" -> SpeedSheet(p, err) { sheet = null }
        "nozzle" -> NozzleSheet(p, err) { sheet = null }
        "bed" -> TempSheet("Heatbed temperature", "The heatbed will maintain the set temperature during printing to help the material adhere better to the printing plate.",
            listOf(35, 45, 90, 120), p.bedTarget, 0..120, { sheet = null }) { p.setTarget("bed", it, err) }
        "chamber" -> TempSheet("Chamber temperature", "Lower chamber temperatures are suitable for PLA and PETG. Higher chamber temperatures are suitable for warp-prone materials like ABS/ASA/PC/PA.",
            listOf(0, 40, 50, 60), p.chamberTarget, 0..65, { sheet = null }) { p.setTarget("chamber", it, err) }
        "chamberInfo" -> ChamberInfoSheet(p) { sheet = null }
        "fan" -> FanSheet(p, err) { sheet = null }
    }
}

@Composable
private fun ChamberInfoSheet(p: Printer, close: () -> Unit) = Sheet(close, "Chamber temperature", centeredTitle = true, confirm = null) {
    T("${p.chamber!!.toInt()}°C", 28.sp, weight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    Gap(16.dp)
    T("The chamber temperature is shown by the printer. This model does not have a chamber heater, so Pandy cannot set it.", 14.sp, C.Sub, lh = 20.sp)
    Gap(20.dp)
}

@Composable
private fun Chip(which: String, now: Double?, target: Double?, modifier: Modifier) =
    Row(modifier.clip(RoundedCornerShape(50)).background(C.Card).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaterGlyph(which, Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); TempText(now, target, 14.sp, 10.sp)
    }

@Composable
private fun TempCard(label: String, now: Double?, target: Double?, modifier: Modifier, onClick: (() -> Unit)?) =
    Row(modifier.height(72.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(label, 13.sp, C.Sub); Gap(4.dp)
            TempText(now, target, 16.sp, 11.sp)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = C.Faint)
    }

@Composable
private fun SpeedSheet(p: Printer, err: (String) -> Unit, close: () -> Unit) = Sheet(close, "Speed", centeredTitle = true, confirm = null) {
    SPEEDS.forEach { (k, label) ->
        val sel = p.speed == k
        Box(Modifier.fillMaxWidth().background(if (sel) C.Fill else Color.Transparent).clickable { p.setSpeed(k, err); close() }.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            T(label, 15.sp, if (sel) C.Accent else C.Text, if (sel) FontWeight.Medium else FontWeight.Normal)
        }
    }
    Gap(20.dp)
}

@Composable
private fun NozzleSheet(p: Printer, err: (String) -> Unit, close: () -> Unit) {
    var temp by remember { mutableStateOf(false) }
    Sheet(close, "Nozzle & Extruder", centeredTitle = true, confirm = null) {
        Row(Modifier.fillMaxWidth().height(200.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolheadArt(Modifier.weight(1f).fillMaxSize().padding(horizontal = 24.dp))
            Column(Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).background(C.Fill).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T("Extruder", 13.sp, C.Text)
                Gap(10.dp)
                KeyButton(Icons.Default.ArrowDropUp, enabled = !p.printing && (p.nozzle ?: 0.0) >= 170) { p.extrude(false, err) } // up: pull filament back
                Gap(16.dp)
                KeyButton(Icons.Default.ArrowDropDown, enabled = !p.printing && (p.nozzle ?: 0.0) >= 170) { p.extrude(true, err) }
            }
        }
        if (p.printing) T("Extrusion is unavailable while printing.", 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp))
        else if ((p.nozzle ?: 0.0) < 170) T("Heat the nozzle to 170°C or more before extruding.", 12.sp, C.Sub, modifier = Modifier.padding(top = 6.dp))
        Gap(16.dp)
        T("Nozzle temperature", 14.sp)
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Fill).clickable { temp = true }.padding(14.dp)) {
            T("${p.nozzle?.toInt() ?: "--"}°C" + (p.nozzleTarget?.takeIf { it > 0 }?.let { " → ${it.toInt()}°C" } ?: ""), 16.sp)
        }
        Gap(14.dp)
        T("Nozzle type", 14.sp)
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Fill).padding(14.dp)) {
            T("${p.state("sensor.nozzle_diameter") ?: "?"}mm ${nozzleType(p.state("sensor.nozzle_type"))}", 16.sp, C.Sub)
        }
        Gap(8.dp)
        T("Please change the nozzle settings on the printer.", 12.sp, C.Sub)
        Gap(24.dp)
    }
    if (temp) TempSheet("Nozzle temperature", "The nozzle heats to this temperature. Most PLA prints at 190–230°C, PETG at 230–260°C.",
        listOf(190, 220, 250, 280), p.nozzleTarget, 0..320, { temp = false }) { p.setTarget("nozzle", it, err) }
}

@Composable
private fun KeyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(C.Track).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(36.dp), tint = if (enabled) C.Text else C.Faint)
    }

/** Handy's temperature entry: a number field in °C, a picture with a hint, presets and Done. */
@Composable
fun TempSheet(title: String, hint: String, presets: List<Int>, current: Double?, range: IntRange, close: () -> Unit, set: (Int) -> Unit) {
    val start = current?.toInt()?.takeIf { it > 0 }?.toString() ?: ""
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(start, androidx.compose.ui.text.TextRange(start.length))) }
    val text = field.text
    fun setText(v: String) { field = androidx.compose.ui.text.input.TextFieldValue(v, androidx.compose.ui.text.TextRange(v.length)) }
    val focus = remember { FocusRequester() }
    fun done() { text.toIntOrNull()?.let { set(it.coerceIn(range)) }; close() }
    Sheet(close, title, centeredTitle = true, confirm = null) {
        OutlinedTextField(field, { t -> field = t.copy(text = t.text.filter { it.isDigit() }.take(3)) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
            trailingIcon = { T("°C", 14.sp, C.Sub) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = C.Fill, focusedContainerColor = C.Fill, unfocusedBorderColor = Color.Transparent, focusedBorderColor = C.Accent))
        Gap(16.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrinterArt(Modifier.size(90.dp), ams = false, lit = true)
            Spacer(Modifier.width(16.dp))
            T(hint, 13.sp, C.Text, lh = 19.sp, modifier = Modifier.weight(1f))
        }
        Gap(16.dp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Fill).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { v ->
                T(if (v == 0) "Off" else "$v°C", 13.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { setText("$v") }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            T("Done", 15.sp, C.Accent, FontWeight.Bold, modifier = Modifier.clickable { done() }.padding(8.dp))
        }
        Gap(20.dp)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Fan sheet: each fan with an on/off switch and −/+ in 10% steps. */
@Composable
private fun FanSheet(p: Printer, err: (String) -> Unit, close: () -> Unit) = Sheet(close, "Fan", centeredTitle = true, confirm = null) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        p.fans.forEach { (name, id, pct) ->
            if (id == null) return@forEach
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Fill).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(name, 14.sp, C.Sub, modifier = Modifier.weight(1f))
                    Toggle(pct > 0) { p.setFan(id, if (it) 100 else 0, err) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) { T("$pct", 20.sp, weight = FontWeight.Bold); T("%", 12.sp, C.Sub) }
                    StepButton(Icons.Default.Remove, pct > 0) { p.setFan(id, (pct - 10).coerceAtLeast(0), err) }
                    Spacer(Modifier.width(8.dp))
                    StepButton(Icons.Default.Add, pct < 100) { p.setFan(id, (pct + 10).coerceAtMost(100), err) }
                }
            }
        }
        Gap(16.dp)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(C.Card).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) C.Text else C.Faint)
    }

// ---------- Motion ----------

/** XYZ: live view on top; the toolhead pad (inner ring 1 mm, outer 10 mm, home in the middle) and the heatbed column. */
@Composable
fun MotionScreen(p: Printer) {
    val err = errorToast()
    Page("XYZ", { Nav.back() }) {
        if (p.camera != null) LiveView(p, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        if (p.printing) T("Motion is unavailable while printing.", 13.sp, C.Orange, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("Toolhead", 13.sp, C.Sub); Gap(10.dp)
                Pad(Modifier.size(210.dp), enabled = !p.printing) { axis, mm -> p.move(axis, mm, err) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("Heatbed", 13.sp, C.Sub); Gap(10.dp)
                // On X/P/H printers Z moves the bed; a negative distance moves it up.
                Column(Modifier.width(90.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)) {
                    listOf("↑10" to -10, "↑1" to -1, "↓1" to 1, "↓10" to 10).forEachIndexed { i, (l, mm) ->
                        Box(Modifier.fillMaxWidth().height(50.dp).background(if (i in 1..2) C.Fill else C.Card).clickable(enabled = !p.printing) { p.move("Z", mm, err) }, contentAlignment = Alignment.Center) {
                            T(l, 15.sp, if (p.printing) C.Faint else C.Text)
                        }
                    }
                }
            }
        }
    }
}

/** The round pad: tap a quarter of the outer ring for 10 mm, the inner ring for 1 mm, the centre to home. */
@Composable
private fun Pad(modifier: Modifier, enabled: Boolean, move: (String, Int) -> Unit) {
    val ring = C.Card; val inner = C.Fill; val line = C.Line; val text = if (enabled) C.Text else C.Faint; val green = C.Accent
    Box(modifier.pointerInput(enabled) {
        detectTapGestures { o ->
            if (!enabled) return@detectTapGestures
            val c = Offset(size.width / 2f, size.height / 2f)
            val d = hypot(o.x - c.x, o.y - c.y) / (size.width / 2f)
            if (d > 1f) return@detectTapGestures
            if (d < .3f) { move("Home", 0); return@detectTapGestures }
            val step = if (d < .62f) 1 else 10
            val a = atan2((c.y - o.y).toDouble(), (o.x - c.x).toDouble()) * 180 / kotlin.math.PI // 0 = right, 90 = up
            when {
                a in -45.0..45.0 -> move("X", step)
                a in 45.0..135.0 -> move("Y", step)
                a > 135 || a < -135 -> move("X", -step)
                else -> move("Y", -step)
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width / 2
            drawCircle(ring, r); drawCircle(inner, r * .62f); drawCircle(ring, r * .3f)
            for (deg in listOf(45f, 135f)) {
                val rad = deg * kotlin.math.PI / 180
                val dx = (kotlin.math.cos(rad) * r).toFloat(); val dy = (kotlin.math.sin(rad) * r).toFloat()
                drawLine(line, Offset(r - dx, r - dy), Offset(r + dx, r + dy), 2f)
            }
            drawCircle(ring, r * .3f); drawCircle(line, r * .3f, style = Stroke(2f)); drawCircle(line, r, style = Stroke(2f))
        }
        Icon(Icons.Outlined.Home, "Home", Modifier.align(Alignment.Center).size(24.dp), tint = if (enabled) green else C.Faint)
        T("Y", 14.sp, text, FontWeight.Bold, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        T("-Y", 14.sp, text, FontWeight.Bold, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        T("-X", 14.sp, text, FontWeight.Bold, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
        T("X", 14.sp, text, FontWeight.Bold, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
        T("1", 11.sp, C.Sub, modifier = Modifier.align(Alignment.Center).padding(start = 70.dp, bottom = 70.dp), align = TextAlign.Center)
        T("10", 11.sp, C.Sub, modifier = Modifier.align(Alignment.Center).padding(start = 130.dp, bottom = 130.dp), align = TextAlign.Center)
    }
}
