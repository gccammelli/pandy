package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.LanConfig
import io.github.gccammelli.pandy.LocalPlatform
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.PreviewMesh
import io.github.gccammelli.pandy.Project
import io.github.gccammelli.pandy.Repo
import io.github.gccammelli.pandy.ApiException
import io.github.gccammelli.pandy.WebDownload
import io.github.gccammelli.pandy.WebPage
import io.github.gccammelli.pandy.WebState
import io.github.gccammelli.pandy.autoMapping
import io.github.gccammelli.pandy.buildPlateCode
import io.github.gccammelli.pandy.buildPlateLabel
import io.github.gccammelli.pandy.decodeImage
import io.github.gccammelli.pandy.download
import io.github.gccammelli.pandy.filamentKey
import io.github.gccammelli.pandy.guide
import io.github.gccammelli.pandy.hexColor
import io.github.gccammelli.pandy.isSingleSlot
import io.github.gccammelli.pandy.readPreviewMeshes
import io.github.gccammelli.pandy.readProject
import io.github.gccammelli.pandy.shortModel
import io.github.gccammelli.pandy.startPrint
import io.github.gccammelli.pandy.loadedTrayIds
import io.github.gccammelli.pandy.slotForTrayId
import io.github.gccammelli.pandy.trayId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fetches a model file; a variable only so tests can supply or fail the download without a network. */
internal var downloadModel: suspend (String, String?) -> ByteArray = ::download

/** Reads a model's 3D preview meshes; a variable only so tests can slow or fail it. */
internal var loadPreviewMeshes: suspend (ByteArray) -> Map<Int, io.github.gccammelli.pandy.PreviewMesh> = { readPreviewMeshes(it) }

const val MAKERWORLD = "https://makerworld.com/en"

/**
 * MakerWorld, as on makerworld.com, signed in with the user's own Bambu account. A model's 3MF download, or its
 * "Open in Bambu Studio", comes to Pandy, which offers to slice and print it on the user's printer.
 */
