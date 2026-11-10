// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Python and the app must write the same fully resolved settings, not just the same preset names. */
class ResolvedParityTest {
    @Test fun matchesHarnessResolvedInputs() {
        val files = mapOf(
            "profiles/BBL.json" to """{"machine_list":[{"name":"M","sub_path":"machine/m.json"}],"process_list":[{"name":"P","sub_path":"process/p.json"}],"filament_list":[{"name":"F","sub_path":"filament/f.json"}]}""",
            "profiles/BBL/machine/m.json" to """{"name":"M","type":"machine","printer_model":"X1","machine_start_gcode":"G28\nG29"}""",
            "profiles/BBL/process/p.json" to """{"name":"P","type":"process","layer_height":"0.2","outer_wall_speed":["200"],"wall_loops":"2","sparse_infill_density":"15%","post_process":["danger"]}""",
            "profiles/BBL/filament/f.json" to """{"name":"F","type":"filament","filament_max_volumetric_speed":["21"],"filament_density":["1.26"],"filament_id":"PLA"}""",
        )
        val library = PresetLibrary(ProfileSource { files[it] })
        val job = SliceJob(byteArrayOf(1), "M", "P", listOf("F"), mapOf("wall_loops" to Setting.One("3")))
        val actual = job.resolvedFiles(library)
        val expected = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("resolved-job-parity.json")!!.bufferedReader().readText()).jsonObject
        for ((name, value) in expected) {
            assertEquals(value, Json.parseToJsonElement(actual.getValue(name).decodeToString()), name)
        }
    }
}
