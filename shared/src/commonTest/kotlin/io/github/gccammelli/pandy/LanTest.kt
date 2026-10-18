package io.github.gccammelli.pandy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

class LanTest {
    private val cfg = LanConfig("192.0.2.42", "00M000000000000", "00000000", "X1C")

    /** A trimmed push_status from an X1C mid-print, in the printer's own format. */
    private val report = json.parseToJsonElement("""
        {"print":{"command":"push_status","gcode_state":"RUNNING","mc_percent":46,"mc_remaining_time":83,"layer_num":145,"total_layer_num":312,
         "subtask_name":"Panda","nozzle_temper":219.6,"nozzle_target_temper":220,"bed_temper":55.1,"bed_target_temper":55,"chamber_temper":34,
         "spd_lvl":3,"stg_cur":0,"cooling_fan_speed":"15","big_fan1_speed":"10","big_fan2_speed":"0","heatbreak_fan_speed":"15",
         "lights_report":[{"node":"chamber_light","mode":"on"}],"wifi_signal":"-52dBm","nozzle_diameter":"0.4","nozzle_type":"hardened_steel",
         "hms":[{"attr":50331904,"code":65543}],"s_obj":[101],
         "ams":{"tray_now":"1","ams":[{"id":"0","humidity":"4","temp":"27.0","tray":[
            {"id":"0","tray_type":"PLA","tray_sub_brands":"PLA Basic","tray_color":"FFFFFFFF","remain":82,"nozzle_temp_min":"190","nozzle_temp_max":"230"},
            {"id":"1","tray_type":"PETG","tray_sub_brands":"","tray_color":"00AE42FF","remain":25},
            {"id":"2"},{"id":"3"}]}]},
         "vt_tray":{"tray_type":"PLA","tray_color":"F98C36FF"}}}
    """).jsonObject

    private fun printer(r: JsonObject = report, online: Boolean = true): Printer {
        val ents = lanEntities(cfg, r, online)
        val devs = JsonArray(ents.map { it.first.device }.distinct().map { d ->
            json.parseToJsonElement("""{"id":"$d","name":"X1C","model":"${if (d == "printer") "X1C" else "AMS"}","via_device_id":${if (d == "printer") "null" else "\"printer\""},"identifiers":[["bambu_lab","${cfg.serial}_$d"]]}""")
        })
        val reg = JsonArray(ents.map { (row, _) ->
            json.parseToJsonElement("""{"entity_id":"${row.id}","device_id":"${row.device}","platform":"bambu_lab","translation_key":"${row.key}","unique_id":"${cfg.serial}_${row.uid}"}""")
        })
        val info = printersFrom(devs, reg).single()
        return Printer(info, ents.associate { it.second.id to it.second })
    }

    @Test fun printing() {
        val p = printer()
        assertTrue(p.online); assertTrue(p.printing)
        assertEquals(46, p.progress); assertEquals(83.0, p.remaining); assertEquals(145, p.layer); assertEquals(312, p.layers)
        assertEquals("Panda", p.task); assertEquals("printing", p.stage); assertEquals("sport", p.speed)
        assertEquals(219.6, p.nozzle); assertEquals(220.0, p.nozzleTarget); assertEquals(34.0, p.chamber)
        assertTrue(p.lightOn)
        assertEquals(listOf(100, 70, 0), p.fans.map { it.third })
        assertEquals(listOf(101), p.skipped)
        assertEquals("HMS_0300_0100_0001_0007", p.hms.single().first)
    }

    @Test fun ams() {
        val p = printer()
        val (a, trays) = p.ams.single()
        assertEquals(2.0, p.amsNum(a, "sensor.humidity_index")) // printer's 4 (fairly dry) → Handy's 2
        assertEquals("Bambu PLA Basic", trays[0]!!.name); assertEquals(82, trays[0]!!.remain)
        assertTrue(trays[1]!!.active); assertEquals("PETG", trays[1]!!.name)
        assertTrue(!trays[2]!!.known)
        assertEquals(0xFFF98C36, p.external.single().color)
    }

    @Test fun offlineAndIdle() {
        assertEquals("offline", printer(online = false).status)
        val idle = deepMerge(report, buildJsonObject { put("print", buildJsonObject { put("gcode_state", "IDLE") }) })
        assertEquals("idle", printer(idle).status)
        assertNull(printer(idle).remaining?.takeIf { it < 0 })
    }

    @Test fun partialReportsMerge() {
        val m = deepMerge(report, json.parseToJsonElement("""{"print":{"mc_percent":47}}""").jsonObject)
        assertEquals(47, printer(m).progress)
        assertEquals(312, printer(m).layers) // untouched fields stay
    }