@Composable
fun MakerWorld(modifier: Modifier) {
    val web = remember { WebState() }
    var printDownload by debugDownload
    var fileDownload by remember { mutableStateOf<WebDownload?>(null) }
    var sheetBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    val platform = LocalPlatform.current
    val st by Repo.state.collectAsState()
    web.canPrint = st.printer()?.let { it.online && !it.controlBlocked } ?: false
    web.overlayBack = if (printDownload != null) (sheetBack ?: { printDownload = null }) else null
    Box(modifier) {
        WebPage(MAKERWORLD, Modifier.fillMaxSize(), onDownload = { d ->
            if (d.printSheet) {
                if (printDownload == null) printDownload = d else platform.toast("Finish or close the current print first")
            } else {
                if (fileDownload == null) fileDownload = d else platform.toast("A download is already in progress")
            }
        }, state = web, printer = st.printer()?.model?.let(::makerWorldName))
        printDownload?.let { d -> PrintModelSheet(d, { printDownload = null }) { sheetBack = it } }
    }
    fileDownload?.let { d ->
        LaunchedEffect(d) {
            try {
                val bytes = d.bytes ?: withContext(Dispatchers.Default) { downloadModel(d.url!!, d.cookies) }
                platform.saveFile(d.name, downloadMime(d.name), bytes) { e -> platform.toast(e?.message ?: "Saved ${d.name}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                platform.toast(e.message ?: "Couldn't download ${d.name}")
            } finally {
                if (fileDownload === d) fileDownload = null
            }
        }
    }
}

/** Pandy's model names → MakerWorld's ("X1C" → "X1 Carbon"). */
fun makerWorldName(model: String) = if (model == "X1C") "X1 Carbon" else model

private fun downloadMime(name: String) = when (name.substringBefore('?').substringAfterLast('.').lowercase()) {
    "3mf" -> "model/3mf"
    "stl" -> "model/stl"
    "obj" -> "model/obj"
    "step", "stp" -> "model/step"
    "iges", "igs" -> "model/iges"
    "gcode" -> "application/octet-stream"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "application/octet-stream"
}

/** The file handed to the print sheet (also set by debug builds' launcher, for testing without a MakerWorld sign-in). */
val debugDownload = mutableStateOf<WebDownload?>(null)

/** Steps of printing a model, shown on the sheet's button. */
private enum class Step(val label: String) { Download("Downloading…"), Ready("Print"), Slicing("Slicing…"), Starting("Starting…") }

private enum class PrepareView { Profile, Prepare, Preview }
private enum class PreviewScheme { Model, Printer }

/**
 * Handy's "Print" sheet for a MakerWorld model: the plate (with its picture), the filaments and which AMS tray each
 * goes to, Handy's print options; then Pandy Slicer slices it and puts it on the printer, and the print starts.
 */
@Composable
fun PrintModelSheet(d: WebDownload, close: () -> Unit, slicerClient: io.github.gccammelli.pandy.SlicerClient? = Repo.slicer, registerBack: (((() -> Unit)?) -> Unit) = {}) {
    val st by Repo.state.collectAsState()
    val p = st.printer() ?: return close()
    val err = errorToast()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Download) }
    var data by remember { mutableStateOf<ByteArray?>(null) }
    var project by remember { mutableStateOf<Project?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var previewLoaded by remember { mutableStateOf(false) }
    var plate by remember { mutableStateOf(1) }
    var leveling by remember { mutableStateOf(true) }
    var flow by remember { mutableStateOf(true) } // on by default, as in Handy
    var timelapse by remember { mutableStateOf(Repo.timelapseDefault) }
    val printerBed = buildPlateLabel(p.state("sensor.print_bed_type"))
    var buildPlate by remember { mutableStateOf(printerBed ?: "Textured PEI Plate") }
    var code by remember { mutableStateOf("") }
    var view by remember { mutableStateOf(PrepareView.Profile) }
    var printerPicker by remember { mutableStateOf(false) }
    var platePicker by remember { mutableStateOf(false) }
    var filamentPicker by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<String?>(null) }
    var filamentIndex by remember { mutableStateOf(0) }
    var manualMapping by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var presets by remember { mutableStateOf(false) }
    var confirmPrint by remember { mutableStateOf(false) }
    val name = d.name.removeSuffix(".3mf").removeSuffix(".gcode")

    DisposableEffect(view, step) {
        registerBack(if (step == Step.Slicing || step == Step.Starting) ({}) else {
            {
                when (view) {
                    PrepareView.Profile -> close()
                    PrepareView.Prepare -> view = PrepareView.Profile
                    PrepareView.Preview -> view = PrepareView.Prepare
                }
            }
        })
        onDispose { registerBack(null) }
    }

    LaunchedEffect(d) {
        failed = null
        reading = false
        previewLoaded = false
        data = null
        project = null
        step = Step.Download
        view = PrepareView.Profile
        manualMapping = emptyMap()
        presets = false
        buildPlate = printerBed ?: "Textured PEI Plate"
        runCatching {
            val bytes = d.bytes ?: withContext(Dispatchers.Default) { downloadModel(d.url!!, d.cookies) }
            data = bytes
            reading = true
            val loaded = withContext(Dispatchers.Default) { readProject(bytes, preview = false) }
            manualMapping = Repo.filamentPreset(p.serial)
            presets = false
            project = loaded.also {
                plate = it.plates.first().index
                buildPlate = buildPlateLabel(it.plates.first().bedType) ?: printerBed ?: "Textured PEI Plate"
            }
            step = Step.Ready
        }.onFailure { failed = it.message ?: it.toString() }
    }

    // The 3D meshes are slow to read on big models, so they load only once the preview is opened.
    LaunchedEffect(view, project != null) {
        val bytes = data
        if (view != PrepareView.Preview || previewLoaded || project == null || bytes == null) return@LaunchedEffect
        val meshes = try {
            withContext(Dispatchers.Default) { loadPreviewMeshes(bytes) }
        } catch (e: CancellationException) {
            throw e // leaving the preview must not publish a half result
        } catch (e: Exception) {
            emptyMap()
        }
        previewLoaded = true
        if (data === bytes) project = project?.copy(meshes = meshes, mesh = meshes[plate] ?: meshes.values.firstOrNull())
    }

    // Home Assistant mode doesn't know the printer's access code; it's asked once, here.
    val savedLan = Repo.lan?.takeIf { it.serial.equals(p.serial, ignoreCase = true) }
    val lan = savedLan ?: if (code.length >= 6) p.state("sensor.ip_address")?.let { LanConfig(it, p.serial, code, p.model) } else null
    val slicer = slicerClient

    fun beginPrint() {
        val bytes = data ?: return
        val projectNow = project ?: return err("The model is still loading")
        val to = lan ?: return err("Enter the printer's access code")
        if (p.printing) return err("The printer is busy")
        val selectedPlate = plate
        val selectedTimelapse = timelapse
        val selectedLeveling = leveling
        val selectedFlow = flow
        val selectedBuildPlate = buildPlate
        val selectedPresets = presets
        step = Step.Slicing
        scope.launch {
            runCatching {
                val (path, sliced) = withContext(Dispatchers.Default) { slicer!!.sliceAndUpload(bytes, selectedPlate, name, to, io.github.gccammelli.pandy.printerPreset(p.model, p.state("sensor.nozzle_diameter")), buildPlateCode(selectedBuildPlate)) }
                step = Step.Starting
                val mapping = if (sliced == null) {
                    if (p.ams.isNotEmpty() && projectNow.filaments.isNotEmpty()) throw ApiException("The slicer returned no filament mapping; refusing to print")
                    null
                } else {
                    if (sliced.filaments.isEmpty() && projectNow.filaments.isNotEmpty()) throw ApiException("The slicer returned no filament mapping; refusing to print")
                    val auto = autoMapping(p, sliced.filaments)
                    val ids = sliced.filaments.mapIndexed { i, filament -> filament["id"]?.toIntOrNull() ?: i + 1 }
                    val valid = p.loadedTrayIds()
                    val chosen = sliced.filaments.mapIndexed { i, filament ->
                        (if (selectedPresets) manualMapping[filamentKey(filament["type"].orEmpty(), filament["color"].orEmpty())]?.takeIf { slot -> slot in valid } else null) ?: auto?.getOrNull(i) ?: if (p.ams.isEmpty()) 254 else -1
                    }
                    if (chosen.any { it < 0 }) throw ApiException("Load a matching filament before printing")
                    if (chosen.isEmpty()) null else List(ids.maxOrNull() ?: 0) { slot ->
                        ids.indexOf(slot + 1).let { i -> if (i < 0) -1 else chosen[i] }
                    }
                }
                withContext(Dispatchers.Default) { Repo.ha!!.startPrint(p, path, selectedPlate, mapping, selectedTimelapse, selectedLeveling, selectedFlow, buildPlateCode(selectedBuildPlate)) }
                if (Repo.lan == null) Repo.saveLan(to)
            }.onSuccess { close(); Nav.stack.clear(); Nav.tab = 1 }
                .onFailure { step = Step.Ready; err(it.message ?: it.toString()) }
        }
    }

    val pr = project
    if (failed != null) {
        PreparePage("Prepare to Print", close) {
            T(failed!!, 14.sp, C.Red, lh = 20.sp)
        }
        return
    }
    if (pr == null) {
        PreparePage("Prepare to Print", close) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = C.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp)); T(if (reading) "Reading the model…" else "Downloading the model…", 14.sp, C.Sub)
            }
        }
        return
    }

    val auto = autoMapping(p, pr.filaments.mapIndexed { i, (type, colour) -> mapOf("id" to "${i + 1}", "type" to type, "color" to colour) })
    val validSlots = p.loadedTrayIds()
    val manualReady = presets && pr.filaments.isNotEmpty() && pr.filaments.all { (type, colour) -> manualMapping[filamentKey(type, colour)] in validSlots }
    val mappingReady = pr.filaments.isEmpty() || auto != null || p.ams.isEmpty() || manualReady
    val setupNote = when {
        slicer == null -> "Printing MakerWorld models needs Pandy Slicer. Add it in Me › Settings."
        !mappingReady -> "Load matching filament types, or choose every model filament in Match my filament."
        else -> null
    }
    val setupGuide = "docs/slicer.md".takeIf { slicer == null }
    when (view) {
        PrepareView.Profile -> ProfileView(pr, name, close) { view = PrepareView.Prepare }
        PrepareView.Prepare -> PrepareView(
            p = p, project = pr, plate = plate, setPlate = { selected ->
                plate = selected
                buildPlate = pr.plates.firstOrNull { it.index == selected }?.bedType?.let(::buildPlateLabel) ?: printerBed ?: "Textured PEI Plate"
            }, buildPlate = buildPlate,
            auto = auto, manualMapping = manualMapping,
            presets = presets, setPresets = { presets = it }, preview = { view = PrepareView.Preview },
            printer = { printerPicker = true }, filament = { filamentIndex = it; filamentPicker = true },
            leveling = leveling, setLeveling = { leveling = it }, flow = flow, setFlow = { flow = it }, timelapse = timelapse,
            setTimelapse = { timelapse = it }, back = { view = PrepareView.Profile },
            print = {
                confirmPrint = true
            }, step = step, needsAccessCode = !Repo.demo && savedLan == null, canPrint = Repo.demo || (step == Step.Ready && p.online && lan != null && slicer != null && mappingReady), code = code,
            setCode = { code = it }, modelNotice = shortModel(pr.printerModel)?.takeIf { it != p.model }?.let { "Prepared for ${p.model}; the designer's ${it} profile will be converted by Pandy Slicer." },
            setupNote = setupNote, setupGuide = setupGuide, help = { help = it },
        )
        PrepareView.Preview -> PreviewView(p, pr, plate, auto, manualMapping, presets, back = { view = PrepareView.Prepare })
    }

    if (printerPicker) PrinterPicker(p, buildPlate, close = { printerPicker = false }, plate = { printerPicker = false; platePicker = true })
    if (platePicker) PlatePicker(buildPlate, close = { platePicker = false }) { buildPlate = it; platePicker = false }
    if (filamentPicker) FilamentPicker(p, pr, filamentIndex, auto, presets, manualMapping, close = { filamentPicker = false }) { slot ->
        val (type, colour) = pr.filaments[filamentIndex] // the picker opens from a filament chip
        manualMapping = manualMapping + (filamentKey(type, colour) to slot)
        presets = true
        Repo.setFilamentPreset(p.serial, manualMapping)
    }
    help?.let { topic -> HelpSheet(topic) { help = null } }
    if (confirmPrint) AlertDialog(
        onDismissRequest = { confirmPrint = false },
        containerColor = C.Card,
        title = { T(if (Repo.demo) "Start demo print?" else "Start print?", weight = FontWeight.Bold) },
        text = { T("Start ${name.ifBlank { "this model" }} on ${p.name}?", 14.sp, C.Sub) },
        confirmButton = {
            TextButton(onClick = {
                confirmPrint = false
                if (Repo.demo) {
                    Repo.act(err) { startPrint(p, "cache/$name.gcode.3mf", plate, null, timelapse, leveling, flow, buildPlateCode(buildPlate)) }
                    close(); Nav.stack.clear(); Nav.tab = 1
                } else if (slicer == null) err("Set up Pandy Slicer in Me › Settings first") else beginPrint()
            }) { T("Start", color = C.Accent, weight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = { confirmPrint = false }) { T("Cancel", color = C.Sub) } },
    )
}

