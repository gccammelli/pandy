package io.github.gccammelli.pandy

import kotlinx.serialization.json.*
import kotlin.test.*

class LanBoundaryTest {
    private val config = LanConfig("192.0.2.42", "TESTSERIAL", "test-code", "X1C")
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun entities(text: String, model: String = "X1C", job: LanJob? = null) =
        lanEntities(config.copy(model = model), obj(text), true, job).filter { it.first.device == "printer" }.associate { it.first.key to it.second }

    @Test fun amsModuleNamesAndModelHintsIdentifyEachVariant() {
        fun model(fields: String = "", module: String = ""): String {
            val report = obj("""{"print":{"ams":{"ams":[{"id":0,"tray":[]$fields}]}},"info":{"module":[$module]}}""")
            return lanEntities(config, report, true).first { it.first.device == "ams0" }.first.deviceModel
        }
        for (name in listOf("ams/0", "ams0", "ams_f1/0", "ams_f1_0", "n3f/0", "n3s/0", "ams_1")) {
            assertEquals("AMS 2 Pro", model(module = """{"name":"$name","project_name":"2 Pro"}"""), name)
        }
        for ((fields, expected) in listOf(
            "model" to "Lite", "model" to "HT", "type" to "N3S",
            "hw_version" to "N3S", "hw" to "N3S", "model" to "2 Pro",
            "type" to "N3F", "model" to "AMS 2", "hw_version" to "N3F", "hw" to "N3F"
        )) {
            val result = when (expected) { "Lite" -> "AMS Lite"; "HT", "N3S" -> "AMS HT"; else -> "AMS 2 Pro" }
            assertEquals(result, model(fields = ",\"$fields\":\"$expected\""), "$fields=$expected")
        }
        for ((name, project, expected) in listOf(
            Triple("ams_f1/0", null, "AMS Lite"), Triple("ams/0", "Lite", "AMS Lite"),
            Triple("n3s/0", null, "AMS HT"), Triple("ams/0", "2 Pro", "AMS 2 Pro"),
            Triple("ams/0", "AMS 2", "AMS 2 Pro"), Triple("n3f/0", null, "AMS 2 Pro")
        )) {
            val projectField = project?.let { ",\"project_name\":\"$it\"" }.orEmpty()
            assertEquals(expected, model(module = """{"name":"$name"$projectField}"""), "$name $project")
        }
        for ((hw, expected) in listOf("N3S" to "AMS HT", "N3F" to "AMS 2 Pro")) {
            assertEquals(expected, model(module = """{"name":"ams/0","hw_ver":"$hw"}"""), hw)
        }
    }

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
        for (model in listOf("X1C", "H2D", "P2S", "X2D", "H2C", "H2D Pro")) assertEquals("25.0", entities(report, model).getValue("chamber_temp").state)
        for (model in listOf("P1S", "A1", "A2L")) assertFalse(entities(report, model).containsKey("chamber_temp"))
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
        for((model,rtsp) in listOf("X1C" to true,"H2D" to true,"P2S" to true,"X2D" to true,"H2C" to true,"H2D Pro" to true,"P1S" to false,"A1" to false,"A2L" to false))
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

