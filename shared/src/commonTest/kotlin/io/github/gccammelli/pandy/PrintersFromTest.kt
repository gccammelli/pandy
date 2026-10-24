package io.github.gccammelli.pandy

import kotlinx.serialization.json.*
import kotlin.test.*

class PrintersFromTest {
    private fun array(text: String) = json.parseToJsonElement(text).jsonArray

    private val devices = array("""[
        {"id":"p1","name":"Bench X1C","name_by_user":"Workshop","model":"X1C","sw_version":"01.09","identifiers":[["bambu_lab","SERIAL1"]]},
        {"id":"p2","identifiers":[["bambu_lab","SERIAL2"]]},
        {"id":"other","name":"Not Bambu","identifiers":[["hue","abc"]]},
        {"id":"nobody","name":"No identifiers"},
        {"id":"a0","via_device_id":"p1","model":"AMS","identifiers":[["bambu_lab","SERIAL1_AMS_0"]]},
        {"id":"a1","via_device_id":"p1","model":"AMS 2 Pro","identifiers":[["bambu_lab","SERIAL1_AMS_1"]]},
        {"id":"ht","via_device_id":"p1","model":"AMS HT","identifiers":[["bambu_lab","SERIAL1_AMS_128"]]},
        {"id":"ht2","via_device_id":"p1","model":"N3S Lite","identifiers":[["bambu_lab","SERIAL1_other"],["bambu_lab","weird"],["bambu_lab",5]]},
        {"id":"ht3","via_device_id":"p1","identifiers":[["bambu_lab","SERIAL1_ams_129"]]},
        {"id":"lite","via_device_id":"p1","model":"AMS Lite","identifiers":[["bambu_lab"],["bambu_lab",["x"]]]},
        {"id":"ext","via_device_id":"p1","model":"External spool","identifiers":[["bambu_lab","SERIAL1_ext"]]},
        {"id":"nospool","via_device_id":"p2","identifiers":[["bambu_lab","SERIAL2_x"]]}]""")

    private fun entity(id: String, device: String, key: String?, unique: String? = null, platform: String = "bambu_lab", disabled: String? = null) =
        """{"entity_id":"$id","device_id":"$device","platform":"$platform"${if (key != null) ""","translation_key":"$key"""" else ""}${if (unique != null) ""","unique_id":"$unique"""" else ""}${if (disabled != null) ""","disabled_by":"$disabled"""" else ""}}"""

    private val entities = array("[" + listOf(
        entity("sensor.nozzle", "p1", "nozzle_temp"),
        entity("sensor.fallback_bed_temp", "p1", null, "SERIAL1_bedtemp"),
        entity("sensor.unkeyed", "p1", null),
        entity("sensor.disabled", "p1", "disabled_one", disabled = "user"),
        entity("sensor.foreign", "p1", "foreign", platform = "hue"),
        entity("sensor.tray1", "a0", "tray", "SERIAL1_AMS_0_tray_1"),
        entity("sensor.tray_odd", "a0", "tray", "no-number"),
        entity("sensor.tray_n", "a0", "tray", "SERIAL1_AMS_0_tray_3"),
        entity("sensor.a1_tray", "a1", "tray", "SERIAL1_AMS_1_tray_1"),
        entity("sensor.ht_tray", "ht", "tray", "SERIAL1_AMS_128_tray_1"),
        entity("sensor.ht2_tray", "ht2", "tray", "SERIAL1_x_tray_1"),
        entity("sensor.ht3_tray", "ht3", "tray", "SERIAL1_y_tray_1"),
        entity("sensor.lite_tray", "lite", "tray"),
        entity("sensor.external", "ext", "external_spool"),
        entity("sensor.p2_name", "p2", "printer_name"),
        entity("sensor.p2_spool", "nospool", "nothing")).joinToString(",") + "]")