@Composable
private fun PreparePage(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().background(C.Bg)) {
    PrepareHeader(title, close)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), content = content)
}

@Composable
private fun PrepareHeader(title: String, back: (() -> Unit)?) = Box(Modifier.fillMaxWidth().height(58.dp).background(C.Card)) {
    back?.let { BackChevron(it, Modifier.align(Alignment.CenterStart)) }
    T(title, 19.sp, weight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
    T("✦", 22.sp, C.Accent, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp))
}

@Composable
private fun ProfileView(project: Project, name: String, close: () -> Unit, next: () -> Unit) = Column(Modifier.fillMaxSize().background(C.Bg)) {
    PrepareHeader("Select a Print Profile", close)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
        T(name, 22.sp, weight = FontWeight.Bold, modifier = Modifier.padding(4.dp, 12.dp, 4.dp, 12.dp))
        Card(Modifier.padding(vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(18.dp), onClick = next) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(C.Accent)) { T("✓", 16.sp, C.OnAccent, FontWeight.Bold, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxSize()) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(project.profileName ?: "Selected profile", 18.sp, weight = FontWeight.Bold)
                    Gap(4.dp); T(project.profileSummary ?: "Matched to the selected printer", 14.sp, C.Sub)
                    Gap(8.dp); T("${project.plates.size} plate${if (project.plates.size == 1) "" else "s"} · ${project.filaments.size} color${if (project.filaments.size == 1) "" else "s"}", 13.sp, C.Sub)
                }
                T("›", 28.sp, C.Sub)
            }
        }
        Gap(14.dp)
        T("Profiles are matched to the selected printer before slicing.", 13.sp, C.Sub, lh = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
    Box(Modifier.padding(14.dp).navigationBarsPadding()) { AccentButton("Next", onClick = next) }
}

