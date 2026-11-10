// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PluginRequestTest {
    @Test fun preservesJobMetadataAndOmitsModelAndBlockedSettings() {
        val job = SliceJob(byteArrayOf(1, 2), "Printer", "Process", listOf("PLA", "PETG"),
            mapOf("wall_loops" to Setting.One("3"), "post_process" to Setting.One("unsafe")),
            mapOf("filament_colour" to Setting.Many(listOf("red", "blue"))),
            mapOf("machine_start_gcode" to Setting.One("G28\nG29")))
        val actual = Json.parseToJsonElement(job.pluginRequest()).jsonObject
        val expected = Json.parseToJsonElement("""{"version":1,"printer":"Printer","process":"Process","filaments":["PLA","PETG"],"process_overrides":{"wall_loops":"3"},"filament_overrides":{"filament_colour":["red","blue"]},"printer_includes":{"machine_start_gcode":"G28\nG29"}}""").jsonObject
        assertEquals(expected, actual)
        assertFalse("model3mf" in actual)
    }
}
