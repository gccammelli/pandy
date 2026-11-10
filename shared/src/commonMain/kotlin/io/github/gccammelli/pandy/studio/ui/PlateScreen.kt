// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.gcode.Feature
import io.github.gccammelli.pandy.studio.model.Vec3
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.studio.PlateObject
import io.github.gccammelli.pandy.studio.studio.SliceState
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.studio.studio.ViewMode
import io.github.gccammelli.pandy.studio.studio.Viewport
import kotlinx.coroutines.launch

fun hexColor(s: String): Color = Color(0xFF000000 or (s.removePrefix("#").take(6).toLongOrNull(16) ?: 0xE0409C))

@Composable
fun PlateScreen(studio: Studio, nav: StudioNav) {
    val scene = studio.scene
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<String?>(null) }
    var filamentSlot by remember { mutableIntStateOf(0) }
    val state = studio.state
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        // Top bar: back, Prepare/Preview, Slice.
        Box(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding().height(56.dp)) {
            BackChevron({ nav.back() }, Modifier.align(Alignment.CenterStart))
            Segmented(listOf("Prepare", "Preview"), if (scene.mode == ViewMode.PREVIEW) 1 else 0, Modifier.align(Alignment.Center),
                enabled = listOf(true, scene.preview != null)) { scene.mode = if (it == 1) ViewMode.PREVIEW else ViewMode.PREPARE; scene.touch() }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                val canSlice = studio.canSlice && state !is SliceState.Running && scene.outside().isEmpty()
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (canSlice) C.Accent else C.Track)
                    .clickable(enabled = canSlice) { studio.slice() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    T("Slice", 15.sp, if (canSlice) C.OnAccent else C.Sub, FontWeight.Medium)
                }
            }
        }
        // Printer, process and filaments.
        Row(Modifier.fillMaxWidth().background(C.Card).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(Icons.Default.Print, studio.printer?.name?.removePrefix("Bambu Lab ")?.replace(" nozzle", "") ?: "Printer") { sheet = "printer" }
            Chip(Icons.Default.Layers, studio.process?.name?.substringBefore(" @") ?: "Process") { sheet = "process" }
            studio.filaments.forEachIndexed { i, (f, color) ->
                Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(C.Fill).clickable { filamentSlot = i; sheet = "filament" }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(hexColor(color)).border(1.dp, C.Line, CircleShape), contentAlignment = Alignment.Center) {
                        T("${i + 1}", 10.sp, if (hexColor(color).luminance() > .6f) Color.Black else Color.White, FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp)); T(f.string("filament_type") ?: f.name.substringBefore(" @"), 13.sp)
                }
            }
            // One filament per plate for now: per-object filaments are not covered by the equivalence tests yet.
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Viewport(scene, ThemeMode.dark, Modifier.fillMaxSize())
            if (scene.objects.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                T("The plate is empty", 16.sp, C.Sub); Gap(8.dp)
                OutlineButton("Add a model", C.Accent, Modifier.width(200.dp)) {
                    scope.launch { pickModels(platform, studio) }
                }
            }
            val out = scene.outside()
            if (out.isNotEmpty() && scene.mode == ViewMode.PREPARE) Banner("${out.size} object${if (out.size > 1) "s are" else " is"} outside the printable area", C.Red, Modifier.align(Alignment.TopCenter))
            if (scene.mode == ViewMode.PREVIEW && scene.preview != null) LayerRail(studio, Modifier.align(Alignment.CenterEnd))
            StudioSession.notice?.let { Banner(it, C.Red, Modifier.align(Alignment.TopCenter)) { StudioSession.notice = null } }
            when (state) {
                is SliceState.Running -> SlicingCard(state, Modifier.align(Alignment.Center)) { studio.cancel() }
                is SliceState.Failed -> Banner(state.message, C.Red, Modifier.align(Alignment.TopCenter)) { studio.state = SliceState.Idle }
                else -> {}
            }
        }
        // Bottom panel.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(C.Card).navigationBarsPadding()) {
            if (scene.mode == ViewMode.PREVIEW && state is SliceState.Done) PreviewPanel(studio, state)
            else {
                val sel = scene.selection
                if (sel != null) ObjectPanel(studio, sel) else PlatePanel(studio, nav) { scope.launch { pickModels(platform, studio) } }
            }
        }
    }
    when (sheet) {
        "printer" -> PrinterSheet(studio) { sheet = null }
        "process" -> ProcessSheet(studio) { sheet = null }
        "filament" -> FilamentSheet(studio, filamentSlot) { sheet = null }
    }
}