    @Test fun nestedLayerFields() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"RUNNING","mc_percent":67,
              "3D":{"layer_num":15,"total_layer_num":23}}}
            """.trimIndent()
        ).jsonObject
        val states = lanEntities(cfg, r, online = true)
            .associate { it.first.key to it.second.state }
        assertEquals("15", states["current_layer"])
        assertEquals("23", states["total_layers"])
    }

    @Test fun settingsReportsUseSharedXcamAndAmsFields() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"IDLE","xcam":{"printing_monitor":true,"print_halt":true},
              "print_option":{"auto_recovery":false,"auto_switch_filament":true},
              "ams":{"insert_flag":true,"power_on_flag":false,"ams":[{"id":"0","calibrate_remain_flag":true,"tray":[{"id":"0"}]}]}}}
            """.trimIndent()
        ).jsonObject
        val rows = lanEntities(cfg, r, online = true)
        val printer = rows.filter { it.first.device == "printer" }.associate { it.first.key to it.second }
        assertEquals("on", printer["printing_monitor"]?.state)
        assertEquals("true", printer["printing_monitor"]?.attrs?.text("print_halt"))
        assertEquals("off", printer["auto_recovery"]?.state)
        assertEquals("on", printer["auto_switch_filament"]?.state)
        val ams = rows.filter { it.first.device == "ams0" }.associate { it.first.key to it.second }
        assertEquals("on", ams["ams_read_on_insertion"]?.state)
        assertEquals("off", ams["ams_read_on_startup"]?.state)
        assertEquals("on", ams["ams_remaining_estimation"]?.state)
    }

    @Test fun settingsReportsDecodeLegacyAndNewOptionBits() {
        val r = json.parseToJsonElement("""{"print":{"home_flag":1040,"cfg":"50000","bed_type":"cool_plate"}}""").jsonObject
        val printer = lanEntities(cfg, r, online = true).filter { it.first.device == "printer" }.associate { it.first.key to it.second.state }
        assertEquals("on", printer["auto_recovery"])
        assertEquals("on", printer["auto_switch_filament"])
        assertEquals("cool_plate", printer["print_bed_type"])
    }

    @Test fun directSettingsCommandsHaveVerifiedFields() {
        val calibration = calibrationCommand("1", 5)["print"]!!.jsonObject
        assertEquals("calibration", calibration.text("command")); assertEquals("5", calibration.text("option"))
        val xcam = xcamCommand("2", "printing_monitor", false, true)["xcam"]!!.jsonObject
        assertEquals("false", xcam.text("control")); assertEquals("false", xcam.text("enable")); assertEquals("true", xcam.text("print_halt"))
        val recovery = printOptionCommand("3", "auto_recovery", true)["print"]!!.jsonObject
        assertEquals("true", recovery.text("auto_recovery")); assertEquals("1", recovery.text("option"))
        val ams = amsUserSettingCommand("4", 0, true, false, true)["print"]!!.jsonObject
        assertEquals("0", ams.text("ams_id")); assertEquals("true", ams.text("startup_read_option")); assertEquals("false", ams.text("tray_read_option"))
    }

    @Test fun currentLayerPrefersLiveTopLevelValue() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"RUNNING","layer_num":7,"total_layer_num":23,
              "3D":{"layer_num":15,"total_layer_num":23}}}
            """.trimIndent()
        ).jsonObject
        val states = lanEntities(cfg, r, online = true).associate { it.first.key to it.second.state }
        assertEquals("7", states["current_layer"])
        assertEquals("23", states["total_layers"])
    }

    @Test fun staleNestedLayerIsIgnoredWhenIdle() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"FINISH","layer_num":0,"total_layer_num":0,
              "3D":{"layer_num":15,"total_layer_num":23}}}
            """.trimIndent()
        ).jsonObject
        val states = lanEntities(cfg, r, online = true).associate { it.first.key to it.second.state }
        assertEquals("0", states["current_layer"])
        assertEquals("0", states["total_layers"])
    }

    @Test fun staleNestedLayerWithoutLegacyFieldsIsIgnoredWhenIdle() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"IDLE",
              "3D":{"layer_num":15,"total_layer_num":23}}}
            """.trimIndent()
        ).jsonObject
        val states = lanEntities(cfg, r, online = true).associate { it.first.key to it.second.state }
        assertEquals("0", states["current_layer"])
        assertEquals("0", states["total_layers"])
    }

    @Test fun staleNestedLayerIsIgnoredAtStartOfNewPrint() {
        val r = json.parseToJsonElement(
            """
            {"print":{"gcode_state":"RUNNING","mc_percent":0,"layer_num":0,"total_layer_num":0,
              "3D":{"layer_num":15,"total_layer_num":23}}}
            """.trimIndent()
        ).jsonObject
        val states = lanEntities(cfg, r, online = true).associate { it.first.key to it.second.state }
        assertEquals("0", states["current_layer"])
        assertEquals("0", states["total_layers"])
    }

    @Test fun names() {
        assertEquals("P1S", modelName("C12")); assertEquals("A1 mini", modelName(null, "030ABC")); assertEquals(70, fanPercent("10"))
    }
}

class LanFirmwareTest {
    @Test fun newFirmwareFields() {
        assertEquals("hardened_steel", nozzleTypeName("HS01")); assertEquals("high_flow_hardened_steel", nozzleTypeName("HH01"))
        val cfg = LanConfig("192.0.2.42", "00M000000000000", "00000000", "X1C", "Demo X1C")
        val r = json.parseToJsonElement("""{"print":{"gcode_state":"IDLE","device":{"ctc":{"info":{"temp":25}}}}}""").jsonObject
        val e = lanEntities(cfg, r, true).associate { it.first.key to it.second.state }
        assertEquals("25.0", e["chamber_temp"]); assertEquals("Demo X1C", e["printer_name"])
    }
}

class LanAmsHtTest {
    @Test fun directAmsHtUsesAbsoluteTrayId() {
        val cfg = LanConfig("192.0.2.10", "00M000000000000", "00000000", "X1C")
        val report = json.parseToJsonElement(
            """{"print":{"ams":{"tray_now":"128","ams":[{"id":"128","tray":[{"id":"0","tray_type":"PLA","tray_color":"FFFFFFFF"}]}]}}}"""
        ).jsonObject
        val entities = lanEntities(cfg, report, online = true)
        val devs = JsonArray(entities.map { it.first.device }.distinct().map { device ->
            val model = if (device == "printer") "X1C" else entities.first { it.first.device == device }.first.deviceModel.ifBlank { "AMS" }
            json.parseToJsonElement("""{"id":"$device","name":"$model","model":"$model","via_device_id":${if (device == "printer") "null" else "\"printer\""},"identifiers":[["bambu_lab","${cfg.serial}_$device"]]}""")
        })
        val registry = JsonArray(entities.map { (row, _) ->
            json.parseToJsonElement("""{"entity_id":"${row.id}","device_id":"${row.device}","platform":"bambu_lab","translation_key":"${row.key}","unique_id":"${cfg.serial}_${row.uid}"}""")
        })
        val info = printersFrom(devs, registry).single()
        val p = Printer(info, entities.associate { it.second.id to it.second })
        assertEquals("AMS HT", p.info.ams.single().model)
        assertEquals(128, p.info.ams.single().amsId())
        assertEquals(setOf(128), p.loadedTrayIds())
    }

    @Test fun directShortStandardAmsKeepsFourSlots() {
        val cfg = LanConfig("192.0.2.10", "00M000000000000", "00000000", "X1C")
        val report = json.parseToJsonElement(
            """{"print":{"ams":{"ams":[{"id":"0","tray":[{"id":"0","tray_type":"PLA"}]}]}}}"""
        ).jsonObject
        val rows = lanEntities(cfg, report, online = true).filter { it.first.device == "ams0" && it.first.key == "tray" }
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.first.deviceModel == "AMS" })
    }
}

class StorageTest {
    @Test fun listing() {
        val l = parseList(listOf(
            "-rw-r--r--    1 1002     1002      2192122 Aug 30  2025 (Unsaved).gcode.3mf",
            "-rw-r--r--    1 1002     1002      3251394 Aug 09 07:58 6-Shot Disc.gcode.3mf",
            "drwxr-xr-x    2 1002     1002         8192 Dec 26 15:31 thumbnail",
        ), 2026, 10)
        assertEquals(FileEntry("(Unsaved).gcode.3mf", 2192122, "2025-08-30T00:00", false), l[0])
        assertEquals("6-Shot Disc.gcode.3mf", l[1].name); assertEquals("2026-08-09T07:58", l[1].modified)
        assertEquals("2025-12-26T15:31", l[2].modified); assertTrue(l[2].dir) // December with a time, seen in October: last year
    }

    @Test fun sliceInfo() {
        val xml = """<config><plate><metadata key="index" value="1"/><object identify_id="270" name="clip.stl" skipped="false"/><object identify_id="281" name="clip.stl" skipped="false"/></plate></config>"""
        assertEquals(mapOf(270 to "clip.stl", 281 to "clip.stl"), sliceObjects(xml, 1))
    }
}

class DeveloperModeTest {
    private val cfg = LanConfig("10.1.2.42", "00M00A000000000", "x", "X1C")
    private fun devMode(fun_: String) =
        lanEntities(cfg, json.parseToJsonElement("""{"print":{"gcode_state":"IDLE","fun":"$fun_"}}""").jsonObject, true)
            .first { it.first.key == "developer_lan_mode" }.second.state

    @Test fun signedCommandsMeanWatchingOnly() {
        assertEquals("off", devMode("20011A30F9CFF")) // Developer Mode off: commands must be signed by Bambu's apps
        assertEquals("on", devMode("3EC1AFFF9CFF".toLong(16).and(0x20000000L.inv()).toString(16)))
    }
}
