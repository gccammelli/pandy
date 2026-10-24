package io.github.gccammelli.pandy

import kotlinx.serialization.json.*
import kotlin.test.*

class LanCoverageTest {
    private val cfg = LanConfig("192.0.2.42", "00M000000000000", "00000000", "X1C")

    private fun rows(print: String, config: LanConfig = cfg, online: Boolean = true, job: LanJob? = null) =
        lanEntities(config, json.parseToJsonElement("""{"print":$print}""").jsonObject, online, job)
    private fun List<Pair<LanRow, Entity>>.value(key: String, device: String = "printer") = firstOrNull { it.first.key == key && it.first.device == device }?.second?.state
    private fun List<Pair<LanRow, Entity>>.entity(key: String, device: String = "printer") = firstOrNull { it.first.key == key && it.first.device == device }?.second

    @Test fun nozzleCodesNameTheirMaterialAndHighFlowAndOtherTextPassesThrough() {
        assertEquals("hardened_steel", nozzleTypeName("HS01"))
        assertEquals("high_flow_hardened_steel", nozzleTypeName("HH01"))
        assertEquals("stainless_steel", nozzleTypeName("HS00"))
        assertEquals("tungsten_carbide", nozzleTypeName("HS05"))
        assertEquals("high_flow_unknown", nozzleTypeName("HH77"))
        for (other in listOf("hardened_steel", "1S01", "HSxx", "HS1")) assertEquals(other, nozzleTypeName(other))
    }

    @Test fun layerNumbersPreferTheTopLevelFieldExceptWhenTheLegacyOneIsStuckAtZeroOnALivePrint() {
        val live = rows("""{"gcode_state":"RUNNING","mc_percent":10,"layer_num":0,"total_layer_num":0,"3D":{"layer_num":7,"total_layer_num":100}}""")
        assertEquals("7", live.value("current_layer")); assertEquals("100", live.value("total_layers"))
        val top = rows("""{"gcode_state":"RUNNING","mc_percent":10,"layer_num":5,"total_layer_num":50,"3D":{"layer_num":7}}""")
        assertEquals("5", top.value("current_layer"))
        val paused = rows("""{"gcode_state":"PAUSE","mc_percent":10,"3D":{"layer_num":9}}""")
        assertEquals("9", paused.value("current_layer"))
        val finished = rows("""{"gcode_state":"FINISH","mc_percent":100,"3D":{"layer_num":9}}""")
        assertEquals("0", finished.value("current_layer")) // a stale nested value is not shown after a print ends
        val starting = rows("""{"gcode_state":"RUNNING","mc_percent":0,"layer_num":0,"3D":{"layer_num":9}}""")
        assertEquals("0", starting.value("current_layer"))
        val flat = rows("""{"gcode_state":"RUNNING","mc_percent":4,"layer_num":0,"3D":{"layer_num":0}}""")
        assertEquals("0", flat.value("current_layer"))
    }

    @Test fun printOptionsComeFromEveryFirmwareGenerationAndAreOmittedWhenUnreported() {
        val nested = rows("""{"print_option":{"auto_recovery":"enable","auto_switch_filament":false}}""")
        assertEquals("on", nested.value("auto_recovery")); assertEquals("off", nested.value("auto_switch_filament"))
        val legacy = rows("""{"auto_recovery":"on","auto_switch_filament":"off"}""")
        assertEquals("on", legacy.value("auto_recovery")); assertEquals("off", legacy.value("auto_switch_filament"))
        val flags = rows("""{"home_flag":"${(1 shl 4)}"}""")
        assertEquals("on", flags.value("auto_recovery")); assertEquals("off", flags.value("auto_switch_filament"))
        val cfgBits = rows("""{"cfg":"${(1 shl 18).toString(16)}"}""")
        assertEquals("off", cfgBits.value("auto_recovery")); assertEquals("on", cfgBits.value("auto_switch_filament"))
        val none = rows("""{}""")
        assertNull(none.value("auto_recovery")); assertNull(none.value("auto_switch_filament"))
    }

    @Test fun xcamModulesReportTheirStateAndWhereverTheFirmwareKeepsTheirPrintHaltSetting() {
        val e = rows("""{"xcam":{"printing_monitor":true,"printing_monitor_print_halt":true,
            "buildplate_marker_detector":"enable","first_layer_inspector":{"enable":"enabled","print_halt":false},
            "spaghetti_detector":false,"print_halt":true,"pileup_detector":"off","air_print_detector":"maybe"}}""")
        assertEquals("on", e.value("printing_monitor")); assertEquals("true", e.entity("printing_monitor")!!.attrs["print_halt"]!!.jsonPrimitive.content)
        assertEquals("on", e.value("buildplate_marker_detector")); assertEquals("true", e.entity("buildplate_marker_detector")!!.attrs["print_halt"]!!.jsonPrimitive.content)
        assertEquals("on", e.value("first_layer_inspector")); assertEquals("false", e.entity("first_layer_inspector")!!.attrs["print_halt"]!!.jsonPrimitive.content)
        assertEquals("off", e.value("spaghetti_detector")); assertEquals("off", e.value("pileup_detector"))
        assertNull(e.value("air_print_detector")); assertNull(e.value("clump_detector"))
        val topLevel = lanEntities(cfg, json.parseToJsonElement("""{"xcam":{"clump_detector":true},"print":{}}""").jsonObject, true)
        assertEquals("on", topLevel.value("clump_detector")); assertNull(topLevel.entity("clump_detector")!!.attrs["print_halt"])
    }