private fun Color.luminance() = .299f * red + .587f * green + .114f * blue

@Composable
fun Segmented(items: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: List<Boolean> = items.map { true }, onSelect: (Int) -> Unit) =
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(C.Fill).padding(3.dp)) {
        items.forEachIndexed { i, s ->
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == selected) C.Card else Color.Transparent)
                .clickable(enabled = enabled[i]) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                T(s, 14.sp, if (!enabled[i]) C.Faint else if (i == selected) C.Text else C.Sub, if (i == selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }

@Composable
fun Chip(icon: ImageVector?, text: String, selected: Boolean = false, onClick: () -> Unit) =
    Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (selected) C.AccentBg else C.Fill).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = if (selected) C.Accent else C.Sub); Spacer(Modifier.width(6.dp)) }
        T(text, 13.sp, if (selected) C.Accent else C.Text, maxLines = 1)
    }

@Composable
private fun Banner(text: String, color: Color, modifier: Modifier, onClose: (() -> Unit)? = null) =
    Row(modifier.padding(12.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = color); Spacer(Modifier.width(8.dp))
        T(text, 13.sp, C.Text, modifier = Modifier.weight(1f, fill = false), lh = 17.sp)
        if (onClose != null) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp).clickable(onClick = onClose), tint = C.Sub) }
    }

@Composable
private fun SlicingCard(state: SliceState.Running, modifier: Modifier, onCancel: () -> Unit) =
    Column(modifier.width(260.dp).clip(RoundedCornerShape(16.dp)).background(C.Card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("Slicing", 17.sp, weight = FontWeight.Medium); Gap(4.dp); T(state.status, 13.sp, C.Sub); Gap(14.dp)
        Bar(state.progress ?: .1f, C.Accent); Gap(14.dp)
        T("Cancel", 14.sp, C.Accent, FontWeight.Medium, Modifier.clickable(onClick = onCancel).padding(8.dp))
    }

@Composable
private fun ToolButton(icon: ImageVector, label: String, tint: Color = C.Text, enabled: Boolean = true, onClick: () -> Unit) =
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).widthIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.Fill), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(20.dp), tint = if (enabled) tint else C.Faint) }
        Gap(4.dp); T(label, 11.sp, if (enabled) C.Sub else C.Faint, maxLines = 1)
    }

@Composable
private fun PlatePanel(studio: Studio, nav: StudioNav, onAdd: () -> Unit) {
    Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        QuickSettings(studio, nav)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Default.Add, "Add", C.Accent, onClick = onAdd)
            ToolButton(Icons.Default.GridView, "Arrange", enabled = studio.scene.objects.isNotEmpty()) { studio.scene.arrange(); studio.invalidate() }
            ToolButton(Icons.Default.Tune, "Settings") { nav.go(StudioScreen.Settings()) }
            ToolButton(Icons.Default.DeleteSweep, "Clear", C.Red, enabled = studio.scene.objects.isNotEmpty()) { studio.scene.objects.clear(); studio.scene.selected = null; studio.invalidate() }
        }
    }
}

