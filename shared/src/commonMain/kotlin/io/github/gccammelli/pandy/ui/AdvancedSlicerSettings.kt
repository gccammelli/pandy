package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.ChoiceStyle
import io.github.gccammelli.pandy.densityPresets
import io.github.gccammelli.pandy.withinRange
import io.github.gccammelli.pandy.SlicerClient
import io.github.gccammelli.pandy.SlicerSetting
import io.github.gccammelli.pandy.choiceStyle
import io.github.gccammelli.pandy.initiallyOpenGroups
import io.github.gccammelli.pandy.matches
import io.github.gccammelli.pandy.settingArt
import io.github.gccammelli.pandy.sliderRange
import io.github.gccammelli.pandy.stepped
import kotlinx.coroutines.CancellationException

private fun groupIcon(group: String): ImageVector = when (group) {
    "Quality" -> Icons.Filled.AutoAwesome
    "Strength" -> Icons.Filled.Shield
    "Support" -> Icons.Filled.Construction
    "Adhesion" -> Icons.Filled.Layers
    "Speed" -> Icons.Filled.Speed
    else -> Icons.Filled.Tune
}

@Composable
internal fun AdvancedSlicerSettings(
    slicer: SlicerClient?, saved: Map<String, String>, back: () -> Unit,
    done: (Map<String, String>, List<SlicerSetting>) -> Unit,
) = AdvancedSlicerSettingsForBackend(slicer, saved, back, null, done)

@Composable
internal fun AdvancedSlicerSettingsForBackend(
    slicer: SlicerClient?, saved: Map<String, String>, back: () -> Unit,
    phoneSettings: (suspend () -> List<SlicerSetting>)?,
    done: (Map<String, String>, List<SlicerSetting>) -> Unit,
) {
    var settings by remember(slicer?.base, slicer?.viaHa, slicer != null) { mutableStateOf<List<SlicerSetting>?>(null) }
    var error by remember(slicer?.base, slicer?.viaHa, slicer != null) { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(saved) }
    var editing by remember { mutableStateOf<SlicerSetting?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyChanged by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(slicer?.base, slicer?.viaHa, slicer != null) {
        if (slicer == null && phoneSettings == null) {
            error = "Set up Pandy Slicer in Me › Settings to change these settings."
        } else try {
            settings = phoneSettings?.invoke() ?: slicer!!.settings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load the slicer settings."
        }
    }
    val all = settings.orEmpty()
    val available = all.associateBy { it.key }
    val unsupported = draft.any { (key, value) -> available[key]?.valid(value) != true }
    val groups = all.map { it.group }.distinct()
    val open = opened ?: initiallyOpenGroups(groups, all.filter { it.key in draft }.map { it.group })
    val filtering = query.isNotBlank() || onlyChanged
    fun set(setting: SlicerSetting, value: String?) { draft = if (value == null) draft - setting.key else draft + (setting.key to value) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        PrepareHeader("Slicer settings", back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Gap(12.dp)
            if (settings != null) {
                SearchField(query, all.size) { query = it }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", null, !onlyChanged) { onlyChanged = false }
                    FilterChip("Modified", draft.size.takeIf { it > 0 }, onlyChanged) { onlyChanged = true }
                }
            }
            Gap(8.dp)
            T("Changes apply to this print. Everything else uses the model's profile.", 13.sp, C.Sub, lh = 18.sp)
            Gap(4.dp)
            T("Object-specific settings in the model may need editing in Bambu Studio.", 12.sp, C.Sub, lh = 17.sp)
            if (settings == null && error == null) {
                Gap(20.dp); T("Loading slicer settings…", 14.sp, C.Sub)
            }
            error?.let { Gap(20.dp); T(it, 14.sp, C.Red, lh = 20.sp) }
            if (settings != null && unsupported) {
                Gap(16.dp); T("Some saved choices are no longer supported. Reset them to continue.", 14.sp, C.Red, lh = 20.sp)
            }
            if (draft.isNotEmpty()) {
                TextButton(onClick = { draft = emptyMap() }) { T("Reset all to profile", 14.sp, C.Accent) }
            } else Gap(12.dp)
            if (filtering) {
                val hits = all.filter { it.matches(query) && (!onlyChanged || it.key in draft) }
                if (hits.isEmpty()) {
                    T(if (onlyChanged && query.isBlank()) "Nothing changed for this print yet." else "No settings match your search.", 14.sp, C.Sub,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                } else {
                    T(if (hits.size == 1) "1 result" else "${hits.size} results", 12.sp, C.Sub, FontWeight.Medium, Modifier.padding(start = 4.dp, bottom = 8.dp))
                    Card(padding = PaddingValues(0.dp)) {
                        hits.forEachIndexed { i, setting ->
                            if (i > 0) Divider()
                            SettingRow(setting, draft[setting.key], showGroup = true, edit = { editing = setting }, change = { set(setting, it) })
                        }
                    }
                }
            } else groups.forEach { group ->
                val rows = all.filter { it.group == group }
                val changed = rows.count { it.key in draft }
                val expanded = group in open
                Card(Modifier.padding(bottom = 10.dp), radius = 18.dp, padding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { opened = if (expanded) open - group else open + group }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(C.AccentBg), contentAlignment = Alignment.Center) {
                            Icon(groupIcon(group), null, Modifier.size(22.dp), tint = C.Accent)
                        }
                        Column(Modifier.weight(1f)) {
                            T(group, 16.sp, weight = FontWeight.Bold)
                            val count = if (rows.size == 1) "1 setting" else "${rows.size} settings"
                            T(if (changed > 0) "$changed modified · $count" else count, 12.sp, if (changed > 0) C.Accent else C.Sub)
                        }
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse $group" else "Expand $group", tint = C.Sub)
                    }
                    if (expanded) rows.forEach { setting ->
                        Divider()
                        SettingRow(setting, draft[setting.key], showGroup = false, edit = { editing = setting }, change = { set(setting, it) })
                    }
                }
            }
            Gap(24.dp)
        }
        Row(Modifier.fillMaxWidth().background(C.Card).padding(14.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                T(when (draft.size) { 0 -> "No changes"; 1 -> "1 change"; else -> "${draft.size} changes" }, 14.sp, weight = FontWeight.Medium)
                T("this print", 12.sp, C.Sub)
            }
            AccentButton("Done", Modifier.weight(1f), enabled = settings != null && !unsupported) { done(draft, settings.orEmpty()) }
        }
    }
    editing?.let { setting ->
        val update: (String?) -> Unit = { value -> set(setting, value); editing = null }
        if (setting.type == "choice") SettingChoice(setting, draft[setting.key], { editing = null }, update)
        else SettingNumber(setting, draft[setting.key], { editing = null }, update)
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(C.Line))

