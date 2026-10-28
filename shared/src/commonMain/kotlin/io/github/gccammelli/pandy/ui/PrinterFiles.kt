package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.FILAMENT_PRESETS
import io.github.gccammelli.pandy.FileEntry
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.Spool
import io.github.gccammelli.pandy.setFilament
import kotlinx.coroutines.CancellationException

private val SWATCHES = listOf("FFFFFF", "161616", "9D9D9D", "C12E1F", "F98C36", "F4E04D", "00AE42", "0086D6", "3F5AA6", "7B4BB5", "E6339A", "8B5A2B")

/** Sets what a tray holds: filament profile and colour. The printer keeps it until the AMS reads a new spool. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditFilamentSheet(p: Printer, s: Spool, close: () -> Unit) {
    val err = errorToast()
    var preset by remember(s) { mutableStateOf(FILAMENT_PRESETS.firstOrNull { it.id == s.filamentId } ?: FILAMENT_PRESETS.firstOrNull { it.type == s.type }) }
    var hex by remember(s) { mutableStateOf(if (s.known) "%06X".format(s.color and 0xFFFFFF) else "FFFFFF") }
    val valid = Regex("[0-9A-Fa-f]{6}").matches(hex)
    Sheet(close, "Edit filament", centeredTitle = true, confirm = "Save", confirmEnabled = preset != null && valid, onConfirm = { p.setFilament(s, preset!!, hex, err); close() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T("Filament", 13.sp, C.Sub)
            Gap(6.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FILAMENT_PRESETS.forEach { f ->
                    val on = f == preset
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) C.Accent else C.Fill).clickable { preset = f }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        T(f.name.removePrefix("Generic "), 13.sp, if (on) Color.White else C.Text)
                    }
                }
            }
            preset?.let { Gap(6.dp); T("Nozzle ${it.min}–${it.max}°C", 12.sp, C.Sub) }
            Gap(14.dp)
            T("Colour", 13.sp, C.Sub)
            Gap(6.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SWATCHES.forEach { c ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong(16)))
                        .border(if (hex.equals(c, true)) 3.dp else 1.dp, if (hex.equals(c, true)) C.Accent else C.Line, CircleShape).clickable { hex = c })
                }
            }
            Gap(10.dp)
            OutlinedTextField(hex, { hex = it.trim().removePrefix("#").take(6).uppercase() }, Modifier.fillMaxWidth(), singleLine = true, isError = !valid,
                label = { T("Hex colour (RRGGBB)", 14.sp, C.Sub) })
        }
    }
}

/** The printer's own files over FTPS: browse folders, delete files. */
@Composable
fun PrinterFilesScreen() {
    val files = Repo.files
    var dir by remember { mutableStateOf("/") }
    var reload by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<FileEntry?>(null) }
    val err = errorToast()
    val listing by produceState<Result<List<FileEntry>>?>(null, dir, reload, files) {
        value = null
        value = files?.let { f ->
            try { Result.success(f.listFiles(dir).sortedWith(compareByDescending<FileEntry> { it.dir }.thenBy { it.name.lowercase() })) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        } ?: Result.success(emptyList())
    }
    fun up() { if (dir == "/") Nav.back() else dir = dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }
    Page("Printer storage", ::up) {
        T(dir, 13.sp, C.Sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when (val r = listing) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
            else -> r.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) Empty("This folder is empty")
                    else Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                        Group {
                            list.forEach { f ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = f.dir) { dir = (dir.trimEnd('/') + "/" + f.name) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (f.dir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null, Modifier.size(22.dp), tint = if (f.dir) C.Accent else C.Sub)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        T(f.name, 14.sp, maxLines = 2)
                                        if (!f.dir) T("${sizeText(f.size)}  ·  ${f.modified.replace('T', ' ')}", 11.sp, C.Sub)
                                    }
                                    if (!f.dir) Icon(Icons.Outlined.DeleteOutline, "Delete ${f.name}", Modifier.size(24.dp).clickable { confirm = f }, tint = C.Red)
                                }
                            }
                        }
                    }
                },
                onFailure = { e ->
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Gap(60.dp)
                        T("Couldn't read the printer's storage", 16.sp, weight = FontWeight.Medium)
                        Gap(8.dp)
                        T(e.message ?: "The connection failed.", 13.sp, C.Sub, align = androidx.compose.ui.text.style.TextAlign.Center)
                        Gap(16.dp)
                        AccentButton("Try again") { reload++ }
                    }
                },
            )
        }
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = C.Card,
            title = { T("Delete ${f.name}?", weight = FontWeight.Medium) },
            text = { T("The file is removed from the printer. This can't be undone.", 14.sp, C.Sub) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    Repo.act(err) { files?.deleteFile(dir.trimEnd('/') + "/" + f.name) ; reload++ }
                }) { T("Delete", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { T("Cancel", color = C.Text) } },
        )
    }
}
