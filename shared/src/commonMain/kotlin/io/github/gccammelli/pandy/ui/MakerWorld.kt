package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LanConfig
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.Project
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.WebDownload
import io.github.gccammelli.pandy.WebPage
import io.github.gccammelli.pandy.WebState
import io.github.gccammelli.pandy.amsMappingFor
import io.github.gccammelli.pandy.decodeImage
import io.github.gccammelli.pandy.download
import io.github.gccammelli.pandy.guide
import io.github.gccammelli.pandy.hexColor
import io.github.gccammelli.pandy.readProject
import io.github.gccammelli.pandy.shortModel
import io.github.gccammelli.pandy.startPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAKERWORLD = "https://makerworld.com/en"

/**
 * MakerWorld, as on makerworld.com, signed in with the user's own Bambu account. A model's 3MF download, or its
 * "Open in Bambu Studio", comes to Pandy, which offers to slice and print it on the user's printer.
 */
@Composable
fun MakerWorld(modifier: Modifier) {
    val web = remember { WebState() }
    var download by debugDownload
    WebPage(MAKERWORLD, modifier, onDownload = { download = it }, state = web)
    download?.let { d -> PrintModelSheet(d) { download = null } }
}

/** The file handed to the print sheet (also set by debug builds' launcher, for testing without a MakerWorld sign-in). */
val debugDownload = mutableStateOf<WebDownload?>(null)

/** Steps of printing a model, shown on the sheet's button. */
private enum class Step(val label: String) { Download("Downloading…"), Ready("Print"), Slicing("Slicing…"), Starting("Starting…") }

/**
 * Handy's "Print" sheet for a MakerWorld model: the plate (with its picture), the filaments and which AMS tray each
 * goes to, Handy's print options; then Pandy Slicer slices it and puts it on the printer, and the print starts.
 */
@Composable
fun PrintModelSheet(d: WebDownload, close: () -> Unit) {
    val st by Repo.state.collectAsState()
    val p = st.printer() ?: return close()
    val err = errorToast()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Download) }
    var data by remember { mutableStateOf<ByteArray?>(null) }
    var project by remember { mutableStateOf<Project?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var plate by remember { mutableStateOf(1) }
    var leveling by remember { mutableStateOf(true) }
    var flow by remember { mutableStateOf(true) } // on by default, as in Handy
    var timelapse by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val name = d.name.removeSuffix(".3mf").removeSuffix(".gcode")

    LaunchedEffect(d) {
        runCatching {
            val bytes = d.bytes ?: withContext(Dispatchers.Default) { download(d.url!!, d.cookies) }
            data = bytes
            project = readProject(bytes).also { plate = it.plates.first().index }
            step = Step.Ready
        }.onFailure { failed = it.message ?: it.toString() }
    }

    // Home Assistant mode doesn't know the printer's access code; it's asked once, here.
    val lan = Repo.lan ?: if (code.length >= 6) p.state("sensor.ip_address")?.let { LanConfig(it, p.serial, code, p.model) } else null
    val slicer = Repo.slicer

    fun print() {
        val bytes = data ?: return
        val to = lan ?: return err("Enter the printer's access code")
        if (p.printing) return err("The printer is busy")
        step = Step.Slicing
        scope.launch {
            runCatching {
                if (Repo.lan == null) Repo.saveLan(to)
                val (path, sliced) = withContext(Dispatchers.Default) { slicer!!.sliceAndUpload(bytes, plate, name, to) }
                step = Step.Starting
                val mapping = sliced?.let { amsMappingFor(p, it.filaments) }
                withContext(Dispatchers.Default) { Repo.ha!!.startPrint(p, path, plate, mapping, timelapse, leveling, flow) }
            }.onSuccess { close(); Nav.stack.clear(); Nav.tab = 1 }
                .onFailure { step = Step.Ready; err(it.message ?: it.toString()) }
        }
    }

    Sheet(close, name, centeredTitle = true, confirm = null) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            val pr = project
            when {
                failed != null -> { T(failed!!, 14.sp, C.Red, lh = 20.sp); Gap(20.dp); return@Column }
                pr == null -> { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); T("Downloading the model…", 14.sp, C.Sub) }; Gap(24.dp); return@Column }
            }
            pr!!
            // Plates, with their pictures, as Handy's plate picker.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pr.plates.forEach { pl ->
                    val sel = pl.index == plate
                    Column(Modifier.width(110.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, if (sel) C.Accent else C.Line, RoundedCornerShape(10.dp))
                        .clickable { plate = pl.index }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val img = remember(pl.picture) { pl.picture?.let { decodeImage(it, 300) } }
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
                            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else T("${pl.index}", 20.sp, C.Faint, FontWeight.Bold)
                        }
                        Gap(4.dp)
                        T(pl.name.ifBlank { "Plate ${pl.index}" }, 12.sp, if (sel) C.Accent else C.Text, maxLines = 1)
                    }
                }
            }
            val made = shortModel(pr.printerModel)
            if (made != null && made != p.model) { Gap(12.dp); T("This profile was made for the $made, not your ${p.model}. Pick the profile for your printer on the model's page if MakerWorld has one.", 13.sp, C.Orange, lh = 18.sp) }
            if (pr.filaments.isNotEmpty()) {
                Gap(16.dp)
                T("Filament", 14.sp, weight = FontWeight.Medium)
                Gap(6.dp)
                val trays = io.github.gccammelli.pandy.autoMapping(p, pr.filaments.mapIndexed { i, (t, c) -> mapOf("id" to "${i + 1}", "type" to t, "color" to c) })
                pr.filaments.forEachIndexed { i, (type, colour) ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(hexColor(colour))))
                        Spacer(Modifier.width(8.dp)); T(type, 13.sp, modifier = Modifier.width(64.dp))
                        T("→", 13.sp, C.Sub); Spacer(Modifier.width(8.dp))
                        val t = trays?.getOrNull(i)
                        val s = t?.let { p.ams.getOrNull(it / 4)?.second?.getOrNull(it % 4) }
                        if (s == null) T(if (p.external.isNotEmpty()) "External spool" else "--", 13.sp, C.Sub)
                        else { Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(s.color))); Spacer(Modifier.width(8.dp)); T("${'A' + t / 4}${t % 4 + 1} ${s.type}", 13.sp) }
                    }
                }
                T("Filaments the plate doesn't use are skipped.", 11.sp, C.Sub)
            }
            Gap(12.dp)
            ToggleLine("Bed leveling", leveling) { leveling = it }
            ToggleLine("Flow dynamics calibration", flow) { flow = it }
            ToggleLine("Timelapse", timelapse) { timelapse = it }
            if (Repo.lan == null && !Repo.demo) {
                Gap(12.dp)
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { T("Printer access code", 14.sp, C.Sub) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                T("The slicer puts the file on the printer over the local network, which needs the code from the printer's screen (Settings › Network or WLAN).", 12.sp, C.Sub, lh = 17.sp)
            }
            Gap(20.dp)
            if (Repo.demo) AccentButton("Print (demo)") {
                Repo.act(err) { startPrint(p, "cache/$name.gcode.3mf", plate, null, timelapse, leveling, flow) }
                close(); Nav.stack.clear(); Nav.tab = 1
            } else if (slicer == null) SetupNote("Printing MakerWorld models needs Pandy Slicer: Bambu Studio's slicer in a small container, on Home Assistant or any computer. Then enter its address in Me › Settings.", "docs/slicer.md")
            else AccentButton(step.label, enabled = step == Step.Ready && lan != null) { print() }
            Gap(20.dp)
        }
    }
}