    @Test fun amsUnitsAreRecognisedAsHighTemperatureByIdModelOrTypeAndNamedFromTheirTrays() {
        val e = rows("""{"ams":{"tray_now":"130","power_on_flag":true,"insert_flag":"off","calibrate_remain_flag":"1","ams":[
            {"id":"0","humidity":"3","humidity_raw":"41","temp":"27.5","tray":[
                {"id":"0","tray_type":"PLA","tray_sub_brands":"PLA Basic","tray_color":"FF0000FF","remain":50,"nozzle_temp_min":"190","nozzle_temp_max":"230"},
                {"id":"1","tray_type":"PETG","tray_sub_brands":"Generic","tray_color":"00FF00FF"},
                {"id":"2","tray_type":"ABS","tray_sub_brands":"   "},{"id":"3","tray_type":"","tray_sub_brands":"ignored"}]},
            {"id":"130","model":"AMS HT","tray":[{"id":"0","tray_type":"TPU","tray_color":"0000FFFF"}]},
            {"id":"2","type":"AMS-HT","tray":[{"id":"0","tray_type":"PA"}]},
            {"id":"3","model":"N3S-B"},{"id":"4","type":"N3S"},{"id":"1"}]}}""")
        fun names(device: String) = e.filter { it.first.key == "tray" && it.first.device == device }.map { it.second.attrs["name"]!!.jsonPrimitive.content to it.second.state }
        assertEquals(listOf("Bambu PLA Basic" to "Bambu PLA Basic", "Generic" to "Generic", "ABS" to "ABS", "Bambu ignored" to "Empty"), names("ams0"))
        assertEquals("3", e.value("humidity_index", "ams0"))
        assertEquals("41", e.value("humidity", "ams0")); assertEquals("27.5", e.value("ams_temp", "ams0"))
        for (device in listOf("ams0", "ams1")) {
            assertEquals("on", e.value("ams_read_on_startup", device)); assertEquals("off", e.value("ams_read_on_insertion", device)); assertEquals("on", e.value("ams_remaining_estimation", device))
        }
        assertEquals(setOf("ams130", "ams2", "ams3", "ams4"), e.filter { it.first.deviceModel == "AMS HT" }.map { it.first.device }.toSet())
        assertEquals("true", e.entity("tray", "ams130")!!.attrs["active"]!!.jsonPrimitive.content)
        val perUnit = rows("""{"ams":{"ams":[{"id":"0","startup_read_option":"on","tray_read_option":false,"calibrate_remain_flag":"disabled"}]}}""")
        assertEquals("on", perUnit.value("ams_read_on_startup", "ams0")); assertEquals("off", perUnit.value("ams_read_on_insertion", "ams0")); assertEquals("off", perUnit.value("ams_remaining_estimation", "ams0"))
        val unflagged = rows("""{"ams":{"ams":[{"id":"0"}]}}""")
        assertNull(unflagged.value("ams_read_on_startup", "ams0")); assertNull(unflagged.value("humidity_index", "ams0"))
        val twoHt = rows("""{"ams":{"ams":[{"model":"HT"},{"model":"HT"}]}}""") // no ids: they get consecutive high-temperature ids
        assertEquals(setOf("ams0"), twoHt.filter { it.first.key == "tray" }.map { it.first.device }.toSet())
    }

    @Test fun externalSpoolsAndBlankReportsStillProduceAUsablePrinter() {
        val e = rows("""{"vt_tray":{"tray_type":"PLA","tray_sub_brands":"Generic PLA","tray_color":"F98C36FF"},"ams":{"tray_now":"254"}}""")
        assertEquals("Generic PLA", e.value("external_spool", "ext")); assertEquals("true", e.entity("external_spool", "ext")!!.attrs["active"]!!.jsonPrimitive.content)
        val empty = rows("""{"vt_tray":{"tray_sub_brands":""}}""")
        assertEquals("Empty", empty.value("external_spool", "ext"))
        val named = rows("""{"vt_tray":{"tray_type":"PETG","tray_sub_brands":"  "}}""")
        assertEquals("PETG", named.value("external_spool", "ext"))
        assertNull(rows("""{}""").value("external_spool", "ext"))
        val blank = lanEntities(cfg, JsonObject(emptyMap()), false)
        assertEquals("off", blank.value("online")); assertEquals("offline", blank.value("print_status"))
    }

    @Test fun jobsAddPictureAndObjectsAndNamelessPrintersFallBackToTheirModel() {
        val withPick = rows("""{}""", job = LanJob("k", byteArrayOf(1), byteArrayOf(2), mapOf(1 to "Cube")))
        assertEquals("k", withPick.value("cover_image")); assertEquals("k", withPick.value("pick_image")); assertEquals("1", withPick.value("printable_objects"))
        val coverOnly = rows("""{}""", job = LanJob("k", byteArrayOf(1), null, emptyMap()))
        assertNull(coverOnly.value("pick_image")); assertEquals("X1C", coverOnly.value("printer_name"))
        assertEquals("Shop", rows("""{}""", config = cfg.copy(name = "Shop")).value("printer_name"))
        val cold = rows("""{"chamber_temper":30}""", config = cfg.copy(model = "P1S"))
        assertNull(cold.value("chamber_temp"))
        val ctc = rows("""{"device":{"ctc":{"info":{"temp":41}}}}""", config = cfg.copy(model = "P2S"))
        assertEquals("41.0", ctc.value("chamber_temp"))
        val errors = rows("""{"print_error":131073,"hms":[{"attr":"x"},"bad",{"attr":50331904,"code":65543},{"attr":1}],"sdcard":"false","wifi_signal":"-60dBm","fun":"20000000"}""")
        assertEquals("on", errors.value("print_error")); assertEquals("missing", errors.value("sdcard_status")); assertEquals("off", errors.value("developer_lan_mode"))
        assertEquals("on", errors.value("hms_errors")); assertEquals("-60", errors.value("wifi_signal"))
    }
}
