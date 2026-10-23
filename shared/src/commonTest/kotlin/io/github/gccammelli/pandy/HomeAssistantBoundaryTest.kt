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