/** The settings people change most, as Bambu Handy's print options and Bambu Studio's basic mode show them. */
@Composable
private fun QuickSettings(studio: Studio, nav: StudioNav) {
    var editing by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val support = studio.value("enable_support")?.first == "1"
        Chip(Icons.Default.Foundation, if (support) "Supports: ${studio.schema?.get("support_type")?.labelOf(studio.value("support_type")?.first ?: "") ?: "on"}" else "No supports", support) {
            studio.set("enable_support", Setting.One(if (support) "0" else "1"))
        }
        Chip(Icons.Default.Grid4x4, "Infill ${studio.value("sparse_infill_density")?.first ?: "–"}", "sparse_infill_density" in studio.overrides) { editing = "sparse_infill_density" }
        Chip(Icons.Default.CropSquare, "Walls ${studio.value("wall_loops")?.first ?: "–"}", "wall_loops" in studio.overrides) { editing = "wall_loops" }
        val brim = studio.value("brim_type")?.first ?: "auto_brim"
        Chip(Icons.Default.BorderOuter, "Brim: ${studio.schema?.get("brim_type")?.labelOf(brim) ?: brim}", "brim_type" in studio.overrides) { editing = "brim_type" }
        Chip(Icons.Default.Tune, "All settings${if (studio.overrides.isNotEmpty()) " · ${studio.overrides.size} changed" else ""}", studio.overrides.isNotEmpty()) { nav.go(StudioScreen.Settings()) }
    }
    editing?.let { key -> OptionSheet(studio, key) { editing = null } }
}

@Composable
private fun ObjectPanel(studio: Studio, o: PlateObject) {
    val scene = studio.scene
    fun changed() { scene.touch(); studio.invalidate() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(o.name, 16.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            val s = o.worldBounds().size
            T("${s.x.fmt()} × ${s.y.fmt()} × ${s.z.fmt()} mm", 13.sp, C.Sub)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Close, "Deselect", Modifier.size(22.dp).clickable { scene.selected = null; scene.touch() }, tint = C.Sub)
        }
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Scale", 13.sp, C.Sub, modifier = Modifier.width(48.dp))
            val pct = o.scale.x * 100f
            Slider(pct, { v -> val k = v / 100f; o.scale = Vec3(k, k, k); scene.touch() }, Modifier.weight(1f), valueRange = 10f..400f,
                onValueChangeFinished = { studio.invalidate() }, colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Track))
            T("${pct.toInt()}%", 13.sp, modifier = Modifier.width(44.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween) {
            ToolButton(Icons.AutoMirrored.Filled.RotateLeft, "−90° Z") { o.rotZ -= 90f; changed() }
            ToolButton(Icons.AutoMirrored.Filled.RotateRight, "+90° Z") { o.rotZ += 90f; changed() }
            ToolButton(Icons.Default.ScreenRotation, "Tip over") { o.rotX = (o.rotX + 90f) % 360f; changed() }
            ToolButton(Icons.Default.Restore, "Reset") { o.rotX = 0f; o.rotY = 0f; o.rotZ = 0f; o.scale = Vec3(1f, 1f, 1f); changed() }
            ToolButton(Icons.Default.ContentCopy, "Duplicate") { studio.duplicate(o.id) }
            ToolButton(Icons.Default.Delete, "Delete", C.Red) { scene.remove(o.id); studio.invalidate() }
        }
    }
}

fun Float.fmt(d: Int = 1): String { val p = listOf(1f, 10f, 100f)[d.coerceIn(0, 2)]; val v = kotlin.math.round(this * p) / p; return if (d == 0) v.toInt().toString() else v.toString() }

@Composable
private fun LayerRail(studio: Studio, modifier: Modifier) {
    val scene = studio.scene
    val n = scene.preview?.layers?.size ?: return
    Column(modifier.padding(end = 6.dp).width(56.dp).fillMaxHeight(.8f).clip(RoundedCornerShape(16.dp)).background(C.Card.copy(alpha = .92f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        T("${scene.layerHigh.coerceAtMost(n)}", 13.sp, weight = FontWeight.Medium)
        T("${scene.preview?.layers?.getOrNull(scene.layerHigh - 1)?.z?.fmt(2) ?: ""}", 10.sp, C.Sub)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            RangeSlider(scene.layerLow.toFloat()..scene.layerHigh.coerceAtMost(n).toFloat(), { r -> scene.layerLow = r.start.toInt().coerceAtLeast(1); scene.layerHigh = r.endInclusive.toInt() },
                Modifier.requiredWidth(260.dp).graphicsRotate(), valueRange = 1f..n.toFloat().coerceAtLeast(1.0001f),
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Track))
        }
        T("${scene.layerLow}", 13.sp, C.Sub)
    }
}

