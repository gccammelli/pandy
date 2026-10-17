package io.github.gccammelli.pandy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanTest {
    private val cfg = LanConfig("10.1.2.42", "00M00A000000000", "12345678", "X1C")

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

    @Test fun names() {
        assertEquals("P1S", modelName("C12")); assertEquals("A1 mini", modelName(null, "030ABC")); assertEquals(70, fanPercent("10"))
    }
}

class LanFirmwareTest {
    @Test fun newFirmwareFields() {
        assertEquals("hardened_steel", nozzleTypeName("HS01")); assertEquals("high_flow_hardened_steel", nozzleTypeName("HH01"))
        val cfg = LanConfig("10.1.2.42", "00M00A000000000", "x", "X1C", "Demo X1C")
        val r = json.parseToJsonElement("""{"print":{"gcode_state":"IDLE","device":{"ctc":{"info":{"temp":25}}}}}""").jsonObject
        val e = lanEntities(cfg, r, true).associate { it.first.key to it.second.state }
        assertEquals("25.0", e["chamber_temp"]); assertEquals("Demo X1C", e["printer_name"])
    }
}
