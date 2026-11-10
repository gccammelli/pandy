// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.gcode.GcodeModel
import io.github.gccammelli.pandy.studio.gcode.GcodeParser
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.io.ThreeMf
import io.github.gccammelli.pandy.studio.io.Zip
import io.github.gccammelli.pandy.studio.io.SceneObject
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.slicer.Preset
import io.github.gccammelli.pandy.studio.slicer.PresetLibrary
import io.github.gccammelli.pandy.studio.slicer.PrinterModel
import io.github.gccammelli.pandy.studio.slicer.Safety
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.slicer.SliceJob
import io.github.gccammelli.pandy.studio.slicer.SliceRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SliceState {
    data object Idle : SliceState()
    data class Running(val status: String, val progress: Float?) : SliceState()
    class Done(val run: SliceRun, val gcode: GcodeModel, val warnings: List<String>) : SliceState()
    data class Failed(val message: String) : SliceState()
}

/** The app's state: presets, the plate, the user's setting changes and the last slice. */
class Studio(private val platform: Platform, private val scope: CoroutineScope) {
    val scene = Scene()
    var library by mutableStateOf<PresetLibrary?>(null); private set
    var schema by mutableStateOf<io.github.gccammelli.pandy.studio.slicer.Schema?>(null); private set
    var printers by mutableStateOf<List<PrinterModel>>(emptyList()); private set
    var printer by mutableStateOf<Preset?>(null); private set
    var processes by mutableStateOf<List<Preset>>(emptyList()); private set
    var process by mutableStateOf<Preset?>(null); private set
    var filamentChoices by mutableStateOf<List<Preset>>(emptyList()); private set
    /** The plate's filaments (AMS slots), each a system preset and a display colour. */
    val filaments = mutableStateListOf<Pair<Preset, String>>()
    /** The user's process changes over the selected process preset, as Bambu Studio marks them modified. */
    val overrides = mutableStateMapOf<String, Setting>()
    var state by mutableStateOf<SliceState>(SliceState.Idle)
    var loadError by mutableStateOf<String?>(null)
    var engine by mutableStateOf<io.github.gccammelli.pandy.studio.slicer.EngineInfo?>(null); private set
    private var sliceJob: Job? = null

    /** [preferredPrinter]: the preset to start with when the user hasn't chosen one (the connected printer's). */
    fun load(preferredPrinter: String? = null) = scope.launch {
        loadError = null
        runCatching {
            engine = platform.engineInfo()
            val source = platform.profiles()
            val lib = withContext(Dispatchers.Default) { PresetLibrary(source).also { it.printers() } }
            schema = withContext(Dispatchers.Default) { io.github.gccammelli.pandy.studio.slicer.Schema.load(source) }
            library = lib
            printers = lib.printers()
            val saved = platform.loadSetting("printer")
            val p = printers.flatMap { it.nozzles }.firstOrNull { it.name == saved }
                ?: printers.flatMap { it.nozzles }.firstOrNull { it.name == preferredPrinter }
                ?: printers.flatMap { it.nozzles }.firstOrNull { it.name == "Bambu Lab X1 Carbon 0.4 nozzle" } ?: printers.first().nozzles.first()
            choosePrinter(p)
        }.onFailure { loadError = it.message ?: it.toString() }
    }

    fun choosePrinter(p: Preset) {
        val lib = library ?: return
        printer = p
        platform.saveSetting("printer", p.name)
        scene.bed = io.github.gccammelli.pandy.studio.studio.Bed.of((p["printable_area"] as? Setting.Many)?.values, p.string("printable_height"))
        processes = lib.processes(p.name)
        process = processes.firstOrNull { it.name == p.string("default_print_profile") } ?: processes.firstOrNull { it.name.startsWith("0.20mm Standard") } ?: processes.firstOrNull()
        filamentChoices = lib.filaments(p.name).sortedWith(compareBy({ !it.name.startsWith("Bambu") }, { it.name }))
        val default = (p["default_filament_profile"] as? Setting.Many)?.values?.firstOrNull() ?: p.string("default_filament_profile")
        val keep = filaments.map { (f, c) -> (filamentChoices.firstOrNull { it.name.substringBefore(" @") == f.name.substringBefore(" @") } ?: f) to c }
        filaments.clear()
        if (keep.isNotEmpty() && keep.all { (f, _) -> f in filamentChoices }) filaments.addAll(keep)
        else (filamentChoices.firstOrNull { it.name == default } ?: filamentChoices.firstOrNull())?.let { filaments.add(it to colorOf(it)) }
        syncColors(); invalidate()
    }

    fun chooseProcess(p: Preset) { process = p; invalidate() }

    fun setFilament(slot: Int, preset: Preset, color: String = colorOf(preset)) {
        if (slot < filaments.size) filaments[slot] = preset to color else filaments.add(preset to color)
        syncColors(); invalidate()
    }

    fun setColor(slot: Int, color: String) { filaments.getOrNull(slot)?.let { filaments[slot] = it.first to color }; syncColors() }

    fun removeFilament(slot: Int) {
        if (filaments.size <= 1) return
        filaments.removeAt(slot)
        scene.objects.forEach { if (it.filament >= filaments.size) it.filament = 0 }
        syncColors(); invalidate()
    }

    private fun syncColors() { scene.colors = filaments.map { it.second }; scene.touch() }
    private fun colorOf(p: Preset) = p.string("default_filament_colour")?.takeIf { it.startsWith("#") } ?: "#E0409C"

