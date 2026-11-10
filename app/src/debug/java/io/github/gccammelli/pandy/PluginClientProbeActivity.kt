package io.github.gccammelli.pandy

import android.app.Activity
import android.os.Bundle
import io.github.gccammelli.pandy.studio.io.Zip
import io.github.gccammelli.pandy.studio.slicer.PresetLibrary
import io.github.gccammelli.pandy.studio.slicer.Safety
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.slicer.SliceJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Testbed-only exercise of the production client; it never contacts a printer. */
class PluginClientProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.APPLICATION_ID.endsWith(".testbed")) { finish(); return }
        val dir = File(cacheDir, "phone-probe").apply { mkdirs() }
        val status = File(dir, "client-status.txt")
        status.writeText("running")
        scope.launch {
            try {
                val client = StudioPluginClient(applicationContext)
                check(client.available()) { "Plugin is not visible" }
                check(phoneSettings(PhoneSlicerResponse(client.capabilities().toString(), null)).any { it.key == "wall_loops" && it.valid("3") })
                val profiles = client.profiles()
                val printer = "Bambu Lab X1 Carbon 0.4 nozzle"
                val lib = withContext(Dispatchers.IO) { PresetLibrary(profiles) }
                val job = withContext(Dispatchers.IO) {
                    SliceJob(File(dir, "model.3mf").readBytes(), printer, "0.20mm Standard @BBL X1C",
                        listOf("Bambu PLA Basic @BBL X1C"), mapOf("wall_loops" to Setting.One("3")),
                        printerIncludes = lib.includes(printer))
                }
                val result = client.slice(job) {}
                withContext(Dispatchers.IO) {
                    check(result.ok)
                    val file = result.sliced ?: error("No sliced file")
                    val gcode = Zip.read(file)["Metadata/plate_1.gcode"]?.decodeToString() ?: error("No G-code")
                    check(Safety.problems(gcode).isEmpty()) { "G-code safety check failed" }
                    File(dir, "client-result.3mf").writeBytes(file)
                    status.writeText("editor ok; testing server slice")
                }
                val request = phoneSliceRequest(1, "demo", "Bambu Lab A1 mini 0.4 nozzle", "textured_plate", "PLA", "#FFFFFF", mapOf("wall_loops" to "3"))
                val server = client.serverSlice(request, job.model3mf)
                val (file, plate) = withContext(Dispatchers.Default) {
                    phoneSliceResult(server, mapOf("wall_loops" to "3"), 1, "Bambu Lab A1 mini 0.4 nozzle", "textured_plate")
                }
                withContext(Dispatchers.IO) {
                    File(dir, "client-server-result.3mf").writeBytes(file)
                    val entries = Zip.read(file)
                    val actual = org.json.JSONObject(entries.getValue("Metadata/project_settings.config").decodeToString())
                    val preset = lib.preset("0.20mm Standard @BBL A1M") ?: error("A1 mini process preset missing")
                    check(actual.getJSONArray("outer_wall_speed").getString(0) == preset.string("outer_wall_speed")) { "Server slice ignored its process preset" }
                    status.writeText("ok: profiles, editor and server slice; plate ${plate.index}")
                }
            } catch (e: Exception) { status.writeText("failed: ${e.message ?: e::class.simpleName}") }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