@Composable
private fun SearchField(query: String, count: Int, change: (String) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = C.Sub)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) T("Search $count settings", 15.sp, C.Sub)
            BasicTextField(query, { change(it.take(40)) }, Modifier.fillMaxWidth().semantics { contentDescription = "Search settings" }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = C.Text), cursorBrush = SolidColor(C.Accent))
        }
        if (query.isNotEmpty()) Icon(Icons.Filled.Cancel, "Clear search", Modifier.size(20.dp).clickable { change("") }, tint = C.Faint)
    }


@Composable
private fun FilterChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) C.AccentBg else C.Card)
        .border(BorderStroke(1.5.dp, if (selected) C.Accent else C.Card), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 13.sp, if (selected) C.Accent else C.Text, FontWeight.Medium)
        if (count != null) Box(Modifier.clip(CircleShape).background(C.Accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { T("$count", 11.sp, C.OnAccent) }
    }

@Composable
private fun SettingRow(setting: SlicerSetting, value: String?, showGroup: Boolean, edit: () -> Unit, change: (String?) -> Unit) {
    val modified = value != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(setting.label, 15.sp, modifier = Modifier.weight(1f, fill = false))
                    if (showGroup) T(setting.group, 11.sp, C.Sub, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Bg).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                if (modified) T("Reset to profile", 12.sp, C.Accent, FontWeight.Medium, Modifier.clickable { change(null) }.padding(top = 3.dp, bottom = 3.dp))
            }
            when {
                setting.type != "choice" -> NumberControl(setting, value, edit, change)
                setting.choiceStyle() == ChoiceStyle.Picker -> Row(Modifier.clickable(onClick = edit), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    value?.let { v -> settingArt(setting.key, v)?.let { SettingArt(it, C.Accent, 28.dp) } }
                    T(value?.let(setting::display) ?: "Profile default", 14.sp, if (modified) C.Accent else C.Sub, if (modified) FontWeight.Medium else FontWeight.Normal)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                }
            }
        }
        if (setting.type == "choice" && setting.choiceStyle() == ChoiceStyle.Segmented) Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(3.dp)) {
            setting.options.forEach { option ->
                val on = value == option.value
                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) C.Card else C.Bg)
                    .selectable(on, role = Role.RadioButton) { change(if (on) null else option.value) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    T(option.label, 13.sp, if (on) C.Accent else C.Sub, if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (setting.key == "sparse_infill_density") DensityControls(setting, value, change)
    }
}