@Composable
private fun PrepareView(
    p: Printer, project: Project, plate: Int, setPlate: (Int) -> Unit, buildPlate: String,
    auto: List<Int>?, manualMapping: Map<String, Int>,
    presets: Boolean, setPresets: (Boolean) -> Unit, preview: () -> Unit, printer: () -> Unit, filament: (Int) -> Unit,
    leveling: Boolean, setLeveling: (Boolean) -> Unit, flow: Boolean, setFlow: (Boolean) -> Unit, timelapse: Boolean,
    setTimelapse: (Boolean) -> Unit, back: () -> Unit, print: () -> Unit, step: Step,
    canPrint: Boolean, needsAccessCode: Boolean, code: String, setCode: (String) -> Unit, modelNotice: String?,
    setupNote: String?, setupGuide: String?, help: (String) -> Unit,
) = Column(Modifier.fillMaxSize().background(C.Bg)) {
    val editable = step == Step.Ready
    PrepareHeader("Prepare to Print", back.takeIf { editable })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = printer.takeIf { editable }) {
            SectionTitle("Check printer and build plate type") { help("Printer and build plate") }
            Gap(12.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(66.dp).clip(RoundedCornerShape(10.dp)).background(C.Fill)) { PrinterArt(Modifier.fillMaxSize().padding(8.dp), p.info.ams.isNotEmpty(), p.lightOn, amsColors = p.ams.flatMap { it.second }.map { it?.takeIf { spool -> spool.known }?.color?.let(::Color) ?: Color(0xFFDDDDDD) }.take(4)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(if (p.online) C.Ok else C.Orange)); Spacer(Modifier.width(6.dp)); T(p.name, 16.sp, weight = FontWeight.Medium) }
                    Gap(3.dp); T(buildPlate, 14.sp, C.Sub)
                }
                T("›", 28.sp, C.Sub)
            }
            if (p.printing) { Gap(10.dp); T("Printer is busy", 14.sp, C.Red, weight = FontWeight.Medium) }
        }
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            SectionTitle("Select a plate to print") { help("Plate selection") }
            Gap(12.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                project.plates.forEach { PlateTile(it, selected = it.index == plate, enabled = editable, onClick = { setPlate(it.index) }) }
            }
        }
        modelNotice?.let { T(it, 13.sp, C.Sub, lh = 18.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            SectionTitle("Match my filament") { help("Filament matching") }
            Gap(10.dp)
            if (project.filaments.isEmpty()) T("No filament metadata was provided by the model.", 13.sp, C.Sub)
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                project.filaments.forEachIndexed { i, (type, colour) ->
                val slot = (if (presets) manualMapping[filamentKey(type, colour)]?.takeIf { it in p.loadedTrayIds() } else null) ?: auto?.getOrNull(i) ?: if (p.ams.isEmpty()) 254 else -1
                    FilamentChip(type, colour, slot, p, editable) { filament(i) }
                }
            }
            Gap(5.dp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Use my filament presets", 14.sp, if (editable) C.Text else C.Sub)
                Spacer(Modifier.width(4.dp)); HelpQuestion { help("Filament presets") }; Spacer(Modifier.weight(1f))
                Toggle(presets, editable, setPresets)
            }
        }
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(12.dp), onClick = preview.takeIf { editable }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val img = remember(project, plate) { project.plates.firstOrNull { it.index == plate }?.picture?.let { decodeImage(it, 300) } }
                Box(Modifier.size(98.dp).clip(RoundedCornerShape(10.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else T("3D", 20.sp, C.Faint, FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                    T("In the 3D preview, you can change the color scheme and view the model parameters.", 13.sp, C.Sub, lh = 17.sp)
                    Gap(8.dp)
                    Box(Modifier.width(132.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
                        T("3D Preview", 14.sp, weight = FontWeight.Medium)
                    }
                }
            }
        }
        Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            T("Print options", 16.sp, weight = FontWeight.Medium)
            ToggleLine("Bed leveling", leveling, enabled = editable, change = setLeveling)
            ToggleLine("Flow dynamics calibration", flow, enabled = editable, change = setFlow)
            ToggleLine("Timelapse", timelapse, enabled = editable, change = setTimelapse)
        }
        if (needsAccessCode) {
            Card(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                OutlinedTextField(code, setCode, Modifier.fillMaxWidth(), enabled = editable, singleLine = true, label = { T("Printer access code", 14.sp, C.Sub) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Gap(6.dp); T("Needed only when Pandy is not already connected directly to this printer.", 12.sp, C.Sub, lh = 17.sp)
            }
        }
        if (step == Step.Slicing || step == Step.Starting) T(step.label, 13.sp, C.Sub, modifier = Modifier.padding(22.dp, 8.dp))
        else if (!canPrint && !Repo.demo) setupNote?.let { note ->
            if (setupGuide != null) SetupNote(note, setupGuide, Modifier.padding(14.dp))
            else T(note, 13.sp, C.Sub, lh = 18.sp, modifier = Modifier.padding(22.dp, 8.dp))
        }
        Gap(8.dp)
    }
    Row(Modifier.fillMaxWidth().background(C.Card).padding(14.dp).navigationBarsPadding()) {
        AccentButton(if (Repo.demo) "Print (demo)" else "Print current plate", Modifier.fillMaxWidth(), enabled = canPrint && !p.printing, onClick = print)
    }
}

@Composable
private fun PlateTile(plate: io.github.gccammelli.pandy.ProjectPlate, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.width(145.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, if (selected) C.Accent else C.Line, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(7.dp)) {
        val img = remember(plate.picture) { plate.picture?.let { decodeImage(it, 300) } }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else T("Plate ${plate.index}", 18.sp, C.Faint, FontWeight.Bold)
            if (selected) Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(RoundedCornerShape(bottomStart = 10.dp)).background(C.Accent), contentAlignment = Alignment.Center) { T("✓", 16.sp, C.OnAccent, FontWeight.Bold) }
        }
        Gap(8.dp)
        T(plate.name.ifBlank { "Plate ${plate.index}" }, 16.sp, C.Text, maxLines = 1)
        if (plate.seconds != null || plate.grams != null) {
            Gap(5.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                plate.seconds?.let { T("◷ ${durationText(it)}", 12.sp, C.Sub, maxLines = 1) }
                plate.grams?.let { T("◎ ${gramsText(it)}", 12.sp, C.Sub, maxLines = 1) }
            }
        }
        Gap(9.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Gap(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parts = plate.parts
            if (parts != null) {
                T("$parts", 14.sp, if (selected) C.Accent else C.Sub)
                T("/$parts parts", 14.sp, C.Sub, modifier = Modifier.weight(1f))
            } else {
                T("Ready", 13.sp, C.Sub, modifier = Modifier.weight(1f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Sub)
        }
    }
}

private fun durationText(seconds: Int): String = if (seconds >= 3600) "${(seconds / 360.0).roundToInt() / 10.0}h" else "${(seconds + 59) / 60} min"

private fun gramsText(grams: Double): String = "${if (grams == grams.toInt().toDouble()) grams.toInt() else ((grams * 10).roundToInt() / 10.0)}g"

@Composable
private fun SectionTitle(title: String, help: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    T(title, 18.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
    HelpQuestion(help)
}

@Composable
private fun HelpQuestion(onClick: () -> Unit) = Box(Modifier.size(30.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Help", Modifier.size(22.dp), tint = C.Sub)
}

@Composable
private fun FilamentChip(type: String, colour: String, slot: Int, p: Printer, enabled: Boolean, onClick: () -> Unit) {
    val loadedAms = if (slot >= 0) p.ams.asSequence().mapNotNull { (ams, spools) ->
        ams.slotForTrayId(slot)?.let { ams to spools.getOrNull(it) }
    }.firstOrNull() else null
    val loaded = when {
        slot == 254 -> p.external.firstOrNull { it.known }
        else -> loadedAms?.second
    }
    val trayLabel = when {
        slot == 254 -> "Ext"
        loadedAms == null -> "--"
        loadedAms.first.isSingleSlot() -> "HT"
        else -> "${'A' + loadedAms.first.index - 1}${loadedAms.first.slotForTrayId(slot)!! + 1}"
    }
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.Line, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(31.dp).background(C.Sub).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
            T(type, 14.sp, Color.White, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(hexColor(colour))))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                T(trayLabel, 12.sp, C.Text, FontWeight.Medium)
                T(loaded?.type ?: type, 12.sp, C.Sub, maxLines = 1)
            }
            T("⌄", 18.sp, C.Sub)
        }
    }
}

