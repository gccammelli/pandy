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

    @Test fun dualExternalSpoolsAreDiscoveredInOrder() {
        val dualDevices = array("""[
            {"id":"p1","name":"Bambu X2D","model":"X2D","identifiers":[["bambu_lab","SERIAL_X2D"]]},
            {"id":"ext2","via_device_id":"p1","name":"X2D_SERIAL_X2D_ExternalSpool2","model":"External Spool","identifiers":[["bambu_lab","SERIAL_X2D_ExternalSpool2"]]},
            {"id":"ext1","via_device_id":"p1","name":"X2D_SERIAL_X2D_ExternalSpool","model":"External Spool","identifiers":[["bambu_lab","SERIAL_X2D_ExternalSpool"]]}
        ]""")
        val dualEntities = array("""[
            {"entity_id":"sensor.spool_2","device_id":"ext2","platform":"bambu_lab","translation_key":"external_spool","unique_id":"X2D_SERIAL_X2D_ExternalSpool2_external_spool"},
            {"entity_id":"sensor.spool_1","device_id":"ext1","platform":"bambu_lab","translation_key":"external_spool","unique_id":"X2D_SERIAL_X2D_ExternalSpool_external_spool"}
        ]""")
        val printer = printersFrom(dualDevices, dualEntities).single()
        assertEquals(listOf("sensor.spool_1", "sensor.spool_2"), printer.external)
        assertEquals("ext1", printer.externalDevice)
        assertEquals(listOf("ext1", "ext2"), printer.externalDevices)
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

    @Test fun dualNozzleEntitiesAreMappedCorrectly() {
        val dev = array("""[{"id":"p_dual","name":"Bambu X2D","model":"X2D","identifiers":[["bambu_lab","SERIAL_DUAL"]]}]""")
        val ents = array("""[
            {"entity_id":"sensor.left_t","device_id":"p_dual","platform":"bambu_lab","unique_id":"SERIAL_DUAL_left_nozzle_temp"},
            {"entity_id":"sensor.right_t","device_id":"p_dual","platform":"bambu_lab","unique_id":"SERIAL_DUAL_right_nozzle_temp"},
            {"entity_id":"sensor.left_target","device_id":"p_dual","platform":"bambu_lab","unique_id":"SERIAL_DUAL_left_target_nozzle_temp"},
            {"entity_id":"sensor.right_target","device_id":"p_dual","platform":"bambu_lab","unique_id":"SERIAL_DUAL_right_target_nozzle_temp"},
            {"entity_id":"sensor.act","device_id":"p_dual","platform":"bambu_lab","unique_id":"SERIAL_DUAL_active_nozzle"}
        ]""")
        val printer = printersFrom(dev, ents).single()
        assertEquals("sensor.left_t", printer.ids["sensor.left_nozzle_temp"])
        assertEquals("sensor.right_t", printer.ids["sensor.right_nozzle_temp"])
        assertEquals("sensor.left_target", printer.ids["sensor.left_target_nozzle_temp"])
        assertEquals("sensor.right_target", printer.ids["sensor.right_target_nozzle_temp"])
        assertEquals("sensor.act", printer.ids["sensor.active_nozzle"])

        val states = mapOf(
            "sensor.left_t" to Entity("sensor.left_t", "45.0", JsonObject(emptyMap())),
            "sensor.right_t" to Entity("sensor.right_t", "220.0", JsonObject(emptyMap())),
            "sensor.left_target" to Entity("sensor.left_target", "0", JsonObject(emptyMap())),
            "sensor.right_target" to Entity("sensor.right_target", "220", JsonObject(emptyMap())),
            "sensor.act" to Entity("sensor.act", "right", JsonObject(emptyMap()))
        )
        val p = Printer(printer, states)
        assertTrue(p.isDualNozzle)
        assertEquals(45.0, p.leftNozzle)
        assertEquals(0.0, p.leftNozzleTarget)
        assertEquals(220.0, p.rightNozzle)
        assertEquals(220.0, p.rightNozzleTarget)
        assertEquals("right", p.activeNozzle)
    }

    @Test fun remainingTimeFollowsTheUnitHomeAssistantReportsItIn() {
        // Home Assistant shows the remaining time in hours when the user picks it: 3.97 h is 238 minutes, not 3.
        fun unit(u: String?) = JsonObject(if (u == null) emptyMap() else mapOf("unit_of_measurement" to kotlinx.serialization.json.JsonPrimitive(u)))
        assertEquals(238.2, Entity("sensor.r", "3.97", unit("h")).minutes!!, 0.001)
        assertEquals(238.0, Entity("sensor.r", "238", unit("min")).minutes!!, 0.001)
        assertEquals(238.0, Entity("sensor.r", "238", unit(null)).minutes!!, 0.001)
        assertEquals(4.0, Entity("sensor.r", "240", unit("s")).minutes!!, 0.001)
        assertEquals(1440.0, Entity("sensor.r", "1", unit("d")).minutes!!, 0.001)
        assertNull(Entity("sensor.r", "unavailable", unit("h")).minutes)
    }

    @Test fun caseInsensitiveSerialPrefixInUniqueId() {
        val devs = array("""[{"id":"p_sn","identifiers":[["bambu_lab","00M09A123456789"]]},{"id":"p_nosn","identifiers":[]}]""")
        val ents = array("[" + listOf(
            entity("sensor.weight", "p_sn", null, "00m09a123456789_print_weight"),
            entity("sensor.length", "p_sn", null, "00m09a123456789_print_length"),
            entity("image.cover", "p_sn", null, "00m09a123456789_cover_image"),
            entity("sensor.other", "p_sn", null, "other_metric"),
            entity("sensor.nouid", "p_sn", null, null),
            entity("sensor.nosn", "p_nosn", null, "abc_temp")
        ).joinToString(",") + "]")
        val printer = printersFrom(devs, ents).first { it.haId == "p_sn" }
        assertEquals("sensor.weight", printer.ids["sensor.print_weight"])
        assertEquals("sensor.length", printer.ids["sensor.print_length"])
        assertEquals("image.cover", printer.ids["image.cover_image"])
        assertEquals("sensor.other", printer.ids["sensor.metric"])
        assertNull(printer.ids["sensor.nouid"])
    }

    @Test fun pickImageDoesNotFallBackToGenericCoverImage() {
        val infoGeneric = PrinterInfo("p", "SN", "X1C", "X1C", null, mapOf("image.image" to "img.cover"), emptyList(), emptyList())
        val ok = Entity("img.cover", "2026-11-17T00:00:00", JsonObject(emptyMap()))
        val okPic = Entity("img.cover", "2026-11-17T00:00:00", JsonObject(mapOf("entity_picture" to kotlinx.serialization.json.JsonPrimitive("/api/image_proxy/img.cover?token=abc"))))
        val unavail = Entity("img.cover", "unavailable", JsonObject(emptyMap()))
        val blank = Entity("img.cover", "", JsonObject(emptyMap()))
        assertEquals("/api/image_proxy/img.cover?v=2026-11-17T00:00:00", Printer(infoGeneric, mapOf("img.cover" to ok)).coverImage)
        assertEquals("/api/image_proxy/img.cover?token=abc", Printer(infoGeneric, mapOf("img.cover" to okPic)).coverImage)
        assertNull(Printer(infoGeneric, mapOf("img.cover" to unavail)).coverImage)
        assertNull(Printer(infoGeneric, mapOf("img.cover" to blank)).coverImage)
        assertNull(Printer(infoGeneric, emptyMap()).coverImage)
        assertNull(Printer(infoGeneric, mapOf("img.cover" to ok)).pickImage)

        val infoBoth = PrinterInfo("p", "SN", "X1C", "X1C", null, mapOf("image.cover_image" to "img.c", "image.pick_image" to "img.p"), emptyList(), emptyList())
        val cOk = Entity("img.c", "v1", JsonObject(emptyMap()))
        val pOk = Entity("img.p", "v2", JsonObject(emptyMap()))
        val pPic = Entity("img.p", "v2", JsonObject(mapOf("entity_picture" to kotlinx.serialization.json.JsonPrimitive("/api/image_proxy/img.p?token=xyz"))))
        val pUnavail = Entity("img.p", "unavailable", JsonObject(emptyMap()))
        val pBlank = Entity("img.p", " ", JsonObject(emptyMap()))
        assertEquals("/api/image_proxy/img.c?v=v1", Printer(infoBoth, mapOf("img.c" to cOk)).coverImage)
        assertEquals("/api/image_proxy/img.p?v=v2", Printer(infoBoth, mapOf("img.p" to pOk)).pickImage)
        assertEquals("/api/image_proxy/img.p?token=xyz", Printer(infoBoth, mapOf("img.p" to pPic)).pickImage)
        assertNull(Printer(infoBoth, mapOf("img.p" to pUnavail)).pickImage)
        assertNull(Printer(infoBoth, mapOf("img.p" to pBlank)).pickImage)
        assertNull(Printer(infoBoth, emptyMap()).pickImage)
    }
}