@Composable
private fun NumberControl(setting: SlicerSetting, value: String?, edit: () -> Unit, change: (String?) -> Unit) {
    if (value == null) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Bg).clickable(onClick = edit).padding(horizontal = 16.dp, vertical = 9.dp)) {
        T("Default", 14.sp, C.Sub)
    } else Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.AccentBg), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Remove, "Decrease ${setting.label}", Modifier.clickable { change(setting.stepped(value, -1)) }.padding(8.dp).size(20.dp), tint = C.Accent)
        T(setting.display(value), 14.sp, C.Accent, FontWeight.SemiBold, Modifier.clickable(onClick = edit).padding(horizontal = 4.dp, vertical = 8.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        Icon(Icons.Filled.Add, "Increase ${setting.label}", Modifier.clickable { change(setting.stepped(value, 1)) }.padding(8.dp).size(20.dp), tint = C.Accent)
    }
}

@Composable
private fun DensityControls(setting: SlicerSetting, value: String?, change: (String?) -> Unit) {
    val now = value?.toFloatOrNull()
    if (now != null) Slider(now, { change(setting.withinRange(kotlin.math.round(it).toDouble())) }, Modifier.padding(top = 6.dp).semantics { contentDescription = setting.label },
        valueRange = setting.sliderRange(),
        colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Track))
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        setting.densityPresets().forEach { (name, preset) ->
            val on = value == preset
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) C.AccentBg else C.Bg).clickable { change(preset) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center) { T("$name $preset", 12.sp, if (on) C.Accent else C.Sub, if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun SettingChoice(setting: SlicerSetting, value: String?, close: () -> Unit, select: (String?) -> Unit) {
    Sheet(close, setting.label, centeredTitle = true, confirm = null) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (setting.options.any { settingArt(setting.key, it.value) != null }) setting.options.chunked(4).forEach { rowOptions ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowOptions.forEach { option ->
                        val on = value == option.value
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) C.AccentBg else C.Fill)
                            .border(BorderStroke(2.dp, if (on) C.Accent else C.Fill), RoundedCornerShape(14.dp)).clickable { select(option.value) }.padding(vertical = 8.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            SettingArt(settingArt(setting.key, option.value).orEmpty(), if (on) C.Accent else C.Sub, 56.dp)
                            T(option.label, 11.sp, if (on) C.Accent else C.Text, if (on) FontWeight.SemiBold else FontWeight.Normal, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    repeat(4 - rowOptions.size) { Box(Modifier.weight(1f)) }
                }
            }
            val reset = value == null
            if (setting.options.none { settingArt(setting.key, it.value) != null }) setting.options.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { select(option.value) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == option.value, onClick = null)
                    T(option.label, 15.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                }
            }
            Row(Modifier.fillMaxWidth().clickable { select(null) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = reset, onClick = null)
                T("Profile default", 15.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
            }
        }
        Gap(20.dp)
    }
}

@Composable
private fun SettingNumber(setting: SlicerSetting, value: String?, close: () -> Unit, select: (String?) -> Unit) {
    var text by remember(setting.key) { mutableStateOf(value.orEmpty()) }
    val valid = setting.valid(text)
    fun bound(n: Double?) = if (setting.type == "integer") n?.toInt().toString() else n.toString()
    Sheet(close, setting.label, centeredTitle = true, confirm = "Save", confirmEnabled = valid, onConfirm = { select(text) }) {
        OutlinedTextField(text, { text = it.replace(',', '.').take(32) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { T(setting.label, 14.sp) },
            suffix = { if (setting.unit.isNotEmpty()) T(setting.unit, 14.sp, C.Sub) },
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = if (setting.type == "integer") KeyboardType.Number else KeyboardType.Decimal))
        Gap(8.dp)
        T("Range: ${bound(setting.min)}–${bound(setting.max)} ${setting.unit}".trim(), 13.sp, C.Sub)
        if (setting.key in setOf("layer_height", "initial_layer_print_height")) {
            Gap(4.dp); T("The allowed range also depends on the nozzle size.", 13.sp, C.Sub)
        }
        if (value != null) TextButton(onClick = { select(null) }) { T("Use profile default", 14.sp, C.Accent) }
    }
}