    @Test fun chamberDoorStatusAndDetectionSwitchAreParsedAndCommandsFormatted() {
        // Real X1C reports: stat is 47A58000 with the door open and closed; only home_flag bit 23 changes (0xC065CD98 closed, 0xC0E5CD98 open).
        assertEquals("on", entities("""{"print": {"stat": "47A58000", "home_flag": ${0xC0E5CD98L}}}""", model = "X1C").getValue("door_open").state)
        assertEquals("off", entities("""{"print": {"stat": "47A58000", "home_flag": ${0xC065CD98L}}}""", model = "X1C").getValue("door_open").state)
        assertFalse(entities("""{"print": {"stat": "46A58008"}}""", model = "X2D").containsKey("door_open"))

        val homeFlagOpen = """{"print": {"home_flag": ${1L shl 23}}}"""
        assertEquals("on", entities(homeFlagOpen, model = "X2D").getValue("door_open").state)

        val closedDoorPayload = """{"print": {"stat": "0", "home_flag": 0}}"""
        assertEquals("off", entities(closedDoorPayload, model = "X2D").getValue("door_open").state)

        val doorCheckPayload = """{"print": {"door_open_check": true}}"""
        assertEquals("on", entities(doorCheckPayload).getValue("door_open_check").state)

        val doorCheckFalsePayload = """{"print": {"door_open_check": false}}"""
        assertEquals("off", entities(doorCheckFalsePayload).getValue("door_open_check").state)

        val doorCheckCfgPayload = """{"print": {"cfg": "${(1L shl 20).toString(16)}"}}"""
        assertEquals("on", entities(doorCheckCfgPayload).getValue("door_open_check").state)

        val doorCheckCfg21Payload = """{"print": {"cfg": "${(1L shl 21).toString(16)}"}}"""
        assertEquals("on", entities(doorCheckCfg21Payload).getValue("door_open_check").state)

        val doorCheckHomeFlagPayload = """{"print": {"home_flag": ${1L shl 26}}}"""
        assertEquals("on", entities(doorCheckHomeFlagPayload).getValue("door_open_check").state)

        val doorCheckHomeFlagOff = """{"print": {"home_flag": 0}}"""
        assertEquals("off", entities(doorCheckHomeFlagOff).getValue("door_open_check").state)

        val openFramePayload = """{"print": {"stat": "${(1L shl 23).toString(16)}", "home_flag": ${(1L shl 26) or (1L shl 23)}}}"""
        assertFalse(entities(openFramePayload, model = "A1").containsKey("door_open"))
        assertFalse(entities(openFramePayload, model = "A1 mini").containsKey("door_open_check"))
        assertFalse(entities(openFramePayload, model = "P1P").containsKey("door_open"))
        assertFalse(entities(openFramePayload, model = "A2L").containsKey("door_open"))
        assertFalse(entities(openFramePayload, model = "A2L").containsKey("door_open_check"))
        assertTrue(entities(openFramePayload, model = "H2C").containsKey("door_open"))
        assertTrue(entities(openFramePayload, model = "H2C").containsKey("door_open_check"))
        assertTrue(entities(openFramePayload, model = "H2D Pro").containsKey("door_open"))
        assertTrue(entities(openFramePayload, model = "H2D Pro").containsKey("door_open_check"))

        val doorCmd = doorStatCommand("42", 1)["system"]!!.jsonObject
        assertEquals("set_door_stat", doorCmd.s("command"))
        assertEquals("1", doorCmd.s("config"))
        assertEquals("42", doorCmd.s("sequence_id"))
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



    @Test
    fun amsDryingCommandsAndLanTelemetry() {
        val startCmd = amsDryingCommand("101", 0, 55, 6, true)["print"]!!.jsonObject
        assertEquals("ams_filament_drying", startCmd.s("command"))
        assertEquals("1", startCmd.s("mode"))
        assertEquals("0", startCmd.s("ams_id"))
        assertEquals("55", startCmd.s("temp"))
        assertEquals("6", startCmd.s("duration"))
        assertEquals("45", startCmd.s("cooling_temp"))
        assertEquals("true", startCmd.s("rotate_tray"))

        val stopCmd = amsStopDryingCommand("102", 0)["print"]!!.jsonObject
        assertEquals("ams_filament_drying", stopCmd.s("command"))
        assertEquals("0", stopCmd.s("mode"))
        assertEquals("0", stopCmd.s("ams_id"))

        val startCmdNoRotate = amsDryingCommand("101", 0, 50, 4, false)["print"]!!.jsonObject
        assertEquals("false", startCmdNoRotate.s("rotate_tray"))

        val htStartCmd = amsDryingCommand("103", 128, 85, 12, false)["print"]!!.jsonObject
        assertEquals("ams_filament_drying", htStartCmd.s("command"))
        assertEquals("1", htStartCmd.s("mode"))
        assertEquals("128", htStartCmd.s("ams_id"))
        assertEquals("85", htStartCmd.s("temp"))
        assertEquals("12", htStartCmd.s("duration"))
        assertEquals("false", htStartCmd.s("rotate_tray"))

        val amsReport = """{
            "print": {
                "ams": {
                    "ams": [
                        {
                            "id": "0",
                            "model": "AMS 2 Pro",
                            "hw_version": "N3F05",
                            "is_drying": true,
                            "remain_time": 180,
                            "target_temp": 55,
                            "temp": 48.0,
                            "tray": []
                        }
                    ]
                }
            }
        }"""
        val amsEntities = lanEntities(config, obj(amsReport), true, null)
            .filter { it.first.device == "ams0" }
            .associate { it.first.key to it.second }

        assertEquals("on", amsEntities.getValue("drying").state)
        assertEquals("3.0", amsEntities.getValue("remaining_drying_time").state)
        assertEquals("55.0", amsEntities.getValue("drying_target_temp").state)
        assertEquals("48.0", amsEntities.getValue("ams_temp").state)

        // Test drying_status string format and module info detection
        val moduleReport = """{
            "print": {
                "ams": {
                    "ams": [
                        {
                            "id": "0",
                            "drying_status": "drying",
                            "tray": []
                        },
                        {
                            "id": "1",
                            "drying_status": "idle",
                            "tray": []
                        },
                        {
                            "id": "2",
                            "tray": []
                        },
                        {
                            "id": "128",
                            "type": "N3S",
                            "tray": []
                        }
                    ]
                }
            },
            "info": {
                "module": [
                    {"name": "n3f/0", "hw_ver": "N3F02", "project_name": "AMS 2 Pro"},
                    {"name": "ams/1", "hw_ver": "AMS08", "project_name": "AMS"},
                    {"name": "ams_f1/2", "hw_ver": "AMS_F1", "project_name": "AMS Lite"},
                    {"name": "n3s/128", "hw_ver": "N3S01", "project_name": "AMS HT"}
                ]
            }
        }"""
        val moduleEntities = lanEntities(config, obj(moduleReport), true, null)
        val ams0Rows = moduleEntities.filter { it.first.device == "ams0" }
        assertEquals("AMS 2 Pro", ams0Rows.first().first.deviceModel)
        assertEquals("on", ams0Rows.first { it.first.key == "drying" }.second.state)

        val ams1Rows = moduleEntities.filter { it.first.device == "ams1" }
        assertEquals("AMS", ams1Rows.first().first.deviceModel)
        assertFalse(ams1Rows.any { it.first.key == "drying" })

        val ams2Rows = moduleEntities.filter { it.first.device == "ams2" }
        assertEquals("AMS Lite", ams2Rows.first().first.deviceModel)
        assertFalse(ams2Rows.any { it.first.key == "drying" })

        val amsHtRows = moduleEntities.filter { it.first.device == "ams128" }
        assertEquals("AMS HT", amsHtRows.first().first.deviceModel)

        val legacyAmsWithTemp = """{
            "print": {
                "ams": {
                    "ams": [
                        {
                            "id": "0",
                            "model": "AMS",
                            "temp": 25.0,
                            "tray": []
                        }
                    ]
                }
            }
        }"""
        val legacyRows = lanEntities(config, obj(legacyAmsWithTemp), true, null).filter { it.first.device == "ams0" }
        assertEquals("AMS", legacyRows.first().first.deviceModel)
        assertFalse(legacyRows.any { it.first.key == "drying" })

        // AMS 2 Pro reporting drying_temp fallback and idle status
        val ams2IdleReport = """{
            "print": {
                "ams": {
                    "ams": [
                        {
                            "id": "0",
                            "drying_status": "idle",
                            "drying_temp": 50.0,
                            "remain_time": 0.0,
                            "tray": []
                        }
                    ]
                }
            },
            "info": {
                "module": [
                    {"name": "n3f/0", "hw_ver": "N3F01", "project_name": "AMS 2 Pro"}
                ]
            }
        }"""
        val idleRows = lanEntities(config, obj(ams2IdleReport), true, null).filter { it.first.device == "ams0" }
        assertEquals("AMS 2 Pro", idleRows.first().first.deviceModel)
        assertEquals("off", idleRows.first { it.first.key == "drying" }.second.state)
        assertEquals("50.0", idleRows.first { it.first.key == "drying_target_temp" }.second.state)
        assertEquals("0.0", idleRows.first { it.first.key == "remaining_drying_time" }.second.state)

        // Alternate module names (ams_f1_0, ams_1)
        val altModuleReport = """{
            "print": {"ams": {"ams": [{"id": 0, "tray": []}]}},
            "info": {"module": [{"name": "ams_f1_0", "hw_ver": "AMS_F1", "project_name": "Lite"}]}
        }"""
        val altRows = lanEntities(config, obj(altModuleReport), true, null).filter { it.first.device == "ams0" }
        assertEquals("AMS Lite", altRows.first().first.deviceModel)
    }

    @Test fun x2dModernAiModulesAndPrintOptionsAreParsedFromBitmapsAndPayloads() {
        // MOCK-X2D payload structure with integer xcam.cfg (8089015 = 0x7B6DB7 -> bits 20, 21, 22 set)
        // and top-level cfg hex "5A1045FDA99" (bit 12=1, bit 15=1, bit 16=1, bit 19=1, bit 20=1, bit 22=1, bit 23=0)
        val x2dPayload = """{
            "print": {
                "xcam": {
                    "allow_skip_parts": false,
                    "buildplate_marker_detector": true,
                    "cfg": 8089015,
                    "first_layer_inspector": true,
                    "halt_print_sensitivity": "medium",
                    "print_halt": true,
                    "printing_monitor": true,
                    "spaghetti_detector": true
                },
                "cfg": "5A1045FDA99",
                "home_flag": -1066967905,
                "stat": "46258008"
            }
        }"""
        val e = entities(x2dPayload, model = "X2D")
        assertEquals("on", e.getValue("printing_monitor").state)
        assertEquals("on", e.getValue("buildplate_marker_detector").state)
        assertEquals("on", e.getValue("first_layer_inspector").state)
        assertEquals("on", e.getValue("plate_offset_switch").state)
        assertEquals("on", e.getValue("fod_check").state)
        assertEquals("on", e.getValue("model_movement_check").state)
        assertEquals("on", e.getValue("auto_recovery").state)
        assertEquals("on", e.getValue("print_cache").state)
        assertEquals("on", e.getValue("door_open_check").state)
        assertEquals("on", e.getValue("prompt_sound").state)
        assertFalse(e.containsKey("filament_tangle_detect"))
        assertFalse(e.containsKey("nozzle_blob_detect"))
        assertEquals("off", e.getValue("door_open").state)

        // When a model advertises tangle and blob support via capability bits
        val supportedMask = (1L shl 19) or (1L shl 20) or (1L shl 25) or (1L shl 24)
        val supportedPayload = """{"print": {"home_flag": $supportedMask}}"""
        val sup = entities(supportedPayload)
        assertEquals("on", sup.getValue("filament_tangle_detect").state)
        assertEquals("on", sup.getValue("nozzle_blob_detect").state)

        // System and print option command formatting
        val cacheCmd = printCacheCommand("43", true)["system"]!!.jsonObject
        assertEquals("print_cache_set", cacheCmd.s("command"))
        assertEquals("true", cacheCmd.s("config"))

        val blobCmd = printOptionCommand("44", "nozzle_blob_detect", true)["print"]!!.jsonObject
        assertEquals("print_option", blobCmd.s("command"))
        assertEquals("1", blobCmd.s("nozzle_blob_detect_v2"))

        val soundCmd = printOptionCommand("45", "sound_enable", false)["print"]!!.jsonObject
        assertEquals("false", soundCmd.s("sound_enable"))
    }

    @Test fun calibrationDispatchesNozzleOffsetAndMapsStage39() {
        val cmd = calibrationCommand("42", 16)["print"]!!.jsonObject
        assertEquals("calibration", cmd.s("command"))
        assertEquals("16", cmd.s("option"))

        val combined = calibrationCommand("43", 2 or 4 or 16)["print"]!!.jsonObject
        assertEquals("22", combined.s("option"))

        assertEquals("calibrate_nozzle_offset", stageName(39))
    }

    @Test fun dualExternalSpoolParsingAndCommands() {
        val dualVirSlotReport = """{
            "print": {
                "ams": {
                    "tray_now": "254"
                },
                "vir_slot": [
                    {
                        "id": "254",
                        "tray_type": "PLA",
                        "tray_sub_brands": "Bambu PLA Basic",
                        "tray_color": "F98C36FF",
                        "remain": 75,
                        "nozzle_temp_min": "190",
                        "nozzle_temp_max": "230",
                        "tray_info_idx": "GFA00"
                    },
                    {
                        "id": "255",
                        "tray_type": "PETG",
                        "tray_sub_brands": "Bambu PETG HF",
                        "tray_color": "00AE42FF",
                        "remain": 45,
                        "nozzle_temp_min": "220",
                        "nozzle_temp_max": "260",
                        "tray_info_idx": "GFB00"
                    }
                ]
            }
        }"""
        val x2dConfig = config.copy(model = "X2D")
        val rows = lanEntities(x2dConfig, obj(dualVirSlotReport), true)
        val extSpools = rows.filter { it.first.key == "external_spool" }
        assertEquals(2, extSpools.size)

        val ext1 = extSpools.first { it.first.device == "ext" }
        assertEquals("ExternalSpool_external_spool", ext1.first.uid)
        assertEquals("Bambu PLA Basic", ext1.second.state)
        assertEquals("PLA", ext1.second.attrs.s("type"))
        assertEquals("true", ext1.second.attrs.s("active"))
        assertEquals("false", ext1.second.attrs.s("empty"))

        val ext2 = extSpools.first { it.first.device == "ext2" }
        assertEquals("ExternalSpool2_external_spool", ext2.first.uid)
        assertEquals("Bambu PETG HF", ext2.second.state)
        assertEquals("PETG", ext2.second.attrs.s("type"))
        assertEquals("false", ext2.second.attrs.s("active"))
        assertEquals("false", ext2.second.attrs.s("empty"))

        // When tray_now switches to 255
        val switchedReport = """{
            "print": {
                "ams": { "tray_now": "255" },
                "vir_slot": [
                    { "id": "254", "tray_type": "PLA" },
                    { "id": "255", "tray_type": "PETG" }
                ]
            }
        }"""
        val switchedRows = lanEntities(x2dConfig, obj(switchedReport), true).filter { it.first.key == "external_spool" }
        assertEquals("false", switchedRows.first { it.first.device == "ext" }.second.attrs.s("active"))
        assertEquals("true", switchedRows.first { it.first.device == "ext2" }.second.attrs.s("active"))

        // Devices and entity registries via printersFrom
        val devs = json.parseToJsonElement("""[
            {"id":"p1","name":"X2D","model":"X2D","identifiers":[["bambu_lab","TESTSERIAL"]]},
            {"id":"p1_ext","via_device_id":"p1","model":"External Spool","identifiers":[["bambu_lab","TESTSERIAL_ExternalSpool"]]},
            {"id":"p1_ext2","via_device_id":"p1","model":"External Spool","identifiers":[["bambu_lab","TESTSERIAL_ExternalSpool2"]]}
        ]""").jsonArray
        val ents = json.parseToJsonElement("""[
            {"entity_id":"sensor.p1_external_spool","device_id":"p1_ext","platform":"bambu_lab","translation_key":"external_spool","unique_id":"TESTSERIAL_ExternalSpool_external_spool"},
            {"entity_id":"sensor.p1_external_spool_2","device_id":"p1_ext2","platform":"bambu_lab","translation_key":"external_spool","unique_id":"TESTSERIAL_ExternalSpool2_external_spool"}
        ]""").jsonArray
        val printers = printersFrom(devs, ents)
        val pInfo = printers.single()
        assertEquals(listOf("sensor.p1_external_spool", "sensor.p1_external_spool_2"), pInfo.external)
        val p = Printer(pInfo, mapOf(
            "sensor.p1_external_spool" to ext1.second,
            "sensor.p1_external_spool_2" to ext2.second
        ))
        assertEquals(2, p.external.size)
        assertEquals("PLA", p.external[0].type)
        assertEquals("PETG", p.external[1].type)
        assertEquals(setOf(254, 255), p.loadedTrayIds())
        assertEquals(listOf(254 to p.external[0], 255 to p.external[1]), p.loadedSpools())

        // Auto mapping
        val mapped = autoMapping(p, listOf(
            mapOf("type" to "PETG", "color" to "#00AE42FF"),
            mapOf("type" to "PLA", "color" to "#F98C36FF")
        ))
        assertEquals(listOf(255, 254), mapped)

        // Setting commands for external spools
        val preset = FILAMENT_PRESETS.first { it.type == "PLA" }
        val ext1Cmd = amsFilamentSettingCommand("201", 255, 0, 254, preset, "F98C36")["print"]!!.jsonObject
        assertEquals("255", ext1Cmd.s("ams_id"))
        assertEquals("0", ext1Cmd.s("slot_id"))
        assertEquals("254", ext1Cmd.s("tray_id"))

        val ext2Cmd = amsFilamentSettingCommand("202", 255, 0, 255, preset, "00AE42")["print"]!!.jsonObject
        assertEquals("255", ext2Cmd.s("ams_id"))
        assertEquals("0", ext2Cmd.s("slot_id"))
        assertEquals("255", ext2Cmd.s("tray_id"))
    }
}
