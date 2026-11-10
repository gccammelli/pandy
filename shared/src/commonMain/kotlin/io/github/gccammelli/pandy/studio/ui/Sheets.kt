// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.studio.Studio

@Composable
fun PrinterSheet(studio: Studio, onDismiss: () -> Unit) = Sheet(onDismiss, "Printer", confirm = null) {
    LazyColumn(Modifier.heightIn(max = 520.dp)) {
        items(studio.printers) { m ->
            Column(Modifier.padding(vertical = 8.dp)) {
                T(m.displayName, 16.sp, weight = FontWeight.Medium)
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    m.nozzles.forEach { n ->
                        val sel = studio.printer?.name == n.name
                        Chip(null, "${n.string("nozzle_diameter") ?: "?"} mm", sel) { studio.choosePrinter(n); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
fun ProcessSheet(studio: Studio, onDismiss: () -> Unit) = Sheet(onDismiss, "Quality", confirm = null) {
    LazyColumn(Modifier.heightIn(max = 520.dp)) {
        items(studio.processes) { p ->
            val sel = studio.process?.name == p.name
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { studio.chooseProcess(p); onDismiss() }.padding(vertical = 14.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(p.name.substringBefore(" @"), 16.sp, if (sel) C.Accent else C.Text, if (sel) FontWeight.Medium else FontWeight.Normal)
                    p.string("layer_height")?.let { T("Layer height $it mm", 13.sp, C.Sub) }
                }
                if (sel) Icon(Icons.Default.Check, null, tint = C.Accent)
            }
        }
    }
}

/** Bambu's filament colour swatches, as Bambu Studio's colour picker offers them. */
val SWATCHES = listOf("#FFFFFF", "#161616", "#898989", "#D3D3D3", "#C12E1F", "#F55A74", "#E0409C", "#AE006D", "#5E43B7", "#0A2989", "#0086D6",
    "#00AE42", "#164B35", "#C2E189", "#F4EE2A", "#FEC600", "#FF6A13", "#9D432C", "#8E8E8E", "#E4BD68")

@Composable
fun FilamentSheet(studio: Studio, slot: Int, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val current = studio.filaments.getOrNull(slot)
    var color by remember { mutableStateOf(current?.second) }
    Sheet(onDismiss, "Filament ${slot + 1}", confirm = null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SWATCHES.take(10).forEach { s -> Swatch(s, color == s) { color = s; if (current != null) studio.setColor(slot, s) } }
        }
        Gap(6.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SWATCHES.drop(10).forEach { s -> Swatch(s, color == s) { color = s; if (current != null) studio.setColor(slot, s) } }
        }
        Gap(14.dp)
        SearchField(query, "Search filaments") { query = it }
        Gap(8.dp)
        val list = studio.filamentChoices.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(list) { f ->
                val sel = current?.first?.name == f.name
                Row(Modifier.fillMaxWidth().clickable { studio.setFilament(slot, f, color ?: SWATCHES[6]); onDismiss() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(f.name.substringBefore(" @"), 15.sp, if (sel) C.Accent else C.Text)
                        T(listOfNotNull(f.string("filament_vendor"), f.string("filament_type"), f.string("nozzle_temperature")?.let { "$it °C" }).joinToString(" · "), 12.sp, C.Sub)
                    }
                    if (sel) Icon(Icons.Default.Check, null, tint = C.Accent)
                }
            }
        }
        if (current != null && studio.filaments.size > 1) {
            Gap(8.dp)
            T("Remove this filament", 15.sp, C.Red, FontWeight.Medium, Modifier.clickable { studio.removeFilament(slot); onDismiss() }.padding(vertical = 10.dp))
        }
        Gap(12.dp)
    }
}

@Composable
private fun RowScope.Swatch(hex: String, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(hexColor(hex)).border(if (selected) 3.dp else 1.dp, if (selected) C.Accent else C.Line, CircleShape)
        .clickable(onClick = onClick))

@Composable
fun SearchField(value: String, hint: String, onChange: (String) -> Unit) =
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(C.Fill).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = C.Sub); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) T(hint, 15.sp, C.Faint)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = C.Text), cursorBrush = SolidColor(C.Accent), modifier = Modifier.fillMaxWidth())
        }
    }

