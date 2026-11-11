// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy

import kotlinx.serialization.json.*
import kotlin.test.*

/** LAN report parsing on the firmware shapes the main tests do not use: nested switches, bitmaps and dual-nozzle data. */
class LanReportEdgeTest {
    private fun rows(body: String, model: String = "H2D", online: Boolean = true) =
        lanEntities(LanConfig("192.0.2.7", "MOCK-SERIAL", "00000000", model), Json.parseToJsonElement("""{"print":$body}""").jsonObject, online, null)
    private fun List<Pair<LanRow, Entity>>.v(key: String, device: String = "printer") = firstOrNull { it.first.key == key && it.first.device == device }?.second?.state
    private fun List<Pair<LanRow, Entity>>.halt(key: String) = firstOrNull { it.first.key == key }?.second?.attrs?.get("print_halt")?.jsonPrimitive?.contentOrNull

    @Test fun modelNamesFromSerialPrefixesAndCodes() {
        assertEquals("H2D Pro", modelName(null, "H2DPRO123")); assertEquals("H2D Pro", modelName("", "h2d pro 1"))
        assertEquals("H2D Pro", modelName(" ", "MOCK-H2DPRO-1")); assertEquals("H2C", modelName(null, "MOCK-H2C-1")); assertEquals("A2L", modelName(null, "MOCK-A2L-1"))
        assertEquals("H2D Pro", modelName("H2DPRO")); assertEquals("H2D Pro", modelName("Bambu Lab H2D Pro")); assertEquals("H2D Pro", modelName("Bambu Lab H2DPRO"))
        assertEquals("H2C", modelName("Bambu Lab H2C")); assertEquals("A2L", modelName("Bambu Lab A2L")); assertEquals("Mochi X", modelName("Mochi X"))
        assertEquals("H2C", modelName(null, "H2C0001")); assertEquals("Bambu printer", modelName(null, "ZZZ"))
    }

    @Test fun nestedXcamSwitchesReadEnableAndPrintHalt() {
        val r = rows("""{"xcam":{"printing_monitor":{"enable":true,"print_halt":true},"buildplate_marker_detector":{"enable":"off"},"first_layer_inspector":"on",
            "first_layer_inspector_print_halt":false,"plate_offset_switch":true,"fod_check":false,"model_movement_check":"enable","print_halt":true,
            "spaghetti_detector":true,"pileup_detector":{"enable":false,"print_halt":true},"clump_detector":false,"clump_detector_print_halt":true}}""")
        assertEquals("on", r.v("printing_monitor")); assertEquals("true", r.halt("printing_monitor"))
        assertEquals("off", r.v("buildplate_marker_detector")); assertEquals("true", r.halt("buildplate_marker_detector"))
        assertEquals("on", r.v("first_layer_inspector")); assertEquals("false", r.halt("first_layer_inspector"))
        assertEquals("on", r.v("plate_offset_switch")); assertEquals("off", r.v("fod_check")); assertEquals("on", r.v("model_movement_check"))
        assertEquals("on", r.v("spaghetti_detector")); assertEquals("true", r.halt("spaghetti_detector"))
        assertEquals("off", r.v("pileup_detector")); assertEquals("true", r.halt("pileup_detector"))
        assertEquals("off", r.v("clump_detector")); assertEquals("true", r.halt("clump_detector"))
        assertNull(r.v("air_print_detector"))
    }

    @Test fun legacyXcamNamesAndHaltFallbacks() {
        val r = rows("""{"xcam":{"spaghetti_detector":"1","buildplate_marker_detector":1,"buildplate_marker_detector_print_halt":true,"first_layer_inspector":0,
            "buildplate_align_detector":"1","displacement_detector":"0","cfg":${(1 shl 20) or (1 shl 21) or (1 shl 22)}}}""")
        assertEquals("on", r.v("printing_monitor")); assertNull(r.halt("printing_monitor"))
        assertEquals("true", r.halt("buildplate_marker_detector")); assertEquals("off", r.v("first_layer_inspector"))
        assertEquals("on", r.v("plate_offset_switch")); assertEquals("off", r.v("model_movement_check"))
        assertEquals("on", r.v("fod_check"))
        val bits = rows("""{"xcam":{"cfg":${(1 shl 20) or (1 shl 21) or (1 shl 22)}}}""")
        assertEquals("on", bits.v("plate_offset_switch")); assertEquals("on", bits.v("fod_check")); assertEquals("on", bits.v("model_movement_check"))
    }

    @Test fun hexConfigBitmapDrivesMonitorsAndOptions() {
        val cfg = (1L shl 15) or (1L shl 13) or (1L shl 12) or (1L shl 16) or (1L shl 19) or (1L shl 22) or (1L shl 23) or (1L shl 43)
        val r = rows("""{"cfg":"${cfg.toString(16)}","fun":"${(1L shl 9 or (1L shl 13)).toString(16)}"}""")
        assertEquals("on", r.v("printing_monitor")); assertEquals("true", r.halt("printing_monitor"))
        assertEquals("on", r.v("first_layer_inspector")); assertEquals("on", r.v("auto_recovery")); assertEquals("off", r.v("auto_switch_filament"))
        assertEquals("on", r.v("print_cache")); assertEquals("on", r.v("prompt_sound")); assertEquals("on", r.v("filament_tangle_detect")); assertEquals("on", r.v("nozzle_blob_detect"))
        val off = rows("""{"cfg":"0","fun":"${(1L shl 9).toString(16)}"}""")
        assertEquals("off", off.v("printing_monitor")); assertEquals("false", off.halt("printing_monitor")); assertEquals("off", off.v("filament_tangle_detect")); assertNull(off.v("nozzle_blob_detect"))
        val blob44 = rows("""{"cfg":"${(1L shl 44).toString(16)}","fun2":"${(1L shl 15).toString(16)}"}""")
        assertEquals("on", blob44.v("nozzle_blob_detect"))
    }