@Composable
private fun HelpSheet(topic: String, close: () -> Unit) = Sheet(close, topic, centeredTitle = true, confirm = "Got it", onConfirm = close) {
    T(when (topic) {
        "Printer and build plate" -> "Choose the printer and the build plate installed on it. The plate type is sent to the printer when a direct-LAN print starts."
        "Plate selection" -> "Choose one plate from the project. The time, filament weight and part count come from the downloaded 3MF when the designer included them."
        "Filament matching" -> "Each model filament must map to a loaded AMS tray, or to the external spool on printers without an AMS. Pandy refuses a mismatched mapping before it starts a print."
        else -> "Presets remember your chosen tray for this printer and filament colour. They are only used when the selected tray is still loaded."
    }, 14.sp, C.Sub, lh = 21.sp)
}

@Composable
private fun PreviewView(p: Printer, project: Project, plate: Int, auto: List<Int>?, manualMapping: Map<String, Int>, presets: Boolean, back: () -> Unit) {
    var showPlate by remember { mutableStateOf(true) }
    var scheme by remember { mutableStateOf(PreviewScheme.Model) }
    var custom by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var colors by remember { mutableStateOf(false) }
    val modelColors = project.filaments.map { (_, colour) -> hexColor(colour) }
    fun spoolColor(slot: Int): Long? = when {
        slot == 254 -> p.external.firstOrNull { it.known }?.color
        slot >= 0 -> p.ams.asSequence().mapNotNull { (ams, spools) ->
            ams.slotForTrayId(slot)?.let { spools.getOrNull(it)?.takeIf { spool -> spool.known }?.color }
        }.firstOrNull()
        else -> null
    }
    val validSlots = p.loadedTrayIds()
    val printerColors = project.filaments.mapIndexed { i, (type, colour) ->
        val slot = (if (presets) manualMapping[filamentKey(type, colour)]?.takeIf { it in validSlots } else null)
            ?: auto?.getOrNull(i)?.takeIf { it in validSlots } ?: -1
        spoolColor(slot) ?: hexColor(colour)
    }
    val mesh = project.meshes[plate] ?: project.mesh?.takeIf { project.meshes.isEmpty() }
    val displayColors = project.filaments.indices.map { i ->
        custom[i] ?: if (scheme == PreviewScheme.Printer) printerColors.getOrNull(i) ?: modelColors.getOrElse(i) { 0xFF888888 } else modelColors.getOrElse(i) { 0xFF888888 }
    }
    val image = remember(project, plate) { project.plates.firstOrNull { it.index == plate }?.picture?.let { decodeImage(it, 700) } }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
    PrepareHeader("3D Preview", back)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Show the plate", 16.sp, C.Sub, modifier = Modifier.weight(1f))
            Toggle(showPlate, onChange = { showPlate = it })
        }
        Card(radius = 16.dp, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)).background(C.Fill), contentAlignment = Alignment.Center) {
                mesh?.let { PreviewMeshCanvas(it, displayColors, showPlate) } ?: if (showPlate && image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else T("3D preview unavailable", 24.sp, C.Faint, FontWeight.Bold)
            }
            Gap(12.dp); T("Color scheme", 16.sp, weight = FontWeight.Medium)
            Gap(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewChoice("Model colors", scheme == PreviewScheme.Model) { scheme = PreviewScheme.Model }
                PreviewChoice("Printer colors", scheme == PreviewScheme.Printer) { scheme = PreviewScheme.Printer }
            }
            Gap(12.dp)
            Row(Modifier.fillMaxWidth().clickable { colors = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Customize colors", 15.sp, modifier = Modifier.weight(1f)); T("Reset", 13.sp, C.Blue, modifier = Modifier.clickable { custom = emptyMap(); scheme = PreviewScheme.Model }.padding(8.dp)); T("›", 24.sp, C.Sub)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                displayColors.ifEmpty { listOf(0xFF888888) }.forEach { colour -> Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, C.Line, CircleShape).background(Color(colour))) }
            }
        }
    }
    Box(Modifier.padding(14.dp).navigationBarsPadding()) { AccentButton("Confirm", onClick = back) }
    }
    if (colors) PreviewColorsSheet(project, p, displayColors, close = { colors = false }) { index, colour -> custom = custom + (index to colour) }
}