/** Me › Settings › Slicer: Pandy Slicer's address and optional token, with a check. */
@Composable
fun SlicerScreen() = Page("Slicer", { Nav.back() }) {
    var url by remember { mutableStateOf(Repo.slicerUrl ?: "") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pl = LocalPlatform.current
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp)) {
        T("Pandy prints MakerWorld models by having Pandy Slicer (Bambu Studio's slicer in a container) slice them and put them on the printer. Run it as a Home Assistant add-on or with Docker on any x86-64 computer, then enter its address.", 14.sp, C.Sub, lh = 20.sp)
        Gap(16.dp)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { T("Slicer address (e.g. 10.1.2.10:8737)", 14.sp, C.Sub) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Gap(10.dp)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, label = { T("Token (if you set one)", 14.sp, C.Sub) },
            visualTransformation = PasswordVisualTransformation())
        if (status != null) { Gap(12.dp); T(status!!, 14.sp, if (status!!.startsWith("Connected")) C.Ok else C.Red) }
        Gap(20.dp)
        AccentButton(if (busy) "Checking…" else "Save", enabled = !busy && url.isNotBlank()) {
            busy = true; status = null
            scope.launch {
                val c = io.github.gccammelli.pandy.SlicerClient(url, token.ifBlank { null })
                runCatching { withContext(Dispatchers.Default) { c.health() } }
                    .onSuccess { v -> Repo.setSlicer(url, token.ifBlank { null }); status = "Connected: Bambu Studio $v" }
                    .onFailure { status = it.message ?: it.toString() }
                busy = false
            }
        }
        if (Repo.slicerUrl != null) { Gap(12.dp); OutlineButton("Remove", C.Red) { Repo.setSlicer(null, null); url = ""; status = null } }
        Gap(16.dp)
        T("Setup guide ›", 14.sp, C.Blue, modifier = Modifier.clickable { pl.openUrl(guide("docs/slicer.md")) })
    }
}

