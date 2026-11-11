// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Commands for a dual-nozzle printer with a second external spool, and the remaining option encodings. */
class DirectPrinterDualNozzleTest {
    private val config = LanConfig("192.0.2.1", "TEST123", "test-code", "H2D")
    private class Link(val report: String) : LanLink {
        val sent = mutableListOf<JsonObject>()
        override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) { onConnected(); onMessage(report); awaitCancellation() }
        override fun publish(payload: String) { sent += json.parseToJsonElement(payload).jsonObject }
        override fun close() = Unit
    }
    private val report = """{"print":{"command":"push_status","gcode_state":"IDLE","device":{"extruder":{"state":0,"info":[{"id":0,"temp":0},{"id":1,"temp":0}]}},
        "vir_slot":[{"id":"254","tray_type":"PLA"},{"id":"255","tray_type":"PETG"}],"ams":{"tray_now":"255","ams":[{"id":0,"tray":[{"id":"0","tray_type":"PLA"}]}]}}}"""
    private suspend fun DirectPrinter.registry() = ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") })).single()!!.jsonArray
    private suspend fun DirectPrinter.command(service: String, vararg fields: Pair<String, String>) = post("/api/services/$service", buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())

    @Test fun perNozzleTemperaturesAndCacheAndOptionsEncodeTheirCommands() = runBlocking<Unit> {
        val link = Link(report); val printer = DirectPrinter(config, true, { link })
        try {
            val entities = printer.registry()
            fun id(key: String) = entities.first { it.jsonObject.s("translation_key") == key }.jsonObject.s("entity_id")!!
            printer.command("number/set_value", "entity_id" to id("target_left_nozzle_temperature"), "value" to "210")
            assertEquals("M104 T1 S210\n", link.sent.last().o("print")?.s("param"))
            printer.command("number/set_value", "entity_id" to id("target_right_nozzle_temperature"), "value" to "190")
            assertEquals("M104 T0 S190\n", link.sent.last().o("print")?.s("param"))

            printer.command("bambu_lab/print_cache_set", "config" to "true"); assertEquals("true", link.sent.last().o("system")?.s("config"))
            printer.command("bambu_lab/print_cache_set", "config" to "false"); assertEquals("false", link.sent.last().o("system")?.s("config"))
            printer.command("bambu_lab/print_cache_set", "enable" to "true"); assertEquals("true", link.sent.last().o("system")?.s("config"))
            printer.command("bambu_lab/print_cache_set", "config" to "maybe"); assertEquals("false", link.sent.last().o("system")?.s("config"))

            printer.command("bambu_lab/print_option", "nozzle_blob_detect" to "true")
            assertEquals("1", link.sent.last().o("print")?.s("nozzle_blob_detect_v2"))
            printer.command("bambu_lab/print_option", "prompt_sound" to "off")
            assertEquals("false", link.sent.last().o("print")?.s("sound_enable"))
            printer.command("bambu_lab/print_option", "auto_recovery" to "on")
            assertEquals("1", link.sent.last().o("print")?.s("option"))

            printer.command("bambu_lab/xcam_control_set", "module_name" to "clump_detector", "enable" to "true", "print_halt" to "true")
            val x = link.sent.last().o("xcam")!!
            assertEquals("clump_detector", x.s("module_name")); assertEquals("true", x.s("print_halt"))

            printer.command("bambu_lab/calibration", "option" to "2", "nozzle_offset" to "true")
            printer.command("bambu_lab/calibration", "nozzleOffset" to "true")
            assertEquals("calibration", link.sent.last().o("print")?.s("command"))
            assertEquals("16", link.sent.last().o("print")?.s("option"))
        } finally { printer.stop() }
    }

    @Test fun theSecondExternalSpoolIsAddressedAs255() = runBlocking<Unit> {
        val link = Link(report); val printer = DirectPrinter(config, true, { link })
        try {
            val entities = printer.registry()
            val ext2 = entities.first { it.jsonObject.s("entity_id")!!.endsWith("externalspool2_external_spool") || it.jsonObject.s("unique_id")?.contains("ExternalSpool2") == true }.jsonObject.s("entity_id")!!
            printer.command("bambu_lab/load_filament", "entity_id" to ext2)
            val p = link.sent.last().o("print")!!
            assertEquals("255", p.s("ams_id")); assertEquals("255", p.s("target"))
            val devices = printer.ws(listOf(buildJsonObject { put("type", "config/device_registry/list") })).single()!!.jsonArray
            assertTrue(devices.any { it.jsonObject.s("name") == "External Spool" })
        } finally { printer.stop() }
    }

    @Test fun printProjectFileWithoutAmsOmitsTheMappingAndDefaultsThePlate() = runBlocking<Unit> {
        val link = Link(report); val printer = DirectPrinter(config, true, { link })
        try {
            printer.registry()
            printer.command("bambu_lab/print_project_file", "filepath" to "cache/Mochi.gcode.3mf", "ams_mapping" to "1,2")
            val p = link.sent.last().o("print")!!
            assertEquals("Metadata/plate_1.gcode", p.s("param")); assertEquals("false", p.s("use_ams")); assertNull(p["ams_mapping"])
            assertEquals("auto", p.s("bed_type")); assertEquals("Mochi", p.s("subtask_name"))
        } finally { printer.stop() }
    }
}