    @Test fun registriesBecomePrintersWithAmsUnitsHighTemperatureIdsAndExternalSpools() {
        val printers = printersFrom(devices, entities)
        assertEquals(listOf("SERIAL1", "SERIAL2"), printers.map { it.serial })
        val first = printers.first()
        assertEquals("Workshop", first.name); assertEquals("X1C", first.model); assertEquals("01.09", first.firmware)
        assertEquals("sensor.nozzle", first.ids["sensor.nozzle_temp"]); assertEquals("sensor.fallback_bed_temp", first.ids["sensor.bedtemp"])
        assertFalse(first.ids.values.any { it == "sensor.unkeyed" || it == "sensor.disabled" || it == "sensor.foreign" })
        assertEquals(listOf("a0", "a1", "ht", "ht2", "ht3", "lite"), first.ams.map { it.haId })
        assertEquals(mapOf(1 to "sensor.tray1", 3 to "sensor.tray_n"), first.ams[0].trays) // a tray without a number is ignored
        assertEquals(listOf(0, 1, 128, 129, 129, null), first.ams.map { it.protocolId }) // N3S without an id takes the next free high-temperature id
        assertEquals(listOf("sensor.external"), first.external); assertEquals("ext", first.externalDevice)
        val second = printers.last()
        assertEquals("SERIAL2", second.name); assertEquals("", second.model); assertNull(second.firmware)
        assertTrue(second.ams.isEmpty()); assertTrue(second.external.isEmpty()); assertNull(second.externalDevice)
    }

    @Test fun printersWithoutBambuDevicesOrEntitiesAreSkippedCleanly() {
        assertTrue(printersFrom(array("[]"), array("[]")).isEmpty())
        assertTrue(printersFrom(array("""[{"id":"x","identifiers":[["hue","a"]]}]"""), array("[]")).isEmpty())
        val bare = printersFrom(array("""[{"id":"p","identifiers":[["bambu_lab","S"]]}]"""), array("[]")).single()
        assertEquals("S", bare.name); assertTrue(bare.ids.isEmpty())
    }

    @Test fun switchesAndFlagsReadEveryDocumentedSpellingAndRejectOthers() {
        val ids = mapOf("switch.printing_monitor" to "switch.pm", "binary_sensor.developer_lan_mode" to "binary_sensor.lan",
            "binary_sensor.hybrid_mode_blocks_control" to "binary_sensor.hybrid")
        val info = PrinterInfo("p", "S", "P", "X1C", null, ids, emptyList(), listOf("sensor.spool", "sensor.gone"))
        fun printer(state: String, attrs: String = "{}", lan: String = "on", hybrid: String? = null) = Printer(info, buildMap {
            put("switch.pm", Entity("switch.pm", state, json.parseToJsonElement(attrs).jsonObject))
            put("binary_sensor.lan", Entity("binary_sensor.lan", lan, JsonObject(emptyMap())))
            hybrid?.let { put("binary_sensor.hybrid", Entity("binary_sensor.hybrid", it, JsonObject(emptyMap()))) }
            put("sensor.spool", Entity("sensor.spool", "PLA", JsonObject(emptyMap())))
        })
        for (on in listOf("on", "TRUE", "1", "Enable", "enabled")) assertEquals(true, printer(on).flag("printing_monitor"), on)
        for (off in listOf("off", "False", "0", "disable", "DISABLED")) assertEquals(false, printer(off).flag("printing_monitor"), off)
        for (other in listOf("unknown", "unavailable", "maybe", "")) assertNull(printer(other).flag("printing_monitor"), other)
        assertNull(printer("on").flag("not_a_switch"))
        assertEquals(true, printer("on", """{"print_halt":"enabled"}""").flagAttribute("printing_monitor", "print_halt"))
        assertEquals(false, printer("on", """{"print_halt":"0"}""").flagAttribute("printing_monitor", "print_halt"))
        assertNull(printer("on", """{"print_halt":"?"}""").flagAttribute("printing_monitor", "print_halt"))
        assertNull(printer("on").flagAttribute("printing_monitor", "missing"))
        assertFalse(printer("on").controlBlocked)
        assertTrue(printer("on", lan = "off").controlBlocked)                    // Developer Mode off
        assertTrue(printer("on", hybrid = "on").controlBlocked)                  // hybrid mode blocks control
        assertFalse(printer("on", hybrid = "off").controlBlocked)
        assertEquals(1, printer("on").external.size) // a spool id without a state is skipped
    }
}