@Composable
private fun RowScope.PreviewChoice(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (selected) C.AccentBg else C.Fill).clickable(onClick = onClick).padding(vertical = 11.dp),
    contentAlignment = Alignment.Center,
) { T(label, 13.sp, if (selected) C.Accent else C.Text, FontWeight.Medium) }

@Composable
private fun PreviewColorsSheet(project: Project, p: Printer, current: List<Long>, close: () -> Unit, choose: (Int, Long) -> Unit) = Sheet(
    close, "Customize colors", centeredTitle = true, confirm = "Done", onConfirm = close,
) {
    val palette = (p.ams.flatMap { it.second }.mapNotNull { it?.takeIf { spool -> spool.known }?.color } + p.external.filter { it.known }.map { it.color } + project.filaments.map { (_, colour) -> hexColor(colour) } + current).distinct()
    if (project.filaments.isEmpty()) T("This model has no filament color metadata.", 14.sp, C.Sub)
    project.filaments.forEachIndexed { index, (type, _) ->
        T("${index + 1}. $type", 14.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEach { colour ->
                Box(Modifier.size(36.dp).clip(CircleShape).border(if (current.getOrNull(index) == colour) 3.dp else 1.dp, if (current.getOrNull(index) == colour) C.Accent else C.Line, CircleShape)
                    .background(Color(colour)).clickable { choose(index, colour) })
            }
        }
    }
}

