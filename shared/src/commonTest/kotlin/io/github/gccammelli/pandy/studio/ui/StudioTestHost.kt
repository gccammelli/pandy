// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.ProfileSource
import io.github.gccammelli.pandy.studio.slicer.SliceJob
import io.github.gccammelli.pandy.studio.slicer.SliceRun

/** A small set of Bambu-style profiles: two printers sharing one process and two filaments. */
internal val studioTestProfiles = mapOf(
    "profiles/BBL.json" to """{"machine_list":[{"name":"Bambu Lab X1 Carbon 0.4 nozzle","sub_path":"machine/x1c.json"},{"name":"Bambu Lab A1 mini 0.4 nozzle","sub_path":"machine/a1m.json"}],
        "process_list":[{"name":"0.20mm Standard @BBL X1C","sub_path":"process/std.json"}],
        "filament_list":[{"name":"Bambu PLA Basic @BBL X1C","sub_path":"filament/pla.json"},{"name":"Bambu PETG HF @BBL X1C","sub_path":"filament/petg.json"}]}""",
    "profiles/BBL/machine/x1c.json" to """{"type":"machine","name":"Bambu Lab X1 Carbon 0.4 nozzle","instantiation":"true","printer_model":"Bambu Lab X1 Carbon","nozzle_diameter":["0.4"],
        "default_filament_profile":["Bambu PLA Basic @BBL X1C"],"printable_height":"250"}""",
    "profiles/BBL/machine/a1m.json" to """{"type":"machine","name":"Bambu Lab A1 mini 0.4 nozzle","instantiation":"true","printer_model":"Bambu Lab A1 mini","nozzle_diameter":["0.4"],
        "default_filament_profile":["Bambu PLA Basic @BBL X1C"],"printable_height":"180"}""",
    "profiles/BBL/process/std.json" to """{"type":"process","name":"0.20mm Standard @BBL X1C","instantiation":"true","layer_height":"0.2",
        "compatible_printers":["Bambu Lab X1 Carbon 0.4 nozzle","Bambu Lab A1 mini 0.4 nozzle"]}""",
    "profiles/BBL/filament/pla.json" to """{"type":"filament","name":"Bambu PLA Basic @BBL X1C","instantiation":"true","filament_type":["PLA"],"default_filament_colour":["#00AE42"],
        "compatible_printers":["Bambu Lab X1 Carbon 0.4 nozzle","Bambu Lab A1 mini 0.4 nozzle"]}""",
    "profiles/BBL/filament/petg.json" to """{"type":"filament","name":"Bambu PETG HF @BBL X1C","instantiation":"true","filament_type":["PETG"],
        "compatible_printers":["Bambu Lab X1 Carbon 0.4 nozzle","Bambu Lab A1 mini 0.4 nozzle"]}""",
)

/** Studio's platform without a slicer: profiles from a map, settings in memory. */
internal class StudioTestHost(val files: Map<String, String> = studioTestProfiles, var picked: List<Pair<String, ByteArray>> = emptyList()) : Platform {
    val settings = mutableMapOf<String, String>()
    override suspend fun pickModels() = picked
    override suspend fun engineInfo() = EngineInfo("02.08.02.61", "e", "r")
    override fun loadSetting(key: String) = settings[key]
    override fun saveSetting(key: String, value: String) { settings[key] = value }
    override suspend fun profiles() = ProfileSource { files[it] }
    override suspend fun slice(job: SliceJob, onLog: (String) -> Unit): SliceRun = error("not sliced in this test")
}