private fun Modifier.graphicsRotate() = this.then(Modifier.graphicsLayerRotate(-90f))

@Composable
private fun PreviewPanel(studio: Studio, done: SliceState.Done) {
    val ui = io.github.gccammelli.pandy.LocalPlatform.current
    var message by remember { mutableStateOf<String?>(null) }
    val st = done.gcode.stats
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Print time", st.estimatedSeconds?.let(::duration) ?: "–")
            Stat("Filament", st.filamentGrams?.takeIf { it > 0f }?.let { "${it.fmt()} g" } ?: "–")
            Stat("Length", st.filamentLengthMm?.let { "${(it / 1000f).fmt(2)} m" } ?: "–")
            Stat("Layers", "${done.gcode.layers.size}")
        }
        Gap(10.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val used = done.gcode.layers.flatMap { l -> l.features.toSet() }.toSet()
            Feature.entries.filter { it.ordinal.toByte() in used && it != Feature.TRAVEL }.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(featureColor(f))); Spacer(Modifier.width(4.dp))
                    T(f.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, 12.sp, C.Sub)
                }
            }
        }
        if (done.warnings.isNotEmpty()) { Gap(8.dp); done.warnings.forEach { T(it, 13.sp, C.Red) } }
        if (studio.value("enable_support")?.first == "1" && studio.value("support_type")?.first?.startsWith("tree") == true) {
            Gap(8.dp)
            T("Tree supports: the part matches Bambu Studio, but supports and the layers resting on them can differ slightly " +
                "from it (docs/equivalence).", 13.sp, C.Orange, lh = 17.sp)
        }
        Gap(12.dp)
        val name = "${studio.scene.objects.firstOrNull()?.name ?: "plate"}.gcode.3mf"
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ready = done.warnings.isEmpty() && done.run.sliced != null && studio.printer != null
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (ready) C.Accent else C.Track)
                .clickable(enabled = ready) { StudioSession.print = StudioPrint(done.run.sliced!!, name, studio.printer!!.name, studio.usedFilaments()) },
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Print, null, Modifier.size(18.dp), tint = if (ready) C.OnAccent else C.Sub); Spacer(Modifier.width(8.dp))
                    T("Print", 16.sp, if (ready) C.OnAccent else C.Sub, FontWeight.Medium)
                }
            }
            RoundIcon(Icons.Default.SaveAlt, "Save") {
                done.run.sliced?.let { bytes -> ui.saveFile(name, "application/octet-stream", bytes) { e -> message = e?.message ?: "Saved to Downloads." } }
            }
        }
        message?.let { Gap(6.dp); T(it, 13.sp, C.Sub) }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).background(C.Fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(20.dp), tint = C.Text) }

@Composable
private fun Stat(label: String, value: String) = Column {
    T(value, 17.sp, weight = FontWeight.Medium); T(label, 12.sp, C.Sub)
}

fun duration(s: Int) = if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60}m ${s % 60}s"

fun featureColor(f: Feature): Color = hexColor(listOf("#FF7D38", "#FFE64D", "#0000FF", "#B03029", "#9654CC", "#F05C5C", "#4D5054", "#4D80BA", "#FFFFFF", "#00876E",
    "#00876E", "#00FF00", "#008000", "#00C8A0", "#B3E3AB", "#FF8C69", "#5E5E5E", "#38D4FF", "#A0A0A0")[f.ordinal])