    @Test fun homeFlagBitsEnableTangleAndBlobSupport() {
        val flag = (1L shl 19) or (1L shl 20) or (1L shl 25) or (1L shl 24) or (1L shl 17)
        val r = rows("""{"home_flag":"$flag"}""")
        assertEquals("on", r.v("filament_tangle_detect")); assertEquals("on", r.v("nozzle_blob_detect")); assertEquals("on", r.v("prompt_sound"))
        val none = rows("""{"home_flag":"${1L shl 3}"}""")
        assertNull(none.v("filament_tangle_detect")); assertNull(none.v("nozzle_blob_detect"))
    }

    @Test fun printOptionObjectsAndFlatKeysAreBothRead() {
        val r = rows("""{"print_option":{"filament_tangle_detect":"enable","nozzle_blob_detect":false,"sound_enable":"off"}}""")
        assertEquals("on", r.v("filament_tangle_detect")); assertEquals("off", r.v("nozzle_blob_detect")); assertEquals("off", r.v("prompt_sound"))
        val flat = rows("""{"filament_tangle_detect":"on","prompt_sound":"on","fun":"${(1L shl 9).toString(16)}"}""")
        assertEquals("on", flat.v("filament_tangle_detect")); assertEquals("on", flat.v("prompt_sound"))
        val prompt = rows("""{"print_option":{"prompt_sound":true}}""")
        assertEquals("on", prompt.v("prompt_sound"))
    }

    @Test fun dualNozzleExtruderAndNozzleInfoAreSplitLeftAndRight() {
        val r = rows("""{"device":{"extruder":{"state":16,"info":[{"id":0,"temp":${(200L shl 16) or 190L}},{"id":1,"temp":"${(210L shl 16) or 205L}"},{"id":5,"temp":1},{"temp":2}]},
            "nozzle":{"info":[{"id":0,"diameter":0.4,"type":"HS01"},{"id":1,"diameter":"0.6","type":"HH01"},{"id":3,"diameter":9},{"diameter":1}]}}}""")
        assertEquals("190.0", r.v("right_nozzle_temp")); assertEquals("200", r.v("right_target_nozzle_temp")); assertEquals("205.0", r.v("left_nozzle_temp"))
        assertEquals("210", r.v("target_left_nozzle_temperature")); assertEquals("left", r.v("active_nozzle"))
        assertEquals("0.4", r.v("right_nozzle_diameter")); assertEquals("0.6", r.v("left_nozzle_diameter"))
        assertEquals("high_flow_hardened_steel", r.v("left_nozzle_type"))
        val right = rows("""{"device":{"extruder":{"info":[{"id":0},{"id":1}]}}}""")
        assertEquals("right", right.v("active_nozzle")); assertNull(right.v("right_nozzle_temp"))
        val single = rows("""{"device":{"extruder":{"info":[{"id":0}]},"nozzle":{"info":[{"id":0}]}}}""")
        assertNull(single.v("active_nozzle")); assertNull(single.v("right_nozzle_diameter"))
    }

    @Test fun virtualSlotsReplaceTheSingleExternalSpool() {
        val r = rows("""{"ams":{"tray_now":"255"},"vir_slot":[{"id":"255","tray_type":"PETG","tray_sub_brands":"Mochi PETG"},{"id":254,"tray_type":""},{"tray_type":"PLA"}]}""")
        val ext = r.filter { it.first.key == "external_spool" }
        assertEquals(3, ext.size)
        assertEquals("Mochi PETG", r.v("external_spool", "ext2")); assertEquals(listOf("Empty", "PLA").sorted(), ext.filter { it.first.device == "ext" }.map { it.second.state }.sorted())
        val single = rows("""{"vt_tray":{"tray_type":"PLA","tray_sub_brands":" "}}""")
        assertEquals("PLA", single.v("external_spool", "ext"))
    }

    @Test fun doorChecksAreSkippedOnOpenFramePrinters() {
        val body = """{"home_flag":"${(1L shl 23) or (1L shl 26)}"}"""
        assertEquals("on", rows(body, "X1C").v("door_open")); assertEquals("on", rows(body, "X1C").v("door_open_check"))
        assertNull(rows(body, "A1").v("door_open")); assertNull(rows(body, "A2L").v("door_open_check"))
        assertEquals("on", rows("""{"cfg":"${(1L shl 20).toString(16)}"}""", "X1C").v("door_open_check"))
        assertEquals("off", rows("""{"door_open_check":"off"}""", "X1C").v("door_open_check"))
    }

    @Test fun amsModuleLookupAcceptsEveryNamingScheme() {
        for (name in listOf("ams/0", "ams0", "ams_f1/0", "ams_f1_0", "n3f/0", "n3s/0", "ams_1")) {
            val r = lanEntities(LanConfig("192.0.2.7", "S", "0", "X1C"), Json.parseToJsonElement(
                """{"print":{"ams":{"ams":[{"id":0,"tray":[]}]}},"info":{"module":[{"name":"$name","hw_ver":"AMS08","project_name":"N3F"}]}}""").jsonObject, true, null)
            assertTrue(r.any { it.first.key == "tray" && it.first.device == "ams0" }, name)
        }
    }
}
