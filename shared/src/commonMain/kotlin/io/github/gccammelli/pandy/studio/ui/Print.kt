// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.ApiException
import io.github.gccammelli.pandy.CONTROL_BLOCKED
import io.github.gccammelli.pandy.LanConfig
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.autoMapping
import io.github.gccammelli.pandy.failureText
import io.github.gccammelli.pandy.printerPreset
import io.github.gccammelli.pandy.printerStorage
import io.github.gccammelli.pandy.startPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the plate can't be printed on [p] now, or null. G-code is made for one printer and nozzle: never sent to another. */
fun studioPrintBlock(print: StudioPrint, p: Printer): String? {
    val preset = printerPreset(p.model, p.state("sensor.nozzle_diameter"))
    return when {
        !p.online -> "${p.name} is offline."
        p.controlBlocked -> CONTROL_BLOCKED
        p.printing -> "${p.name} is busy."
        // Fail closed: a printer that hasn't said what it's doing or which nozzle it has is not assumed idle or 0.4 mm.
        p.state("sensor.print_status") == null -> "Pandy doesn't know yet what ${p.name} is doing. Try again in a moment."
        p.state("sensor.nozzle_diameter")?.toDoubleOrNull() == null -> "Pandy doesn't know ${p.name}'s nozzle size yet. Try again in a moment."
        preset != print.printer -> "This plate was sliced for ${print.printer.removePrefix("Bambu Lab ")}, and ${p.name} is a " +
            "${preset.removePrefix("Bambu Lab ")}. Choose that printer in Studio and slice again."
        else -> null
    }
}

/**
 * The loaded spool for each filament the plate uses (in [StudioPrint.filaments] order): same type, nearest colour;
 * -1 when none is loaded. A printer without an AMS prints a one-filament plate from its external spool.
 */
fun studioTrays(print: StudioPrint, p: Printer): List<Int> = print.filaments.map { f ->
    autoMapping(p, listOf(f))?.firstOrNull() ?: if (p.ams.isEmpty() && print.filaments.size == 1) 254 else -1
}

/** The printer's tray for each of the file's filament slots (-1 for slots the plate doesn't use), or null if a spool is missing. */
fun studioMapping(print: StudioPrint, p: Printer): List<Int>? {
    val trays = studioTrays(print, p)
    if (trays.isEmpty() || trays.any { it < 0 }) return null
    val ids = print.filaments.map { it["id"]?.toIntOrNull() ?: return null }
    return List(ids.max()) { slot -> ids.indexOf(slot + 1).let { if (it < 0) -1 else trays[it] } }
}

/** "AMS A2", "AMS HT A", "External spool". */
fun trayLabel(tray: Int): String = when {
    tray < 0 -> "No matching filament loaded"
    tray >= 254 -> if (tray == 254) "External spool" else "External spool 2"
    tray >= 128 -> "AMS HT ${'A' + (tray - 128)}"
    else -> "AMS ${'A' + tray / 4}${tray % 4 + 1}"
}

/** Uploads the sliced plate and starts it, rechecking the printer and its filaments right before each step. */
suspend fun sendStudioPrint(print: StudioPrint, serial: String, lan: LanConfig, timelapse: Boolean, leveling: Boolean, flow: Boolean) {
    fun ready(): Pair<Printer, List<Int>> {
        val live = Repo.state.value.printers?.firstOrNull { it.serial == serial } ?: throw ApiException("The printer is no longer connected.")
        studioPrintBlock(print, live)?.let { throw ApiException(it) }
        return live to (studioMapping(print, live) ?: throw ApiException("Load a matching filament before printing."))
    }
    val (_, planned) = ready()
    val filename = "pandy-${kotlin.random.Random.nextLong().toULong()}.gcode.3mf"
    withContext(Dispatchers.Default) { printerStorage(lan).write("/cache/$filename", print.file) }
    val (live, mapping) = ready()
    if (mapping != planned) throw ApiException("The loaded filament has changed. Review the print and try again.")
    withContext(Dispatchers.Default) { Repo.ha!!.startPrint(live, "cache/$filename", 1, mapping, timelapse, leveling, flow) }
    if (Repo.lan == null) Repo.saveLan(lan)
}

