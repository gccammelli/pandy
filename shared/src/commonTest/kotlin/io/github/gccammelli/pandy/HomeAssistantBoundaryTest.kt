package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class HomeAssistantBoundaryTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun arr(text: String) = json.parseToJsonElement(text).jsonArray
    private fun printer(values: Map<String, Entity> = emptyMap(), ams: List<AmsInfo> = emptyList(), external: List<String> = emptyList()): Printer {
        val info = PrinterInfo("ha", "serial", "Saved name", "X1C", null, values.mapValues { it.value.id }, ams, external)
        return Printer(info, values.values.associateBy { it.id })
    }
    private fun e(id: String, state: String = "unknown", attrs: String = "{}") = Entity(id, state, obj(attrs))

    @Test fun absentPrinterDataStaysOfflineAndUnknown() {
        val p = printer()
        assertEquals("Saved name", p.name); assertEquals("offline", p.status)
        assertFalse(p.online); assertFalse(p.printing); assertFalse(p.paused); assertFalse(p.lightOn)
        assertEquals(0, p.progress); assertNull(p.remaining); assertNull(p.layer); assertNull(p.layers)
        assertNull(p.task); assertNull(p.stage); assertNull(p.speed)
        assertNull(p.nozzle); assertNull(p.nozzleTarget); assertNull(p.bed); assertNull(p.bedTarget)
        assertNull(p.chamber); assertNull(p.chamberTarget); assertFalse(p.hasChamberHeater)
        assertNull(p.doorOpen); assertNull(p.camera); assertNull(p.coverImage); assertNull(p.pickImage)
        assertTrue(p.objects.isEmpty()); assertTrue(p.skipped.isEmpty()); assertTrue(p.ams.isEmpty())
        assertTrue(p.external.isEmpty()); assertTrue(p.hms.isEmpty()); assertNull(p.printError)
        assertNull(p.flag("missing")); assertNull(p.flagAttribute("missing", "enabled"))
        assertEquals(listOf(Triple("Part", null, 0)), p.fans); assertEquals(0, p.fansOn)
        assertTrue(p.entityIds.isEmpty())
        assertFalse(e("s", "unavailable").ok); assertFalse(e("s", "unknown").ok); assertTrue(e("s", "off").ok)
    }

    @Test fun liveValuesAndUnrecognizedStatesDoNotBecomeMeasurements() {
        val fields = mapOf("sensor.printer_name" to e("sensor.name", "Printer"), "binary_sensor.online" to e("binary_sensor.online", "on"),
            "sensor.current_layer" to e("sensor.layer", "12"), "sensor.total_layers" to e("sensor.layers", "100"),
            "sensor.print_progress" to e("sensor.progress", "37"), "sensor.speed_profile" to e("sensor.speed", "standard"),
            "image.cover_image" to e("image.cover", "v1"), "image.pick_image" to e("image.pick", "v2"))
        val p = printer(fields)
        assertEquals("Printer", p.name); assertEquals("idle", p.status); assertTrue(p.online)
        assertEquals(12, p.layer); assertEquals(100, p.layers); assertEquals(37, p.progress); assertEquals("standard", p.speed)
        assertEquals("/api/image_proxy/image.cover?v=v1", p.coverImage)
        assertEquals("/api/image_proxy/image.pick?v=v2", p.pickImage)
        for (state in listOf("unavailable", "unknown")) {
            val missing = printer(mapOf("sensor.print_status" to e("sensor.status", state)))
            assertNull(missing.state("sensor.print_status")); assertEquals("offline", missing.status)
        }
        assertEquals("sport", printer(mapOf("select.printing_speed" to e("select.speed", "sport"))).speed)
    }

    @Test fun optionalObjectAndErrorFieldsAreParsedConservatively() {
        val p = printer(mapOf(
            "sensor.printable_objects" to e("sensor.objects", attrs = """{"objects":{"bad":"ignored","1":null,"2":{},"3":"Part"}}"""),
            "sensor.skipped_objects" to e("sensor.skipped", attrs = """{"objects":[{},null,"bad",3]}"""),
            "binary_sensor.hms_errors" to e("binary_sensor.hms", attrs = """{"Count":3,"1-Error":"First","2-Code":"without-message","3-Error":"Third","3-Code":"C3"}"""),
            "binary_sensor.print_error" to e("binary_sensor.error", "on"),
        ))
        assertEquals(mapOf(1 to "", 2 to "", 3 to "Part"), p.objects); assertEquals(listOf(3), p.skipped)
        assertEquals(listOf("" to "First", "C3" to "Third"), p.hms); assertEquals("Print error", p.printError)
        assertNull(printer(mapOf("binary_sensor.print_error" to e("e", "off"))).printError)
        assertTrue(printer(mapOf("binary_sensor.hms_errors" to e("e", "off"))).hms.isEmpty())
    }

    @Test fun allSupportedFlagEncodingsAreDistinctFromUnknown() {
        for (state in listOf("on", "true", "1", "enable", "enabled", "ON", "off", "false", "0", "disable", "disabled", "unknown", "")) {
            val expected = when (state.lowercase()) { "on", "true", "1", "enable", "enabled" -> true; "off", "false", "0", "disable", "disabled" -> false; else -> null }
            val value = e("switch.test", state, """{"flag":"$state"}""")
            val p = printer(mapOf("switch.test" to value))
            assertEquals(expected, p.flag("test")); assertEquals(expected, p.flagAttribute("test", "flag"))
            assertNull(p.flagAttribute("test", "missing"))
            val ams = AmsInfo("ams", 1, "AMS", mapOf("switch.test" to value.id), emptyMap())
            assertEquals(expected, p.amsFlag(ams, "test"))
            assertNull(p.amsNum(ams, "missing")); assertNull(p.amsFlag(ams, "missing"))
        }
        val stale = AmsInfo("ams", 1, "AMS", mapOf("switch.test" to "absent", "sensor.humidity" to "absent"), emptyMap())
        assertNull(printer().amsFlag(stale, "test")); assertNull(printer().amsNum(stale, "sensor.humidity"))
    }

    @Test fun incompleteSpoolsRemainUnknownAndInvalidColorsHaveANeutralFallback() {
        val empty = spoolFrom(e("sensor.tray", "Empty"), 1)
        assertFalse(empty.known); assertTrue(empty.empty); assertEquals(-1, empty.remain)
        assertNull(empty.nozzleMin); assertNull(empty.nozzleMax); assertNull(empty.filamentId)
        assertTrue(empty.colors.isEmpty())
        val value = spoolFrom(e("sensor.tray", "Spool", """{"name":"PLA","type":"PLA","remain":"bad","cols":[{},null,"#FF0000"],"nozzle_temp_min":190,"nozzle_temp_max":230}"""), 2)
        assertTrue(value.known); assertEquals("PLA", value.name); assertEquals(listOf(0xFFFF0000), value.colors)
        assertEquals(190, value.nozzleMin); assertEquals(230, value.nozzleMax)
        for (color in listOf(null, "not-a-color", "#FF000000")) assertEquals(0xFFDDDDDD, hexColor(color))
        assertEquals(0xFF123456, hexColor("#123456XX")); assertEquals(0xFF123456, hexColor("#123456FF"))
    }

    @Test fun partialEntityEventsPreserveStateAndRemoveOnlyNamedAttributes() {
        val states = mutableMapOf("sensor.old" to e("sensor.old", "10", """{"keep":1,"remove":2}"""))
        applyEntityEvent(states, obj("{}"))
        applyEntityEvent(states, obj("""{"a":{"sensor.new":{}},"c":{"sensor.old":{"-":{"a":[{},null,"remove"]}},"sensor.absent":{}},"r":[{},null,"sensor.new"]}"""))
        assertFalse(states.containsKey("sensor.new")); assertEquals("10", states["sensor.old"]!!.state)
        assertEquals(obj("""{"keep":1}"""), states["sensor.old"]!!.attrs)
        assertEquals("", states["sensor.absent"]!!.state); assertTrue(states["sensor.absent"]!!.attrs.isEmpty())
    }

    @Test fun controlAvailabilityDoorAndOptionalFansReflectReportedHardware() {
        for ((developer,hybrid,blocked) in listOf(Triple("off","off",true),Triple("on","on",true),Triple("on","off",false),Triple("unknown","unknown",false))) {
            val p=printer(mapOf("binary_sensor.developer_lan_mode" to e("dev",developer),"binary_sensor.hybrid_mode_blocks_control" to e("hybrid",hybrid)))
            assertEquals(blocked,p.controlBlocked)
        }
        for (door in listOf("on","off")) assertEquals(door=="on",printer(mapOf("binary_sensor.door_open" to e("door",door))).doorOpen)
        val p=printer(mapOf("fan.aux_fan" to e("fan.aux"),"fan.chamber_fan" to e("fan.chamber"),
            "sensor.aux_fan_speed" to e("aux.speed","75"),"sensor.chamber_fan_speed" to e("chamber.speed","unknown")))
        assertEquals(listOf(Triple("Part",null,0),Triple("Aux","fan.aux",75),Triple("Chamber","fan.chamber",0)),p.fans)
        assertEquals(1,p.fansOn)
        assertEquals(0,printer(mapOf("fan.aux_fan" to e("fan.aux"))).fans.last().third)
    }

    @Test fun firmwareWithoutDeveloperModeStillTakesUnsignedCommands() {
        fun p(dev: String?, enc: String?, hybrid: String = "off") = printer(buildMap {
            dev?.let { put("binary_sensor.developer_lan_mode", e("dev", it)) }
            enc?.let { put("binary_sensor.mqtt_encryption", e("enc", it)) }
            put("binary_sensor.hybrid_mode_blocks_control", e("hybrid", hybrid))
        })
        assertFalse(p("off", "off").controlBlocked)              // old firmware has no Developer Mode: unsigned commands are fine
        assertTrue(p("off", "on").controlBlocked)                // newer firmware taking only signed commands
        assertFalse(p("on", "on").controlBlocked)
        assertTrue(p("off", null).controlBlocked)                // support unknown: stay explicit rather than silently assume safe
        assertFalse(p("on", null).controlBlocked)
        assertTrue(p("on", "on", hybrid = "on").controlBlocked)
    }

    @Test fun temperaturesAreReadAndSetInCelsiusEvenWhenHomeAssistantUsesFahrenheit() {
        val p = printer(mapOf(
            "sensor.nozzle_temp" to e("n", "212", """{"unit_of_measurement":"°F"}"""),
            "sensor.target_bed_temp" to e("t", "60", """{"unit_of_measurement":"°C"}"""),
            "sensor.chamber_temp" to e("c", "77"),
            "number.target_nozzle_temperature" to e("number.hot", "482", """{"unit_of_measurement":"°F"}"""),
            "number.target_bed_temperature" to e("number.bed", "60", """{"min":0,"max":248}"""),
        ))
        assertEquals(100.0, p.nozzle)                            // a 212 °F reading is 100 °C on the printer
        assertEquals(60.0, p.bedTarget); assertEquals(77.0, p.chamber) // no or Celsius attribute: taken as Celsius
        assertEquals(482.0, p.setpoint("number.target_nozzle_temperature", 250)) // a Fahrenheit setpoint wants a Fahrenheit value
        assertEquals(-40.0, p.setpoint("number.target_nozzle_temperature", -40))
        assertEquals(60.0, p.setpoint("number.target_bed_temperature", 60))       // a Celsius setpoint passes through
        assertEquals(250.0, printer().setpoint("number.target_nozzle_temperature", 250)) // entity unseen yet: pass through
        val ams = AmsInfo("ams", 1, "AMS", mapOf("sensor.ams_temp" to "a"), emptyMap())
        assertEquals(30.0, printer(mapOf("sensor.ams_temp" to e("a", "86", """{"unit_of_measurement":"°F"}""")), ams = listOf(ams)).amsTemp(ams))
    }

    @Test fun registryNamesAndAmsProtocolIdsSurviveMissingOptionalMetadata() {
        val devices=arr("""[
            {"id":"p","identifiers":[["other","ignore"],["bambu_lab","SERIAL"]]},
            {"id":"ht","via_device_id":"p","model":"AMS HT","identifiers":[["bambu_lab","SERIAL_unknown"]]},
            {"id":"ht2","via_device_id":"p","model":"N3S","identifiers":[["bambu_lab","SERIAL_ams130"]]},
            {"id":"ht3","via_device_id":"p","model":"AMS HT","identifiers":[["bambu_lab","SERIAL_unknown3"]]},
            {"id":"external","via_device_id":"p","identifiers":[["bambu_lab","SERIAL_ext"]]},
            {"id":"other","identifiers":[["other","ignore"]]},{}
        ]""")
        val entities=arr("""[
            {"platform":"other","entity_id":"sensor.ignore","device_id":"p"},
            {"platform":"bambu_lab","disabled_by":"user","entity_id":"sensor.disabled","device_id":"p"},
            {"platform":"bambu_lab","device_id":"p"},
            {"platform":"bambu_lab","device_id":"p","entity_id":"sensor.no_key"},
            {"platform":"bambu_lab","device_id":"p","entity_id":"sensor.fallback","unique_id":"SERIAL_temperature"},
            {"platform":"bambu_lab","device_id":"ht","entity_id":"sensor.ht","translation_key":"tray","unique_id":"SERIAL_tray_1"},
            {"platform":"bambu_lab","device_id":"ht2","entity_id":"sensor.ht2","translation_key":"tray","unique_id":"SERIAL_tray_1"},
            {"platform":"bambu_lab","device_id":"ht3","entity_id":"sensor.bad_slot","translation_key":"tray","unique_id":"SERIAL_tray_bad"},
            {"platform":"bambu_lab","device_id":"external","entity_id":"sensor.external","translation_key":"external_spool"}
        ]""")
        val p=printersFrom(devices,entities).single()
        assertEquals("SERIAL",p.name); assertEquals("",p.model); assertNull(p.firmware)
        assertEquals(mapOf("sensor.temperature" to "sensor.fallback"),p.ids)
        assertEquals(listOf(128,130,131),p.ams.map { it.amsId() })
        assertTrue(p.ams.last().trays.isEmpty()); assertEquals(listOf("sensor.external"),p.external)
        assertEquals("external",p.externalDevice)
        val missing=Printer(p,mapOf("sensor.external" to e("sensor.external","PLA","""{"type":"PLA"}""")))
        assertEquals(1,missing.ams[0].second.size); assertNull(missing.ams[0].second[0])
        assertEquals(setOf(254),missing.loadedTrayIds())
    }

    @Test fun bedPlateAliasesAndAmsSlotBoundsStayCompatibleWithPrinterCommands() {
        for ((labels,code) in listOf(
            listOf("Textured PEI Plate") to "textured_plate",
            listOf("Smooth PEI Plate / High Temp Plate","Smooth PEI Plate","High Temp Plate") to "hot_plate",
            listOf("Cool Plate SuperTack") to "supertack_plate",listOf("Engineering Plate") to "eng_plate",
            listOf("Cool Plate") to "cool_plate",listOf("Unknown") to "auto"
        )) for(label in labels) assertEquals(code,buildPlateCode(label))
        for ((aliases,label) in listOf(
            listOf("textured pei plate","textured_plate") to "Textured PEI Plate",
            listOf("high temp plate","hot plate","hot_plate") to "Smooth PEI Plate / High Temp Plate",
            listOf("supertack plate","supertack_plate") to "Cool Plate SuperTack",
            listOf("engineering plate","eng_plate","engineering_plate") to "Engineering Plate",
            listOf("cool plate","cool_plate") to "Cool Plate"
        )) for(alias in aliases) assertEquals(label,buildPlateLabel(alias.uppercase()))
        assertNull(buildPlateLabel(null)); assertNull(buildPlateLabel("unknown"))
        val standard=AmsInfo("ams",2,"AMS",emptyMap(),emptyMap(),3)
        val ht=AmsInfo("ht",2,"N3S",emptyMap(),emptyMap(),1)
        assertEquals(12,standard.trayId(0)); assertEquals(128,ht.amsId())
        for(a in listOf(standard,ht)) {
            assertFailsWith<IllegalArgumentException> { a.trayId(-1) }
            assertFailsWith<IllegalArgumentException> { a.trayId(if(a.isSingleSlot()) 1 else 4) }
            assertNull(a.slotForTrayId(-1))
        }
        for(id in listOf(-1,0,103,104,127,128,135,136,254)) assertEquals(id in 0..103 || id in 128..135,isAmsTrayId(id))
    }

    private class Backend : HaBackend {
        override val base = "https://home.example"
        var response = "{}"
        var result: JsonElement? = null
        var fail = false
        val posts = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun get(path: String): String { if (fail) throw ApiException("Unavailable"); return response }
        override suspend fun post(path: String, body: String): String { posts += path to json.parseToJsonElement(body).jsonObject; return response }
        override suspend fun bytes(path: String) = byteArrayOf()
        override suspend fun ws(commands: List<JsonObject>) = commands.map { result }
        override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) = error("No subscription expected")
    }

    @Test fun buttonAndPrintRequestsRespectBackendCapabilities() = runTest {
        val b=Backend(); val p=printer(mapOf("button.pause" to e("button.pause")))
        b.press(p,"pause")
        assertEquals("/api/services/button/press",b.posts.last().first)
        assertEquals("button.pause",b.posts.last().second.s("entity_id"))
        val preferred=PreferPrimaryBackend(b,Backend())
        preferred.startPrint(p,"cache/model.3mf",2,listOf(128),false,false,false,"hot_plate")
        assertEquals("hot_plate",b.posts.last().second.s("bed_type"))
        assertEquals("true",b.posts.last().second.s("use_ams")); assertEquals("128",b.posts.last().second.s("ams_mapping"))
        b.startPrint(p,"cache/model.3mf",1,listOf(254),true,true,true)
        assertNull(b.posts.last().second.s("bed_type")); assertEquals("false",b.posts.last().second.s("use_ams"))
        assertNull(b.posts.last().second.s("ams_mapping"))
    }

    @Test fun missingMediaAndCachedFileMetadataStayExplicit() = runTest {
        val b = Backend()
        for (result in listOf(null, JsonNull, obj("{}"))) {
            b.result = result
            assertFailsWith<ApiException> { b.hlsUrl("camera.test") }
        }
        for (url in listOf("/stream", "https://signed.example/stream")) {
            b.result = obj("""{"url":"$url"}""")
            assertEquals(if (url.startsWith("/")) b.base + url else url, b.hlsUrl("camera.test"))
        }
        assertTrue(parseFiles("{}", "files").isEmpty())
        val files = parseFiles("""{"files":[{}, {"filename":"missing-path"},{"filename":"part.gcode","path":"cache/part.gcode","size":"bad"}]}""", "files")
        val file = files.single()
        assertEquals(0L, file.size); assertEquals("", file.modified); assertNull(file.thumbUrl)
        assertEquals("part", file.title); assertEquals("cache/part.gcode", file.printerPath)
        assertEquals(SliceInfo(1, emptyList()), b.sliceInfo(file))
        b.fail = true; assertNull(b.sliceInfo(file)); b.fail = false
        b.response = "not-json"
        assertEquals(file.printerPath, b.ensureOnPrinter("serial", file))
        b.response = """{"status":"use_legacy_path","detail":"/legacy/part.gcode"}"""
        assertEquals("legacy/part.gcode", b.ensureOnPrinter("serial", file))
        assertFailsWith<ApiException> { b.press(printer(), "pause") }
        b.bambu("test", printer())
        assertEquals("ha", b.posts.last().second.s("device_id"))
        assertEquals("$REPO_URL/blob/main/docs/slicer.md", guide("docs/slicer.md"))
    }
}
