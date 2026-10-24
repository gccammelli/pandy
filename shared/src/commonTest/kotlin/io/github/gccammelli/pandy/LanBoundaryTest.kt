package io.github.gccammelli.pandy

import kotlinx.serialization.json.*
import kotlin.test.*

class LanBoundaryTest {
    private val config = LanConfig("192.0.2.42", "TESTSERIAL", "test-code", "X1C")
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun entities(text: String, model: String = "X1C", job: LanJob? = null) =
        lanEntities(config.copy(model = model), obj(text), true, job).filter { it.first.device == "printer" }.associate { it.first.key to it.second }

    @Test fun emptyAndMalformedOptionalReportsStayExplicitlyUnknown() {
        for (report in listOf("{}", """{"print":null}""", """{"print":{"fun":"bad-hex","hms":[null,{},42,{"attr":"bad"},{"attr":1,"code":"bad"}],"lights_report":[null,{},42]}}""")) {
            val e = entities(report)
            assertEquals("unknown", e.getValue("nozzle_temp").state)
            assertEquals("idle", e.getValue("print_status").state)
            assertEquals("off", e.getValue("chamber_light").state)
        }
        val hms = entities("""{"print":{"hms":[null,{},42,{"attr":"bad"},{"attr":1,"code":"bad"}]}}""").getValue("hms_errors")
        assertEquals("1", hms.attrs.s("Count")); assertEquals("HMS_0000_0001_0000_0000", hms.attrs.s("1-Code"))
    }

    @Test fun firmwareFlagEncodingsAndNestedXcamFieldsHaveConsistentMeaning() {
        val values = listOf("true" to "on", "false" to "off", "1" to "on", "0" to "off", "\"ON\"" to "on", "\"off\"" to "off",
            "\"enable\"" to "on", "\"disable\"" to "off", "\"enabled\"" to "on", "\"disabled\"" to "off", "\"unknown\"" to null, "null" to null, "[]" to null)
        for ((raw, expected) in values) {
            val e = entities("""{"print":{"xcam":{"printing_monitor":$raw,"printing_monitor_print_halt":$raw},"auto_recovery":$raw,"auto_switch_filament":$raw}}""")
            assertEquals(expected, e["printing_monitor"]?.state, raw)
            assertEquals(expected, e["auto_recovery"]?.state, raw)
            assertEquals(expected, e["auto_switch_filament"]?.state, raw)
        }
        val nested = entities("""{"xcam":{"printing_monitor":{"enable":true,"print_halt":false}}}""").getValue("printing_monitor")
        assertEquals("on", nested.state); assertEquals("false", nested.attrs.s("print_halt"))
        val global = entities("""{"print":{"xcam":{"printing_monitor":{"enable":false},"print_halt":true}}}""").getValue("printing_monitor")
        assertEquals("off", global.state); assertEquals("true", global.attrs.s("print_halt"))
    }

    @Test fun explicitPrintOptionsTakePrecedenceAndLegacyBitmapsRemainSupported() {
        val cases = listOf(
            """{"print":{"print_option":{"auto_recovery":false,"auto_switch_filament":false},"auto_recovery":true,"home_flag":1040,"cfg":"50000"}}""" to "off",
            """{"print_option":{"auto_recovery":true,"auto_switch_filament":true}}""" to "on",
            """{"print":{"auto_recovery":false,"auto_switch_filament":false,"cfg":"50000"}}""" to "off",
            """{"print":{"home_flag":0}}""" to "off",
            """{"print":{"cfg":"50000"}}""" to "on",
            """{"print":{"home_flag":"bad","cfg":"0"}}""" to "off",
        )
        for ((raw, expected) in cases) {
            val e = entities(raw)
            assertEquals(expected, e.getValue("auto_recovery").state)
            assertEquals(expected, e.getValue("auto_switch_filament").state)
        }
    }

    @Test fun chamberTemperaturesAreLimitedToSupportedModelsAndBedTypeUsesFallback() {
        val report = """{"print":{"device":{"ctc":{"info":{"temp":25}}},"print_bed_type":"cool_plate","sdcard":"false","ipcam":{"timelapse":"disable"}}}"""
        for (model in listOf("X1C", "H2D", "P2S")) assertEquals("25.0", entities(report, model).getValue("chamber_temp").state)
        for (model in listOf("P1S", "A1")) assertFalse(entities(report, model).containsKey("chamber_temp"))
        val e = entities(report)
        assertEquals("cool_plate", e.getValue("print_bed_type").state)
        assertEquals("missing", e.getValue("sdcard_status").state); assertEquals("off", e.getValue("timelapse").state)
        val top = entities("""{"print":{"chamber_temper":30,"device":{"ctc":{"info":{"temp":25}}},"bed_type":"textured_plate","print_bed_type":"cool_plate","sdcard":"true","ipcam":{"timelapse":"enable"}}}""")
        assertEquals("30.0", top.getValue("chamber_temp").state)
        assertEquals("textured_plate", top.getValue("print_bed_type").state)
        assertEquals("normal", top.getValue("sdcard_status").state); assertEquals("on", top.getValue("timelapse").state)
    }