/** Pandy's print sheet for a plate sliced in Studio: the printer, where each filament comes from, the print options. */
@Composable
fun StudioPrintSheet(print: StudioPrint, p: Printer, close: () -> Unit) {
    val err = errorToast()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var leveling by remember { mutableStateOf(true) }
    var flow by remember { mutableStateOf(true) } // on by default, as in Handy
    var timelapse by remember { mutableStateOf(Repo.timelapseDefault) }
    var confirm by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Home Assistant mode doesn't know the printer's access code; it's asked once, here.
    val savedLan = Repo.lan?.takeIf { it.serial.equals(p.serial, ignoreCase = true) }
    val lan = savedLan ?: if (code.length >= 6) p.state("sensor.ip_address")?.let { LanConfig(it, p.serial, code, p.model) } else null
    val block = if (Repo.demo) null else studioPrintBlock(print, p)
    val trays = studioTrays(print, p)
    val ready = Repo.demo || (block == null && lan != null && trays.none { it < 0 })
    Sheet(close, "Print", centeredTitle = true, confirm = if (sending) "Sending…" else if (Repo.demo) "Print (demo)" else "Print",
        onConfirm = { confirm = true }, confirmEnabled = ready && !sending) {
        T(print.name.removeSuffix(".gcode.3mf"), 16.sp, weight = FontWeight.Medium)
        Gap(2.dp)
        T("${p.name} · ${print.printer.removePrefix("Bambu Lab ")}", 13.sp, C.Sub)
        block?.let { Gap(10.dp); T(it, 13.sp, C.Red, lh = 18.sp) }
        Gap(14.dp)
        print.filaments.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(hexColor(f["color"].orEmpty())).border(1.dp, C.Line, CircleShape))
                Spacer(Modifier.width(10.dp))
                T("${f["id"]} · ${f["type"]}", 15.sp, modifier = Modifier.weight(1f))
                val tray = trays[i]
                T(if (Repo.demo) "Demo spool" else trayLabel(tray), 14.sp, if (tray < 0 && !Repo.demo) C.Red else C.Sub)
            }
        }
        if (!Repo.demo && trays.any { it < 0 }) {
            Gap(4.dp); T("Load this filament type on the printer, or pick the loaded one in Studio and slice again.", 13.sp, C.Sub, lh = 18.sp)
        }
        if (!Repo.demo && savedLan == null) {
            Gap(10.dp)
            OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), enabled = !sending, singleLine = true,
                label = { T("Printer access code", 14.sp, C.Sub) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Gap(10.dp)
        ToggleLine("Bed leveling", leveling, !sending) { leveling = it }
        ToggleLine("Flow dynamics calibration", flow, !sending) { flow = it }
        ToggleLine("Timelapse", timelapse, !sending) { timelapse = it }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = C.Card,
        title = { T(if (Repo.demo) "Start demo print?" else "Start print?", weight = FontWeight.Bold) },
        text = { T("Start ${print.name.removeSuffix(".gcode.3mf")} on ${p.name}?", 14.sp, C.Sub) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                if (Repo.demo) {
                    Repo.act(err) { startPrint(p, "cache/${print.name}", 1, null, timelapse, leveling, flow) }
                    close(); Nav.stack.clear(); Nav.tab = 1
                } else {
                    val to = lan ?: return@TextButton err("Enter the printer's access code")
                    sending = true
                    scope.launch {
                        runCatching { sendStudioPrint(print, p.serial, to, timelapse, leveling, flow) }
                            .onSuccess { close(); Nav.stack.clear(); Nav.tab = 1 }
                            .onFailure { sending = false; err(failureText(it)) }
                    }
                }
            }) { T("Start", color = C.Accent, weight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { T("Cancel", color = C.Sub) } },
    )
}
