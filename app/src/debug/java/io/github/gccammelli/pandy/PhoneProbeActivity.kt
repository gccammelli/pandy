package io.github.gccammelli.pandy

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Testbed-only smoke test for the real Android activity-result and file-grant boundary; never starts a print. */
class PhoneProbeActivity : MainActivity() {
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.APPLICATION_ID.endsWith(".testbed")) { finish(); return }
        val dir = File(cacheDir, "phone-probe").apply { mkdirs() }
        val status = File(dir, "status.txt")
        status.writeText("running")
        probeScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { File(dir, "model.3mf").readBytes() }
                val settings = mapOf("wall_loops" to "3")
                val available = phoneSettings(phoneSlicer("CAPABILITIES_V1", "{\"version\":1}"))
                check(available.any { it.key == "wall_loops" && it.valid("3") }) { "Studio did not advertise the requested setting" }
                val printer = "Bambu Lab A1 mini 0.4 nozzle"
                val bed = "textured_plate"
                val request = phoneSliceRequest(1, "demo", printer, bed, "PLA", "#FFFFFF", settings)
                val response = phoneSlicer("SLICE_V1", request, model)
                val (bytes, plate) = withContext(Dispatchers.Default) { phoneSliceResult(response, settings, 1, printer, bed) }
                withContext(Dispatchers.IO) { File(dir, "result.3mf").writeBytes(bytes); status.writeText("ok: plate ${plate.index}, ${plate.filaments.size} filament(s)") }
            } catch (e: Exception) {
                status.writeText("failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    override fun onDestroy() { probeScope.cancel(); super.onDestroy() }
}