/** Edits one option: a list of choices, a switch, or a number within Bambu's limits. */
@Composable
fun OptionSheet(studio: Studio, key: String, onDismiss: () -> Unit) {
    val def = studio.schema?.get(key)
    val current = studio.value(key)
    val title = def?.label ?: key
    when {
        def?.isEnum == true -> Sheet(onDismiss, title, confirm = null) {
            def.tooltip?.let { T(it, 13.sp, C.Sub, lh = 18.sp); Gap(8.dp) }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(def.enum!!) { v ->
                    Radio(current?.first == v, def.labelOf(v)) { studio.set(key, withValue(current, v)); onDismiss() }
                }
            }
            ResetRow(studio, key, onDismiss)
        }
        def?.isBool == true -> Sheet(onDismiss, title, confirm = null) {
            def.tooltip?.let { T(it, 13.sp, C.Sub, lh = 18.sp); Gap(8.dp) }
            ToggleRow(title, current?.first == "1") { studio.set(key, withValue(current, if (it) "1" else "0")) }
            ResetRow(studio, key, onDismiss)
        }
        else -> {
            var text by remember { mutableStateOf(current?.first ?: "") }
            val error = validate(def, text)
            Sheet(onDismiss, title, confirm = "Apply", confirmEnabled = error == null, onConfirm = { studio.set(key, withValue(current, text.trim())); onDismiss() }) {
                def?.tooltip?.let { T(it, 13.sp, C.Sub, lh = 18.sp); Gap(10.dp) }
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(C.Fill).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp, color = C.Text), cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = if (def?.isNumber == true && !text.endsWith("%")) KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.weight(1f))
                    def?.sidetext?.let { T(it, 15.sp, C.Sub) }
                }
                Gap(6.dp)
                T(error ?: listOfNotNull(def?.min?.let { "Min ${it.trimNum()}" }, def?.max?.takeIf { it < 1e5 }?.let { "Max ${it.trimNum()}" },
                    studio.process?.get(key)?.first?.let { "Preset: $it" }).joinToString(" · "), 12.sp, if (error != null) C.Red else C.Sub)
                ResetRow(studio, key, onDismiss)
            }
        }
    }
}

private fun Double.trimNum() = if (this == kotlin.math.floor(this)) toLong().toString() else toString()

/** Same shape as the preset's value: one string, or the same string for every extruder. */
fun withValue(current: Setting?, v: String): Setting = when (current) { is Setting.Many -> Setting.Many(List(current.values.size.coerceAtLeast(1)) { v }); else -> Setting.One(v) }

/** Numbers must parse and stay within the option's limits; Bambu Studio refuses other values the same way. */
fun validate(def: io.github.gccammelli.pandy.studio.slicer.OptionDef?, text: String): String? {
    if (def == null || !def.isNumber) return null
    val t = text.trim()
    val pct = t.endsWith("%")
    if (pct && def.type !in setOf("coPercent", "coPercents", "coFloatOrPercent", "coFloatsOrPercents")) return "This setting takes a number, not a percentage"
    val v = t.removeSuffix("%").toDoubleOrNull() ?: return "Enter a number"
    if (def.isInt && v != kotlin.math.floor(v)) return "Enter a whole number"
    def.min?.let { if (v < it) return "At least ${it.trimNum()}" }
    def.max?.let { if (v > it) return "At most ${it.trimNum()}" }
    return null
}

@Composable
private fun ResetRow(studio: Studio, key: String, onDismiss: () -> Unit) {
    if (key in studio.overrides) {
        Gap(8.dp)
        T("Reset to the preset's value", 15.sp, C.Accent, FontWeight.Medium, Modifier.clickable { studio.reset(key); onDismiss() }.padding(vertical = 10.dp))
    }
    Gap(12.dp)
}