@Composable
private fun PreviewMeshCanvas(mesh: PreviewMesh, colors: List<Long>, showPlate: Boolean) {
    var yaw by remember(mesh) { mutableFloatStateOf(-0.55f) }
    var pitch by remember(mesh) { mutableFloatStateOf(0.65f) }
    var zoom by remember(mesh) { mutableFloatStateOf(1f) }
    val vertices = remember(mesh) { mesh.triangles.flatMap { listOf(it.a, it.b, it.c) } }
    val minX = remember(mesh) { vertices.fold(Float.MAX_VALUE) { m, v -> minOf(m, v.x) } }
    val maxX = remember(mesh) { vertices.fold(-Float.MAX_VALUE) { m, v -> maxOf(m, v.x) } }
    val minY = remember(mesh) { vertices.fold(Float.MAX_VALUE) { m, v -> minOf(m, v.y) } }
    val maxY = remember(mesh) { vertices.fold(-Float.MAX_VALUE) { m, v -> maxOf(m, v.y) } }
    val minZ = remember(mesh) { vertices.fold(Float.MAX_VALUE) { m, v -> minOf(m, v.z) } }
    val maxZ = remember(mesh) { vertices.fold(-Float.MAX_VALUE) { m, v -> maxOf(m, v.z) } }
    Box(Modifier.fillMaxSize().pointerInput(mesh) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
            yaw += pan.x * 0.01f
            pitch = (pitch - pan.y * 0.01f).coerceIn(-1.35f, 1.35f)
            zoom = (zoom * gestureZoom).coerceIn(.65f, 3f)
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            if (showPlate) drawRect(C.Track, topLeft = Offset(size.width * .08f, size.height * .78f), size = Size(size.width * .84f, size.height * .03f))
            val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f; val cz = (minZ + maxZ) / 2f
            val range = maxOf(maxX - minX, maxOf(maxY - minY, maxZ - minZ)).coerceAtLeast(.001f)
            val scale = minOf(size.width, size.height) * .72f * zoom / range
            val sinYaw = sin(yaw); val cosYaw = cos(yaw); val sinPitch = sin(pitch); val cosPitch = cos(pitch)
            fun project(v: io.github.gccammelli.pandy.PreviewVertex): Triple<Float, Float, Float> {
                val x = v.x - cx; val y = v.y - cy; val z = v.z - cz
                val rx = x * cosYaw - y * sinYaw
                val rz = x * sinYaw + y * cosYaw
                val ry = z * cosPitch - rz * sinPitch
                val depth = z * sinPitch + rz * cosPitch
                return Triple(size.width / 2f + rx * scale, size.height / 2f - ry * scale, depth)
            }
            mesh.triangles.mapIndexed { index, triangle ->
                val a = project(triangle.a); val b = project(triangle.b); val c = project(triangle.c)
                Triple((a.third + b.third + c.third) / 3f, index, listOf(a, b, c))
            }.sortedBy { it.first }.forEach { (_, index, points) ->
                val path = Path().apply { moveTo(points[0].first, points[0].second); lineTo(points[1].first, points[1].second); lineTo(points[2].first, points[2].second); close() }
                val triangle = mesh.triangles[index]
                val material = triangle.material?.takeIf { it in colors.indices } ?: 0
                val colour = colors.getOrNull(material) ?: 0xFF888888
                drawPath(path, Color(colour).copy(alpha = .94f))
            }
        }
    }
}

@Composable
private fun PrinterPicker(p: Printer, currentPlate: String, close: () -> Unit, plate: () -> Unit) = Sheet(close, "Select printer", centeredTitle = true, confirm = "Confirm", onConfirm = close) {
    Card(radius = 12.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(if (p.online) C.Ok else C.Orange)); Spacer(Modifier.width(8.dp)); T(p.name, 15.sp, weight = FontWeight.Medium); Spacer(Modifier.weight(1f)); T(p.model, 13.sp, C.Sub) }
    }
    Gap(8.dp); NavRow("Build plate", currentPlate) { plate() }
}

@Composable
private fun PlatePicker(current: String, close: () -> Unit, choose: (String) -> Unit) = Sheet(close, "Select build plate", centeredTitle = true, confirm = null) {
    listOf("Textured PEI Plate", "Smooth PEI Plate / High Temp Plate", "Cool Plate SuperTack", "Engineering Plate", "Cool Plate").forEachIndexed { i, label ->
        val selected = label == current
        Row(Modifier.fillMaxWidth().clickable { choose(label) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) C.AccentBg else C.Fill), contentAlignment = Alignment.Center) { T("${i + 1}", 13.sp, if (selected) C.Accent else C.Sub, FontWeight.Bold) }
            Spacer(Modifier.width(12.dp)); T(label, 15.sp, modifier = Modifier.weight(1f)); if (selected) T("✓", 18.sp, C.Accent, FontWeight.Bold)
        }
    }
}

