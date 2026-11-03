package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.SlicerClient
import io.github.gccammelli.pandy.SlicerSetting
import kotlinx.coroutines.CancellationException

@Composable
internal fun AdvancedSlicerSettings(
    slicer: SlicerClient?, saved: Map<String, String>, back: () -> Unit,
    done: (Map<String, String>, List<SlicerSetting>) -> Unit,
) {
    var settings by remember(slicer?.base, slicer?.viaHa, slicer != null) { mutableStateOf<List<SlicerSetting>?>(null) }
    var error by remember(slicer?.base, slicer?.viaHa, slicer != null) { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(saved) }
    var editing by remember { mutableStateOf<SlicerSetting?>(null) }
    LaunchedEffect(slicer?.base, slicer?.viaHa, slicer != null) {
        if (slicer == null) {
            error = "Set up Pandy Slicer in Me › Settings to change these settings."
        } else try {
            settings = slicer.settings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load the slicer settings."
        }
    }
    val available = settings.orEmpty().associateBy { it.key }
    val unsupported = draft.any { (key, value) -> available[key]?.valid(value) != true }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        PrepareHeader("Slicer settings", back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Gap(16.dp)
            T("Changes apply to this print. Everything else uses the model's profile.", 14.sp, C.Sub, lh = 20.sp)
            Gap(6.dp)
            T("Object-specific settings in the model may need editing in Bambu Studio.", 13.sp, C.Sub, lh = 18.sp)
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
            settings?.groupBy { it.group }?.forEach { (group, rows) ->
                T(group, 16.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
                Card(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    rows.forEach { setting ->
                        Row(Modifier.fillMaxWidth().clickable { editing = setting }.padding(horizontal = 16.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            T(setting.label, 15.sp, modifier = Modifier.weight(1f))
                            T(draft[setting.key]?.let(setting::display) ?: "Profile default", 14.sp, if (setting.key in draft) C.Accent else C.Sub)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                        }
                    }
                }
            }
            Gap(24.dp)
        }
        Row(Modifier.fillMaxWidth().background(C.Card).padding(14.dp).navigationBarsPadding()) {
            AccentButton("Done", enabled = settings != null && !unsupported) { done(draft, settings.orEmpty()) }
        }
    }
    editing?.let { setting ->
        val update: (String?) -> Unit = { value ->
            draft = if (value == null) draft - setting.key else draft + (setting.key to value)
            editing = null
        }
        if (setting.type == "choice") SettingChoice(setting, draft[setting.key], { editing = null }, update)
        else SettingNumber(setting, draft[setting.key], { editing = null }, update)
    }
}

@Composable
private fun SettingChoice(setting: SlicerSetting, value: String?, close: () -> Unit, select: (String?) -> Unit) {
    Sheet(close, setting.label, centeredTitle = true, confirm = null) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            (listOf(null to "Profile default") + setting.options.map { it.value to it.label }).forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().clickable { select(key) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = value == key, onClick = null)
                    T(label, 15.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                }
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
