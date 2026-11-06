package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class AmsTest {
    @Test fun homeAssistantRecognizesIndependentAmsModelAndDryingHints() {
        val printer = json.parseToJsonElement("""{"id":"p","identifiers":[["bambu_lab","DEMO"]]}""").jsonObject
        for ((model, dryingRow) in listOf(
            "2 Pro" to null, "N3F" to null, "AMS 2" to null,
            "AMS" to "\"translation_key\":\"drying\"",
            "AMS" to "\"unique_id\":\"demo_drying_status\"",
            "AMS" to "\"entity_id\":\"binary_sensor.demo_drying\""
        )) {
            val device = json.parseToJsonElement("""{"id":"a","via_device_id":"p","model":"$model","identifiers":[["bambu_lab","DEMO_ams_0"]]}""").jsonObject
            val tray = json.parseToJsonElement("""{"device_id":"a","platform":"bambu_lab","entity_id":"sensor.demo_tray_2","unique_id":"renamed"}""").jsonObject
            val rows = mutableListOf(tray)
            if (dryingRow != null) {
                val eid = if (dryingRow.startsWith("\"entity_id\"")) "" else "\"entity_id\":\"binary_sensor.demo_status\","
                rows += json.parseToJsonElement("""{"device_id":"a","platform":"bambu_lab",$eid$dryingRow}""").jsonObject
            }
            val ams = printersFrom(JsonArray(listOf(printer, device)), JsonArray(rows)).single().ams.single()
            assertEquals("AMS 2 Pro", ams.model, "$model $dryingRow")
            assertEquals(mapOf(2 to "sensor.demo_tray_2"), ams.trays)
        }

        val device = json.parseToJsonElement("""{"id":"a","via_device_id":"p","model":"AMS","identifiers":[["bambu_lab","DEMO_ams_0"]]}""").jsonObject
        val rows = JsonArray(listOf(
            json.parseToJsonElement("""{"device_id":"a","platform":"bambu_lab","entity_id":"sensor.demo_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"device_id":"a","platform":"bambu_lab","entity_id":"sensor.demo_remaining","unique_id":"demo_remaining_drying"}""").jsonObject
        ))
        val ams = printersFrom(JsonArray(listOf(printer, device)), rows).single().ams.single()
        assertEquals("sensor.demo_remaining", ams.ids["sensor.remaining_drying_time"])
    }

    private fun mappingPrinter(): Printer {
        val ams=AmsInfo("ams",1,"AMS",emptyMap(),mapOf(1 to "sensor.white",2 to "sensor.green"))
        val info=PrinterInfo("printer","serial","Printer","X1C",null,emptyMap(),listOf(ams),emptyList())
        return Printer(info,listOf("white" to "#FFFFFF","green" to "#00FF00").associate { (name,color) ->
            "sensor.$name" to Entity("sensor.$name","PLA",json.parseToJsonElement("""{"type":"PLA","color":"$color"}""").jsonObject)
        })
    }

    @Test fun slicerMappingPreservesUnusedSlotsAndRefusesUnmatchedFilaments() {
        val p=mappingPrinter()
        assertEquals(listOf(0,-1,1),amsMappingFor(p,listOf(mapOf("id" to "1","type" to "PLA","color" to "#FFFFFF"),
            mapOf("id" to "3","type" to "pla","color" to "#00FF00"))))
        assertEquals(listOf(0),amsMappingFor(p,listOf(mapOf("id" to "bad","type" to "PLA","color" to "#FFFFFF"))))
        assertEquals(listOf(0),amsMappingFor(p,listOf(mapOf("type" to "PLA","color" to "#FFFFFF"))))
        assertNull(amsMappingFor(p,emptyList()))
        assertNull(amsMappingFor(p,listOf(mapOf("type" to "PETG"))))
        val bare=Printer(p.info.copy(ams=emptyList()),emptyMap())
        assertNull(amsMappingFor(bare,listOf(mapOf("type" to "PLA"))))
    }

    @Test
    fun mapsStandardAndAmsHtTrayIds() {
        val standard = AmsInfo("standard", 2, "AMS", emptyMap(), emptyMap())
        assertEquals(4, standard.trayId(0))
        assertEquals(7, standard.trayId(3))
        assertEquals(0, standard.slotForTrayId(4))
        assertNull(standard.slotForTrayId(8))

        val ht = AmsInfo("ht", 1, "AMS HT", emptyMap(), emptyMap())
        assertEquals(128, ht.trayId(0))
        assertEquals(0, ht.slotForTrayId(128))
        assertNull(ht.slotForTrayId(129))
        assertEquals(128, AmsInfo("ht2", 2, "AMS HT", emptyMap(), emptyMap()).trayId(0))
        assertEquals(129, AmsInfo("ht2", 2, "AMS HT", emptyMap(), emptyMap(), protocolId = 129).trayId(0))
    }

    @Test
    fun homeAssistantHtIdDoesNotCountStandardAms() {
        val serial = "TESTSERIAL"
        val devices = JsonArray(listOf(
            json.parseToJsonElement("""{"id":"printer","model":"X1C","name":"X1C","via_device_id":null,"identifiers":[["bambu_lab","$serial"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"standard","model":"AMS","name":"AMS","via_device_id":"printer","identifiers":[["bambu_lab","${serial}_ams0"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ht","model":"AMS HT","name":"AMS HT","via_device_id":"printer","identifiers":[["bambu_lab","${serial}_ams128"]]}""").jsonObject,
        ))
        val entities = JsonArray(listOf(
            json.parseToJsonElement("""{"entity_id":"sensor.standard_tray_1","device_id":"standard","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ht_tray_1","device_id":"ht","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
        ))
        val ams = printersFrom(devices, entities).single().ams
        assertEquals(listOf(0, 128), ams.map { it.amsId() })
    }

    @Test
    fun ams2ProAutoDetectionFromHwVersionOrDryingEntities() {
        val serial = "TEST_SERIAL"
        val devices = JsonArray(listOf(
            json.parseToJsonElement("""{"id":"p1","identifiers":[["bambu_lab","$serial"]]}""").jsonObject,
            // Case 1: HA reports model as generic "AMS", but hw_version is N3F01
            json.parseToJsonElement("""{"id":"ams_hw","via_device_id":"p1","model":"AMS","hw_version":"N3F01","identifiers":[["bambu_lab","${serial}_AMS_0"]]}""").jsonObject,
            // Case 2: HA reports model as generic "AMS", but has drying entities
            json.parseToJsonElement("""{"id":"ams_dry","via_device_id":"p1","model":"AMS","identifiers":[["bambu_lab","${serial}_AMS_1"]]}""").jsonObject,
            // Case 3: HA reports model as generic "AMS", but has ams_temp and multiple trays
            json.parseToJsonElement("""{"id":"ams_temp","via_device_id":"p1","model":"AMS","identifiers":[["bambu_lab","${serial}_AMS_2"]]}""").jsonObject,
            // Case 4: Standard legacy AMS 1 (no temperature, no drying)
            json.parseToJsonElement("""{"id":"ams_legacy","via_device_id":"p1","model":"AMS","identifiers":[["bambu_lab","${serial}_AMS_3"]]}""").jsonObject,
        ))
        val entities = JsonArray(listOf(
            json.parseToJsonElement("""{"entity_id":"sensor.ams0_tray_1","device_id":"ams_hw","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams0_tray_2","device_id":"ams_hw","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_2"}""").jsonObject,

            json.parseToJsonElement("""{"entity_id":"sensor.ams1_tray_1","device_id":"ams_dry","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams1_tray_2","device_id":"ams_dry","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_2"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"binary_sensor.ams1_drying","device_id":"ams_dry","platform":"bambu_lab","translation_key":"drying","unique_id":"${serial}_drying"}""").jsonObject,

            json.parseToJsonElement("""{"entity_id":"sensor.ams2_tray_1","device_id":"ams_temp","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams2_tray_2","device_id":"ams_temp","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_2"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams2_temp","device_id":"ams_temp","platform":"bambu_lab","translation_key":"ams_temp","unique_id":"${serial}_temp"}""").jsonObject,

            json.parseToJsonElement("""{"entity_id":"sensor.ams3_tray_1","device_id":"ams_legacy","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams3_tray_2","device_id":"ams_legacy","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_2"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams3_humidity","device_id":"ams_legacy","platform":"bambu_lab","translation_key":"humidity_index","unique_id":"${serial}_humidity"}""").jsonObject,
        ))
        val amsList = printersFrom(devices, entities).single().ams
        assertEquals(4, amsList.size)

        // Case 1: Detected via hw_version
        assertEquals("AMS 2 Pro", amsList[0].model)
        assertEquals("AMS 2 Pro", amsList[0].modelBadge())
        assertEquals(true, amsList[0].supportsDrying())

        // Case 2: Detected via drying entity
        assertEquals("AMS 2 Pro", amsList[1].model)
        assertEquals("AMS 2 Pro", amsList[1].modelBadge())
        assertEquals(true, amsList[1].supportsDrying())

        // Case 3: Original AMS with temperature sensor remains standard AMS (not 2 Pro)
        assertEquals("AMS", amsList[2].model)
        assertNull(amsList[2].modelBadge())
        assertEquals(false, amsList[2].supportsDrying())

        // Case 4: Legacy AMS 1 remains standard AMS without badge or drying
        assertEquals("AMS", amsList[3].model)
        assertNull(amsList[3].modelBadge())
        assertEquals(false, amsList[3].supportsDrying())
    }

    @Test
    fun ams2ProModelIdentificationAndBadges() {
        val ams2ProExplicit = AmsInfo("ams_pro", 1, "AMS 2 Pro", emptyMap(), emptyMap())
        assertEquals(true, ams2ProExplicit.isAms2Pro())
        assertEquals(false, ams2ProExplicit.isHt)
        assertEquals(65, ams2ProExplicit.maxDryingTemp)
        assertEquals(true, ams2ProExplicit.supportsDrying())
        assertEquals("AMS A", ams2ProExplicit.displayName())
        assertEquals("AMS 2 Pro", ams2ProExplicit.modelBadge())

        val ams2ProHw = AmsInfo("ams_hw", 2, "N3F05", emptyMap(), emptyMap())
        assertEquals(true, ams2ProHw.isAms2Pro())
        assertEquals(false, ams2ProHw.isHt)
        assertEquals(65, ams2ProHw.maxDryingTemp)
        assertEquals(true, ams2ProHw.supportsDrying())
        assertEquals("AMS B", ams2ProHw.displayName())
        assertEquals("AMS 2 Pro", ams2ProHw.modelBadge())

        val standard = AmsInfo("ams_std", 1, "AMS", emptyMap(), emptyMap())
        assertEquals(false, standard.isAms2Pro())
        assertEquals(false, standard.isHt)
        assertEquals(65, standard.maxDryingTemp)
        assertEquals(false, standard.supportsDrying())
        assertEquals("AMS A", standard.displayName())
        assertNull(standard.modelBadge())

        val ht = AmsInfo("ams_ht", 1, "AMS HT", emptyMap(), emptyMap())
        assertEquals(false, ht.isAms2Pro())
        assertEquals(true, ht.isHt)
        assertEquals(85, ht.maxDryingTemp)
        assertEquals(true, ht.supportsDrying())
        assertEquals("AMS HT", ht.displayName())
        assertNull(ht.modelBadge())

        val amsLite = AmsInfo("ams_lite", 1, "AMS Lite", emptyMap(), emptyMap())
        assertEquals(false, amsLite.isAms2Pro())
        assertEquals(false, amsLite.isHt)
        assertEquals(65, amsLite.maxDryingTemp)
        assertEquals(false, amsLite.supportsDrying())
        assertEquals("AMS A", amsLite.displayName())
        assertEquals("AMS Lite", amsLite.modelBadge())
    }

    @Test
    fun dryingKeyAndDeviceNameDetectionInHomeAssistant() {
        val serial = "TEST_SERIAL_HA"
        val devices = JsonArray(listOf(
            json.parseToJsonElement("""{"id":"p1","identifiers":[["bambu_lab","$serial"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ams_user_named","via_device_id":"p1","model":"AMS","name_by_user":"My AMS 2 Pro","identifiers":[["bambu_lab","${serial}_AMS_0"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ams_drying_status","via_device_id":"p1","model":"AMS","identifiers":[["bambu_lab","${serial}_AMS_1"]]}""").jsonObject
        ))
        val entities = JsonArray(listOf(
            json.parseToJsonElement("""{"entity_id":"sensor.ams0_tray_1","device_id":"ams_user_named","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams0_target_dry","device_id":"ams_user_named","platform":"bambu_lab","unique_id":"ams0_target_dry_temp"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams0_remain_dry","device_id":"ams_user_named","platform":"bambu_lab","unique_id":"ams0_remain_dry_time"}""").jsonObject,

            json.parseToJsonElement("""{"entity_id":"sensor.ams1_tray_1","device_id":"ams_drying_status","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_1"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"binary_sensor.ams1_status","device_id":"ams_drying_status","platform":"bambu_lab","unique_id":"ams1_drying_status"}""").jsonObject,
        ))
        val amsList = printersFrom(devices, entities).single().ams
        assertEquals(2, amsList.size)
        assertEquals("AMS 2 Pro", amsList[0].model)
        assertEquals("drying_target_temp", amsList[0].ids.entries.first { it.value == "sensor.ams0_target_dry" }.key.substringAfter('.'))
        assertEquals("remaining_drying_time", amsList[0].ids.entries.first { it.value == "sensor.ams0_remain_dry" }.key.substringAfter('.'))

        assertEquals("AMS 2 Pro", amsList[1].model)
        assertEquals("drying", amsList[1].ids.entries.first { it.value == "binary_sensor.ams1_status" }.key.substringAfter('.'))
    }

    @Test
    fun amsDryingTelemetryStates() {
        val amsInfo = AmsInfo(
            "ams1", 1, "AMS 2 Pro",
            ids = mapOf(
                "binary_sensor.drying" to "binary_sensor.ams1_drying",
                "sensor.remaining_drying_time" to "sensor.ams1_drying_remain",
                "sensor.drying_target_temp" to "sensor.ams1_drying_temp"
            ),
            trays = emptyMap()
        )
        val printerInfo = PrinterInfo("p", "serial", "Printer", "X2D", null, emptyMap(), listOf(amsInfo), emptyList())
        val printer = Printer(
            printerInfo,
            mapOf(
                "binary_sensor.ams1_drying" to Entity("binary_sensor.ams1_drying", "on", JsonObject(emptyMap())),
                "sensor.ams1_drying_remain" to Entity("sensor.ams1_drying_remain", "3.5", JsonObject(emptyMap())),
                "sensor.ams1_drying_temp" to Entity("sensor.ams1_drying_temp", "55.0", JsonObject(emptyMap()))
            )
        )

        assertEquals(true, printer.amsIsDrying(amsInfo))
        assertEquals(true, printer.amsDryingState(amsInfo))
        assertEquals(3.5, printer.amsDryingRemaining(amsInfo))
        assertEquals(55.0, printer.amsDryingTargetTemp(amsInfo))

        val emptyPrinter = Printer(printerInfo, emptyMap())
        assertEquals(false, emptyPrinter.amsIsDrying(amsInfo))
        assertNull(emptyPrinter.amsDryingState(amsInfo)) // no reading is unknown, not "idle"
        // A drying sensor that is unavailable or unknown must not read as "not drying" either
        for (st in listOf("unavailable", "unknown")) {
            val unsure = Printer(printerInfo, mapOf("binary_sensor.ams1_drying" to Entity("binary_sensor.ams1_drying", st, JsonObject(emptyMap()))))
            assertNull(unsure.amsDryingState(amsInfo), st)
            assertFalse(unsure.amsIsDrying(amsInfo), st)
        }
        assertNull(emptyPrinter.amsDryingRemaining(amsInfo))
        assertNull(emptyPrinter.amsDryingTargetTemp(amsInfo))
    }

    private class TestBackend : HaBackend {
        override val base = "https://home.example"
        val posts = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun get(path: String) = "{}"
        override suspend fun post(path: String, body: String): String {
            posts += path to json.parseToJsonElement(body).jsonObject
            return "{}"
        }
        override suspend fun bytes(path: String) = byteArrayOf()
        override suspend fun ws(commands: List<JsonObject>) = commands.map { null }
        override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) = error("No stream")
    }

    @Test fun startAndStopAmsDryingDispatchCorrectHomeAssistantServices() = runTest {
        val b = TestBackend()
        val ams = AmsInfo("ams_dev_1", 1, "AMS 2 Pro", emptyMap(), emptyMap(), 0)
        val p = Printer(PrinterInfo("p_ha_id", "serial", "Printer", "X1C", null, emptyMap(), listOf(ams), emptyList()), emptyMap())

        b.startAmsDrying(p, ams, 55, 6, true)
        assertEquals("/api/services/bambu_lab/start_filament_drying", b.posts.last().first)
        val startData = b.posts.last().second
        assertEquals("ams_dev_1", startData.s("device_id"))
        assertEquals("0", startData.s("ams_id"))
        assertEquals("55", startData.s("temp"))
        assertEquals("6", startData.s("duration"))
        assertEquals("true", startData.s("rotate_tray"))

        b.stopAmsDrying(p, ams)
        assertEquals("/api/services/bambu_lab/stop_filament_drying", b.posts.last().first)
        val stopData = b.posts.last().second
        assertEquals("ams_dev_1", stopData.s("device_id"))
        assertEquals("0", stopData.s("ams_id"))

        // Fallback to printer haId when ams haId is blank
        val blankAms = ams.copy(haId = "")
        b.startAmsDrying(p, blankAms, 50, 4, false)
        assertEquals("p_ha_id", b.posts.last().second.s("device_id"))
        assertEquals("false", b.posts.last().second.s("rotate_tray"))

        b.stopAmsDrying(p, blankAms)
        assertEquals("p_ha_id", b.posts.last().second.s("device_id"))

        // AMS HT drying and out-of-range input clamping
        val htAms = AmsInfo("ams_ht_dev", 2, "AMS HT", emptyMap(), emptyMap(), 128)
        b.startAmsDrying(p, htAms, 80, 8, false)
        assertEquals("ams_ht_dev", b.posts.last().second.s("device_id"))
        assertEquals("128", b.posts.last().second.s("ams_id"))
        assertEquals("80", b.posts.last().second.s("temp"))
        assertEquals("8", b.posts.last().second.s("duration"))

        b.startAmsDrying(p, htAms, 85, 12, true)
        assertEquals("ams_ht_dev", b.posts.last().second.s("device_id"))
        assertEquals("128", b.posts.last().second.s("ams_id"))
        assertEquals("85", b.posts.last().second.s("temp"))
        assertEquals("12", b.posts.last().second.s("duration"))
        assertEquals("true", b.posts.last().second.s("rotate_tray"))

        // Out-of-range inputs clamped to the unit's own range and [1..24]: the AMS 2 Pro stops at 65, the AMS HT at 85
        b.startAmsDrying(p, ams, 30, 0, true)
        assertEquals("45", b.posts.last().second.s("temp"))
        assertEquals("1", b.posts.last().second.s("duration"))

        b.startAmsDrying(p, ams, 100, 30, true)
        assertEquals("65", b.posts.last().second.s("temp"))
        assertEquals("24", b.posts.last().second.s("duration"))

        b.startAmsDrying(p, htAms, 100, 8, false)
        assertEquals("85", b.posts.last().second.s("temp"))

        // A unit that cannot dry is refused before anything is sent
        val sent = b.posts.size
        val lite = AmsInfo("ams_lite", 3, "AMS Lite", emptyMap(), emptyMap(), 0)
        assertFailsWith<ApiException> { b.startAmsDrying(p, lite, 55, 4, false) }
        assertEquals(sent, b.posts.size)
    }

    @Test fun amsHtDetectionAndTemperatureLimits() {
        val ht = AmsInfo("ht", 1, "AMS HT", emptyMap(), emptyMap(), 128)
        assertTrue(ht.isHt)
        assertEquals(85, ht.maxDryingTemp)
        assertTrue(ht.supportsDrying())

        val htCaseInsensitive = AmsInfo("ht2", 2, "ams ht", emptyMap(), emptyMap(), 129)
        assertTrue(htCaseInsensitive.isHt)
        assertEquals(85, htCaseInsensitive.maxDryingTemp)

        val htCode = AmsInfo("ht3", 3, "N3S", emptyMap(), emptyMap(), 130)
        assertTrue(htCode.isHt)
        assertEquals(85, htCode.maxDryingTemp)

        val ams2 = AmsInfo("ams2", 1, "AMS 2 Pro", emptyMap(), emptyMap(), 0)
        assertFalse(ams2.isHt)
        assertEquals(65, ams2.maxDryingTemp)

        val ams1 = AmsInfo("ams1", 1, "AMS", emptyMap(), emptyMap(), 0)
        assertFalse(ams1.isHt)
        assertEquals(65, ams1.maxDryingTemp)
    }

    @Test fun amsModelBadgeAndDisplayNamesCoverAllVariants() {
        val ams2ByModel = AmsInfo("1", 1, "AMS 2", emptyMap(), emptyMap(), 0)
        assertTrue(ams2ByModel.isAms2Pro())
        assertEquals("AMS 2 Pro", ams2ByModel.modelBadge())
        assertEquals("AMS A", ams2ByModel.displayName())
        assertEquals(0, ams2ByModel.amsId())

        val ams2ByIdKey = AmsInfo("2", 2, "AMS", mapOf("binary_sensor.drying" to "s1"), emptyMap(), 1)
        assertTrue(ams2ByIdKey.isAms2Pro())
        assertEquals("AMS 2 Pro", ams2ByIdKey.modelBadge())
        assertEquals("AMS B", ams2ByIdKey.displayName())

        val ams2ByRemainKey = AmsInfo("3", 3, "AMS", mapOf("sensor.remaining_drying_time" to "s2"), emptyMap(), 2)
        assertTrue(ams2ByRemainKey.isAms2Pro())

        val ams2ByTargetKey = AmsInfo("4", 4, "AMS", mapOf("sensor.drying_target_temp" to "s3"), emptyMap(), 3)
        assertTrue(ams2ByTargetKey.isAms2Pro())

        val ams2BySubstring = AmsInfo("5", 1, "AMS", mapOf("custom_drying_sensor" to "s4"), emptyMap(), 0)
        assertTrue(ams2BySubstring.isAms2Pro())

        val amsLite = AmsInfo("6", 1, "Bambu AMS Lite", emptyMap(), emptyMap(), 0)
        assertFalse(amsLite.isAms2Pro())
        assertEquals("AMS Lite", amsLite.modelBadge())

        val ht = AmsInfo("7", 1, "AMS HT", emptyMap(), emptyMap(), null)
        assertTrue(ht.isSingleSlot())
        assertEquals("AMS HT", ht.displayName())
        assertEquals(128, ht.amsId())
        assertNull(ht.modelBadge())
    }

    @Test fun printersFromResolvesDryingKeysFromEntityIdWhenUniqueIdIsMissing() {
        val serial = "TEST_SERIAL"
        val devices = JsonArray(listOf(
            json.parseToJsonElement("""{"id":"p1","identifiers":[["bambu_lab","$serial"]],"model":"X1C"}""").jsonObject,
            json.parseToJsonElement("""{"id":"ams_n3f_hw","via_device_id":"p1","model":"AMS","hw_ver":"N3F-1.0","identifiers":[["bambu_lab","${serial}_ams_0"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ams_name_user","via_device_id":"p1","model":"AMS","name_by_user":"AMS 2 Pro Custom","identifiers":[["bambu_lab","${serial}_ams_1"]]}""").jsonObject,
            json.parseToJsonElement("""{"id":"ams_ht_hw","via_device_id":"p1","model":"AMS","hw_version":"N3S-1.0","identifiers":[["bambu_lab","${serial}_ams_128"]]}""").jsonObject,
        ))
        val entities = JsonArray(listOf(
            json.parseToJsonElement("""{"entity_id":"sensor.drying_target_temp","device_id":"ams_n3f_hw","platform":"bambu_lab"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams_remain_drying_time","device_id":"ams_n3f_hw","platform":"bambu_lab"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams_tray_1","device_id":"ams_n3f_hw","platform":"bambu_lab"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"binary_sensor.printer_drying","device_id":"ams_name_user","platform":"bambu_lab"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ams2_tray_1","device_id":"ams_name_user","platform":"bambu_lab"}""").jsonObject,
            json.parseToJsonElement("""{"entity_id":"sensor.ht_tray_0","device_id":"ams_ht_hw","platform":"bambu_lab","translation_key":"tray","unique_id":"${serial}_tray_0"}""").jsonObject,
        ))
        val amsList = printersFrom(devices, entities).single().ams
        assertEquals(3, amsList.size)
        assertEquals("AMS 2 Pro", amsList[0].model)
        assertEquals(true, amsList[0].trays.containsKey(1))

        assertEquals("AMS 2 Pro", amsList[1].model)
        assertEquals("AMS HT", amsList[2].model)
    }

    @Test fun amsEdgeBranchesAndNullFallbacksCovered() {
        val stdAms = AmsInfo("std_ams", 1, "AMS", emptyMap(), emptyMap(), null)
        assertFalse(stdAms.isSingleSlot())
        assertFalse(stdAms.isAms2Pro())
        assertFalse(stdAms.supportsDrying())
        assertNull(stdAms.modelBadge())
        assertEquals("AMS A", stdAms.displayName())
        assertEquals(0, stdAms.amsId())

        val htWithoutProto = AmsInfo("ht_ams", 2, "AMS HT", emptyMap(), emptyMap(), null)
        assertTrue(htWithoutProto.isSingleSlot())
        assertFalse(htWithoutProto.isAms2Pro())
        assertTrue(htWithoutProto.supportsDrying())
        assertNull(htWithoutProto.modelBadge())
        assertEquals("AMS HT", htWithoutProto.displayName())
        assertEquals(128, htWithoutProto.amsId())

        val p = Printer(PrinterInfo("p1", "SERIAL", "Printer", "X1C", null, emptyMap(), listOf(stdAms), emptyList()), emptyMap())
        assertFalse(p.amsIsDrying(stdAms))
        assertNull(p.amsDryingRemaining(stdAms))
        assertNull(p.amsDryingTargetTemp(stdAms))

        val amsWithDryingEntities = stdAms.copy(ids = mapOf(
            "binary_sensor.drying" to "drying_active",
            "sensor.remaining_drying_time" to "drying_rem",
            "sensor.drying_target_temp" to "drying_target"
        ))
        assertTrue(amsWithDryingEntities.isAms2Pro())
        assertTrue(amsWithDryingEntities.supportsDrying())
        assertEquals("AMS 2 Pro", amsWithDryingEntities.modelBadge())

        val statesWithDrying = mapOf(
            "drying_active" to Entity("drying_active", "off", JsonObject(emptyMap())),
            "drying_rem" to Entity("drying_rem", "2.5", JsonObject(emptyMap())),
            "drying_target" to Entity("drying_target", "50.0", JsonObject(emptyMap()))
        )
        val pDrying = Printer(PrinterInfo("p1", "SERIAL", "Printer", "X1C", null, emptyMap(), listOf(amsWithDryingEntities), emptyList()), statesWithDrying)
        assertFalse(pDrying.amsIsDrying(amsWithDryingEntities))
        assertEquals(2.5, pDrying.amsDryingRemaining(amsWithDryingEntities))
        assertEquals(50.0, pDrying.amsDryingTargetTemp(amsWithDryingEntities))
    }
}