    @Test fun liveZeroLegacyLayersUseNestedProgressWithoutLeakingStaleIdleLayers() {
        val cases = listOf(
            Triple("RUNNING", 10, 5), Triple("PAUSE", 10, 5), Triple("RUNNING", 0, 0), Triple("FINISH", 10, 0), Triple("RUNNING", -1, 0))
        for ((status, progress, expected) in cases) {
            val e = entities("""{"print":{"gcode_state":"$status","mc_percent":$progress,"layer_num":0,"3D":{"layer_num":5}}}""")
            assertEquals(expected.toString(), e.getValue("current_layer").state)
        }
        assertEquals("0", entities("""{"print":{"gcode_state":"RUNNING","mc_percent":10,"layer_num":0,"3D":{"layer_num":0}}}""").getValue("current_layer").state)
    }

    @Test fun amsHtVariantsKeepOneSlotAndHonorPerUnitSettings() {
        for (hint in listOf("\"model\":\"AMS HT\"", "\"type\":\"HT\"", "\"model\":\"N3S\"", "\"type\":\"N3S\"")) {
            val report = obj("""{"print":{"ams":{"tray_now":"128","ams":[null,{"id":1,$hint,"startup_read_option":true,"tray_read_option":false,"calibrate_remain_flag":false,"humidity_raw":42,"tray":[null,{"id":0,"tray_type":"PETG","tray_sub_brands":"Custom spool"}]}]},"vt_tray":{}}}""")
            val rows = lanEntities(config, report, true).filter { it.first.device == "ams1" }
            val trays = rows.filter { it.first.key == "tray" }
            assertEquals(1, trays.size); assertEquals("AMS HT", trays.single().first.deviceModel)
            assertEquals("Custom spool", trays.single().second.state); assertEquals("true", trays.single().second.attrs.s("active"))
            val settings = rows.associate { it.first.key to it.second.state }
            assertEquals("on", settings["ams_read_on_startup"]); assertEquals("off", settings["ams_read_on_insertion"])
            assertEquals("off", settings["ams_remaining_estimation"]); assertEquals("42", settings["humidity"])
        }
        val standard = lanEntities(config, obj("""{"print":{"ams":{"ams":[{"tray":[{}]}]}}}"""), true)
        assertEquals(4, standard.count { it.first.key == "tray" })
        assertTrue(standard.filter { it.first.key == "tray" }.all { it.second.state == "Empty" })
    }

    @Test fun optionalJobMetadataDistinguishesAPlateImageFromAPickMap() {
        val job = LanJob("job-key", null, null, mapOf(1 to "Part"))
        val withoutPick = entities("{}", job = job)
        assertEquals("job-key", withoutPick.getValue("cover_image").state); assertFalse(withoutPick.containsKey("pick_image"))
        assertEquals("Part", withoutPick.getValue("printable_objects").attrs.o("objects")!!.s("1"))
        val withPick = entities("{}", job = LanJob("job-key", byteArrayOf(1), byteArrayOf(2), emptyMap()))
        assertEquals("job-key", withPick.getValue("pick_image").state)
    }

    @Test fun advertisedCameraAndNozzleVariantsRemainReadableAcrossPrinterFamilies() {
        for((model,rtsp) in listOf("X1C" to true,"H2D" to true,"P2S" to true,"P1S" to false,"A1" to false))
            assertEquals(rtsp,rtspCamera(model))
        for((raw,expected) in listOf("HS00" to "stainless_steel","HH01" to "high_flow_hardened_steel",
            "HH05" to "high_flow_tungsten_carbide","HS99" to "unknown","1234" to "1234","HHxx" to "HHxx","brass" to "brass"))
            assertEquals(expected,nozzleTypeName(raw))
        for((raw,expected) in listOf(null to "idle","" to "idle","running" to "running","PAUSE" to "pause","FINISH" to "finish",
            "FAILED" to "failed","PREPARE" to "prepare","SLICING" to "slicing","FUTURE_STATE" to "future_state"))
            assertEquals(expected,printStatus(raw))
        for((level,label) in listOf(1 to "silent",2 to "standard",3 to "sport",4 to "ludicrous",99 to "standard"))
            assertEquals(label,entities("""{"print":{"spd_lvl":$level,"subtask_name":" "}}""").getValue("speed_profile").state)
    }

    @Test fun globalAmsSettingsTakePrecedenceAndUnknownTraysNeverInventFilament() {
        val report=obj("""{"print":{"ams":{"calibrate_remain_flag":true,"ams":[
            {"id":0,"calibrate_remain_flag":false,"tray":[42,{"id":0,"tray_type":"PLA","tray_sub_brands":""}]},
            {"id":1,"tray":null}]},"vt_tray":{"tray_type":null}}}""")
        val rows=lanEntities(config,report,true)
        assertTrue(rows.filter { it.first.key=="ams_remaining_estimation" }.all { it.second.state=="on" })
        val first=rows.first { it.first.device=="ams0" && it.first.key=="tray" }.second
        assertEquals("PLA",first.state); assertEquals("PLA",first.attrs.s("type"))
        val absent=rows.first { it.first.device=="ams1" && it.first.key=="tray" }.second
        assertEquals("Empty",absent.state); assertEquals("true",absent.attrs.s("empty"))
        assertEquals("",rows.single { it.first.key=="external_spool" }.second.attrs.s("type"))
    }
}
