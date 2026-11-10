// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ResolvedJobTest {
    private val files = mapOf(
        "profiles/BBL.json" to """{"machine_list":[{"name":"M","sub_path":"machine/m.json"}],"process_list":[{"name":"P","sub_path":"process/p.json"}],"filament_list":[{"name":"F","sub_path":"filament/f.json"}]}""",
        "profiles/BBL/machine/m.json" to """{"name":"M","type":"machine","printer_model":"X1","machine_start_gcode":"G28\nG29"}""",
        "profiles/BBL/process/p.json" to """{"name":"P","type":"process","outer_wall_speed":["200"],"wall_loops":"2","post_process":["danger"]}""",
        "profiles/BBL/filament/f.json" to """{"name":"F","type":"filament","filament_max_volumetric_speed":["21"],"filament_density":["1.26"],"filament_id":"PLA"}""",
    )
    private val library = PresetLibrary(ProfileSource { files[it] })

    @Test fun resolvedInputsCarryPresetValuesAndOverrides() {
        val job = SliceJob(byteArrayOf(1), "M", "P", listOf("F"), mapOf("wall_loops" to Setting.One("3")))
        val inputs = job.resolvedFiles(library)
        fun doc(name: String) = Json.parseToJsonElement(inputs.getValue(name).decodeToString()).jsonObject
        assertEquals("X1", doc("machine.json")["printer_model"]!!.jsonPrimitive.content)
        assertEquals("3", doc("process.json")["wall_loops"]!!.jsonPrimitive.content)
        assertEquals("21", doc("filament1.json")["filament_max_volumetric_speed"].toString().trim('[', ']', '"'))
        assertEquals("PLA", doc("filament1.json")["filament_id"]!!.jsonPrimitive.content)
        assertFalse("post_process" in doc("process.json"))
        assertFailsWith<IllegalArgumentException> { job.copy(process = "missing").resolvedFiles(library) }
        assertFailsWith<IllegalArgumentException> { job.copy(processOverrides = mapOf("post_process" to Setting.One("unsafe"))).resolvedFiles(library) }
    }
}
