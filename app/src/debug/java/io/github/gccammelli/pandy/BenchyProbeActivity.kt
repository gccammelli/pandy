package io.github.gccammelli.pandy

import android.app.Activity
import android.os.Bundle
import io.github.gccammelli.pandy.studio.io.SceneObject
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.io.ThreeMf
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.slicer.PresetLibrary
import io.github.gccammelli.pandy.studio.slicer.SliceJob
import io.github.gccammelli.pandy.studio.studio.PlateObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Testbed-only byte comparison probe. This activity never connects to a printer. */
class BenchyProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.APPLICATION_ID.endsWith(".testbed")) { finish(); return }
        val dir = File(cacheDir, "benchy-probe").apply { mkdirs() }
        val status = File(dir, "status.txt")
        status.writeText("loading profiles")
        scope.launch {
            try {
                val client = StudioPluginClient(applicationContext)
                val profiles = client.profiles()
                status.writeText("preparing model")
                val (job, library) = withContext(Dispatchers.Default) {
                    val printer = "Bambu Lab X1 Carbon 0.4 nozzle"
                    val library = PresetLibrary(profiles)
                    val mesh = Stl.read(File(dir, "3DBenchy.stl").readBytes())
                    val obj = PlateObject.of(1, SceneObject("3DBenchy", mesh, Mat4.identity())).apply { x = 128f; y = 128f }
                    val model = ThreeMf.writePlain(listOf(SceneObject(obj.name, obj.mesh, obj.transform())))
                    SliceJob(model, printer, "0.20mm Standard @BBL X1C", listOf("Bambu PLA Basic @BBL X1C"), printerIncludes = library.includes(printer)) to library
                }
                withContext(Dispatchers.IO) {
                    File(dir, "inputs").mkdirs()
                    job.resolvedFiles(library).forEach { (name, bytes) -> File(dir, "inputs/$name").writeBytes(bytes) }
                }
                status.writeText("slicing")
                val run = client.slice(job) {}
                check(run.ok)
                withContext(Dispatchers.IO) {
                    File(dir, "phone.gcode.3mf").writeBytes(run.sliced ?: error("No slice"))
                    status.writeText("ok")
                }
            } catch (e: Exception) { status.writeText("failed: ${e.message ?: e::class.simpleName}") }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