    /** The value the slice will use for a process option: the user's change, else the preset's. */
    fun value(key: String): Setting? = overrides[key] ?: process?.get(key)

    fun set(key: String, value: Setting) {
        if (key in io.github.gccammelli.pandy.studio.slicer.BLOCKED_OPTIONS) return
        if (process?.get(key) == value) overrides.remove(key) else overrides[key] = value
        invalidate()
    }

    fun reset(key: String) { overrides.remove(key); invalidate() }

    /** Any change to the plate or settings makes the last slice stale. */
    fun invalidate() {
        sliceJob?.cancel()
        if (state !is SliceState.Idle) state = SliceState.Idle
        if (scene.mode == ViewMode.PREVIEW) scene.mode = ViewMode.PREPARE
        scene.preview = null
    }

    /** Adds the models in a picked file (STL or 3MF) to the plate. */
    fun addSample(name: String, mesh: io.github.gccammelli.pandy.studio.model.Mesh) {
        val po = PlateObject.of(scene.newId(), SceneObject(name, mesh, Mat4.identity()))
        po.x = scene.bed.centerX; po.y = scene.bed.centerY
        scene.add(po)
        if (scene.objects.size > 1) scene.arrange()
        invalidate()
    }

    fun duplicate(id: Long) {
        val o = scene.objects.firstOrNull { it.id == id } ?: return
        scene.add(o.copy(scene.newId())); scene.arrange(); invalidate()
    }

    fun addFile(name: String, bytes: ByteArray) {
        val objects = if (name.lowercase().endsWith(".3mf")) {
            requirePlainEditorModel(bytes)
            ThreeMf.read(bytes)
        }
        else listOf(SceneObject(name.substringBeforeLast('.'), Stl.read(bytes), Mat4.identity()))
        for (o in objects) {
            val po = PlateObject.of(scene.newId(), o)
            // Drop new models in the middle of the bed when they come from a file with no placement of their own.
            if (!name.lowercase().endsWith(".3mf") || objects.size == 1) { po.x = scene.bed.centerX; po.y = scene.bed.centerY }
            scene.add(po)
        }
        if (scene.objects.size > 1 && objects.size >= 1 && !name.lowercase().endsWith(".3mf")) scene.arrange()
        invalidate()
    }

    fun job(): SliceJob? {
        val p = printer ?: return null; val pr = process ?: return null
        if (filaments.isEmpty() || scene.objects.isEmpty()) return null
        return SliceJob(ThreeMf.writePlain(scene.sceneObjects()), p.name, pr.name, filaments.map { it.first.name }, overrides.toMap(),
            printerIncludes = library?.includes(p.name).orEmpty())
    }

    /** The plate's filaments as a print needs them: slot (from 1), type and colour of each one an object uses. */
    fun usedFilaments(): List<Map<String, String>> =
        scene.objects.map { it.filament }.distinct().sorted().ifEmpty { listOf(0) }.mapNotNull { i ->
            filaments.getOrNull(i)?.let { (preset, color) ->
                mapOf("id" to "${i + 1}", "type" to (preset.string("filament_type") ?: preset.name.substringBefore(" @")), "color" to color)
            }
        }

    /** Whether there is something to slice (cheap: does not build the job). */
    val canSlice get() = printer != null && process != null && filaments.isNotEmpty() && scene.objects.isNotEmpty()

    fun slice() {
        val job = job() ?: return
        sliceJob?.cancel()
        state = SliceState.Running("Preparing", null)
        sliceJob = scope.launch {
            val run = try {
                platform.slice(job) { line -> progressOf(line)?.let { (status, p) -> state = SliceState.Running(status, p) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = SliceState.Failed(e.message ?: e.toString()); return@launch
            }
            ensureActive()
            if (!run.ok) {
                state = SliceState.Failed(run.outcome?.message?.takeIf { it.isNotBlank() && it != "Success." } ?: ("The slicer stopped (code ${run.exitCode})." + run.log.takeLast(3).joinToString("\n", "\n")))
                return@launch
            }
            state = SliceState.Running("Loading preview", 1f)
            val (model, warnings) = withContext(Dispatchers.Default) {
                val text = Zip.read(run.sliced!!)["Metadata/plate_1.gcode"]?.decodeToString() ?: ""
                GcodeParser.parse(text) to Safety.problems(text)
            }
            scene.preview = model
            scene.layerLow = 1; scene.layerHigh = model.layers.size
            scene.mode = ViewMode.PREVIEW
            state = SliceState.Done(run, model, warnings)
        }
    }

    fun cancel() { sliceJob?.cancel(); state = SliceState.Idle }

    companion object {
        /** Bambu Studio's CLI logs its steps; these map to the progress shown while slicing. */
        private val STEPS = listOf(
            "load_" to ("Reading the plate" to .05f), "Initializing StaticPrintConfigs" to ("Starting" to .02f),
            "slicing" to ("Slicing layers" to .25f), "perimeters" to ("Generating walls" to .4f), "infill" to ("Generating infill" to .55f),
            "support" to ("Generating supports" to .65f), "export_gcode" to ("Writing G-code" to .8f), "gcode" to ("Writing G-code" to .8f),
            "export_3mf" to ("Packing the file" to .95f),
        )

        fun progressOf(line: String): Pair<String, Float>? = STEPS.firstOrNull { line.contains(it.first, ignoreCase = true) }?.second
    }
}