@Composable
private fun FilamentPicker(p: Printer, project: Project, index: Int, auto: List<Int>?, presets: Boolean, selected: Map<String, Int>, close: () -> Unit, choose: (Int) -> Unit) = Sheet(close, "Please select filament", centeredTitle = true, confirm = "Confirm", onConfirm = close) {
    run {
        val (type, colour) = project.filaments[index]
        val current = (if (presets) selected[filamentKey(type, colour)] else null) ?: auto?.getOrNull(index) ?: -1
        T("$type · choose a loaded spool", 13.sp, C.Sub, modifier = Modifier.padding(bottom = 8.dp))
        p.ams.flatMap { (ams, spools) -> spools.mapIndexedNotNull { slot, spool -> spool?.takeIf { it.known }?.let { ams to (ams.trayId(slot) to it) } } }.forEach { (ams, tray) ->
            val (slot, spool) = tray
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(slot) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(spool.color)))
                Spacer(Modifier.width(10.dp)); T(if (ams.model.contains("HT", ignoreCase = true) || ams.model.contains("N3S", ignoreCase = true)) "AMS HT" else "AMS ${ams.index} · slot ${slot % 4 + 1}", 14.sp, modifier = Modifier.weight(1f)); T(spool.type, 13.sp, C.Sub); if (slot == current) { Spacer(Modifier.width(8.dp)); T("✓", 18.sp, C.Accent, FontWeight.Bold) }
            }
        }
        p.external.forEachIndexed { _, spool ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(254) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(spool.color))); Spacer(Modifier.width(10.dp)); T("External spool", 14.sp, modifier = Modifier.weight(1f)); T(spool.type, 13.sp, C.Sub); if (current == 254) { Spacer(Modifier.width(8.dp)); T("✓", 18.sp, C.Accent, FontWeight.Bold) }
            }
        }
    }
}

/** Me › Settings › Slicer: the Pandy Slicer add-on through Home Assistant, or a slicer's address and optional token, with a check. */
@Composable
fun SlicerScreen() = Page("Slicer", { Nav.back() }) {
    var url by remember { mutableStateOf(Repo.slicerUrl ?: "") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checkedAddOn by remember { mutableStateOf(false) } // which check [status] belongs to
    val scope = rememberCoroutineScope()
    val pl = LocalPlatform.current
    val ha = Repo.haApi
    fun check(c: io.github.gccammelli.pandy.SlicerClient, saved: () -> Unit = {}) {
        busy = true; status = null; checkedAddOn = c.viaHa
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { c.health() } }
                .onSuccess { v -> saved(); status = "Connected: Bambu Studio $v" }
                .onFailure { status = it.message ?: it.toString() }
            busy = false
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp)) {
        if (ha != null) {
            T("Pandy prints MakerWorld models by having Pandy Slicer (Bambu Studio's slicer in a container) slice them and put them on the printer. Install the Pandy Slicer add-on in Home Assistant: Pandy reaches it through Home Assistant, so there's nothing to enter here.", 14.sp, C.Sub, lh = 20.sp)
            Gap(16.dp)
            AccentButton(if (busy && checkedAddOn) "Checking…" else "Check the add-on", enabled = !busy) { check(io.github.gccammelli.pandy.SlicerClient(null, null, ha)) }
            if (status != null && checkedAddOn) { Gap(12.dp); T(status!!, 14.sp, if (status!!.startsWith("Connected")) C.Ok else C.Red) }
            Gap(28.dp)
            T("Slicer elsewhere (Docker)", 13.sp, C.Sub)
            Gap(4.dp)
            T("Only if it doesn't run in Home Assistant. An address here is used instead of the add-on.", 12.sp, C.Sub, lh = 17.sp)
        } else T("Pandy prints MakerWorld models by having Pandy Slicer (Bambu Studio's slicer in a container) slice them and put them on the printer. Run it as a Home Assistant add-on or with Docker on any x86-64 computer, then enter its address.", 14.sp, C.Sub, lh = 20.sp)
        Gap(16.dp)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { T("Slicer address (e.g. slicer.local:8737)", 14.sp, C.Sub) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Gap(10.dp)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, label = { T("Token (if you set one)", 14.sp, C.Sub) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (status != null && !checkedAddOn) { Gap(12.dp); T(status!!, 14.sp, if (status!!.startsWith("Connected")) C.Ok else C.Red) }
        Gap(20.dp)
        AccentButton(if (busy && !checkedAddOn) "Checking…" else "Save", enabled = !busy && url.isNotBlank()) {
            check(io.github.gccammelli.pandy.SlicerClient(url, token.ifBlank { null })) { Repo.setSlicer(url, token.ifBlank { null }) }
        }
        if (Repo.slicerUrl != null) { Gap(12.dp); OutlineButton("Remove", C.Red) { Repo.setSlicer(null, null); url = ""; status = null } }
        Gap(16.dp)
        T("Setup guide ›", 14.sp, C.Blue, modifier = Modifier.clickable { pl.openUrl(guide("docs/slicer.md")) })
    }
}
