package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DirectPrinterTest {
    private val config = LanConfig("192.0.2.1", "TEST123", "test-code", "X1C")
    private class Link(val report: String, val failure: String? = null) : LanLink {
        val sent = mutableListOf<JsonObject>()
        var closed = false
        override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
            failure?.let { throw ApiException(it) }
            onConnected(); onMessage("not json"); onMessage("{}"); onMessage(report)
            awaitCancellation()
        }
        override fun publish(payload: String) { sent += json.parseToJsonElement(payload).jsonObject }
        override fun close() { closed = true }
    }
    private class Storage : PrinterStorage {
        val files = mutableMapOf("/cache/test.3mf" to byteArrayOf(1,2,3), "/timelapse/clip.mp4" to byteArrayOf(4,5))
        override suspend fun list(dir: String) = files.filterKeys { it.substringBeforeLast('/').ifEmpty { "/" } == dir }.map { (p,b) -> FileEntry(p.substringAfterLast('/'), b.size.toLong(), "2026-10-01T08:00", false) }
        override suspend fun read(path: String, offset: Long, length: Int): ByteArray = files.getValue(path).let { it.copyOfRange(offset.toInt(), if(length < 0) it.size else (offset+length).toInt().coerceAtMost(it.size)) }
        override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) { files[path] = data; progress(1f) }
        override suspend fun delete(path: String) { files.remove(path) ?: throw PrinterStorageException(550, "No such file") }
    }
    private suspend fun registry(printer: DirectPrinter) = printer.ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") })).single()!!.jsonArray
    private fun report(extra: String = "") = """{"print":{"command":"push_status","gcode_state":"RUNNING","mc_percent":12${extra}},"info":{"module":[{"name":"ota","sw_ver":"1.2.3"}]}}"""
    private suspend fun DirectPrinter.command(service: String, vararg fields: Pair<String, String>) = post("/api/services/$service", buildJsonObject { fields.forEach { (k,v) -> put(k,v) } }.toString())

    @Test fun settingAFilamentAddressesTheTrayLikeBambuStudio() = runBlocking<Unit> {
        val ams=""","ams":{"ams":[{"id":"0","tray":[{"id":"1","tray_type":"PLA"}]},{"id":"128","tray":[{"id":"0","tray_type":"PETG"}]}]},"vt_tray":{"tray_type":"PLA"},"chamber_target_temper":35"""
        val link=Link(report(ams));val printer=DirectPrinter(config,true,{link})
        try {
            val trays=registry(printer).map { it.jsonObject }.filter { it.s("translation_key") in listOf("tray","external_spool") }
            val petg=FILAMENT_PRESETS.first { it.id=="GFG99" }
            // (tray row, AMS unit, slot) as the printer addresses them
            for ((suffix,expected) in listOf("AMS_1_tray_2" to (0 to 1),"AMS_2_tray_1" to (128 to 0),"external_spool" to (255 to 254))) {
                val row=trays.first { it.s("unique_id")!!.endsWith(suffix) }
                printer.command("bambu_lab/set_filament","entity_id" to row.s("entity_id")!!,"filament_id" to petg.id,"color" to "#c12e1f")
                val sent=link.sent.last().o("print")!!
                assertEquals("ams_filament_setting",sent.s("command"))
                assertEquals(expected.first.toString(),sent.s("ams_id")); assertEquals(expected.second.toString(),sent.s("slot_id")); assertEquals(expected.second.toString(),sent.s("tray_id"))
                assertEquals("GFG99",sent.s("tray_info_idx")); assertEquals("PETG",sent.s("tray_type")); assertEquals("GFSG99",sent.s("setting_id"))
                assertEquals("C12E1FFF",sent.s("tray_color")); assertEquals("220",sent.s("nozzle_temp_min")); assertEquals("270",sent.s("nozzle_temp_max"))
            }
            val row=trays.first().s("entity_id")!!
            assertFailsWith<ApiException> { printer.command("bambu_lab/set_filament","entity_id" to row,"filament_id" to "nope","color" to "FFFFFF") }
            assertFailsWith<ApiException> { printer.command("bambu_lab/set_filament","entity_id" to "missing","filament_id" to petg.id,"color" to "FFFFFF") }
        } finally { printer.stop() }
    }

    @Test fun theFileManagerListsAndDeletesOnThePrinter() = runBlocking<Unit> {
        val storage=Storage()
        val printer=DirectPrinter(config,true,{Link(report())},{storage})
        try {
            assertEquals(listOf("test.3mf"),printer.listFiles("/cache").map { it.name })
            printer.deleteFile("/cache/test.3mf")
            assertEquals(emptyList(),printer.listFiles("/cache"))
        } finally { printer.stop() }
    }

    @Test fun cameraRequestsShareTheLiveStreamAndStopReleasesIt() = runBlocking<Unit> {
        val starts=kotlinx.coroutines.flow.MutableStateFlow(0)
        val closed=CompletableDeferred<Unit>()
        val frame=byteArrayOf(1,2,3)
        val printer=DirectPrinter(config.copy(model="P1S"),jpegStream={ received,onFrame ->
            assertEquals(config.serial,received.serial)
            starts.value += 1; onFrame(frame)
            try { awaitCancellation() } finally { closed.complete(Unit) }
        })
        try {
            assertContentEquals(frame,withTimeout(3000) { printer.bytes("/api/camera_proxy/live") })
            assertContentEquals(frame,printer.bytes("/api/camera_proxy/live"))
            assertEquals(1,starts.value)
        } finally { printer.stop() }
        withTimeout(3000) { closed.await() }
    }

    @Test fun cameraFailureIsRetriedAndNeverReturnsAnInventedImage() = runBlocking<Unit> {
        val starts=kotlinx.coroutines.flow.MutableStateFlow(0)
        val printer=DirectPrinter(config.copy(model="P1S"),jpegStream={ _,_ ->
            starts.value += 1; throw ApiException("Camera offline")
        })
        try {
            assertEquals("The camera didn't send a picture",withTimeout(15_000) {
                assertFailsWith<ApiException> { printer.bytes("/api/camera_proxy/live") }.message
            })
            assertTrue(starts.value>=2)
        } finally { printer.stop() }
    }

    @Test fun registryStateAndCameraAreConsistent() = runBlocking<Unit> {
        val link = Link(report())
        val printer = DirectPrinter(config, true, { link })
        try {
            val entities = registry(printer)
            assertTrue(entities.any { it.jsonObject.s("translation_key") == "print_status" })
            val devices = printer.ws(listOf(buildJsonObject { put("type", "config/device_registry/list") })).single()!!.jsonArray
            assertEquals("1.2.3", devices.first().jsonObject.s("sw_version"))
            val camera = printer.ws(listOf(buildJsonObject { put("type", "camera/stream") })).single()!!.jsonObject.s("url")
            assertEquals("rtsps://bblp:test-code@192.0.2.1:322/streaming/live/1#TEST123", camera)
            assertEquals("pushall", link.sent.first().o("pushing")?.s("command"))
            var event: JsonObject? = null
            val stream = launch { printer.stream(buildJsonObject {}) { event = it; cancel() } }
            withTimeout(3000) { stream.join() }
            assertTrue(event!!.o("a")!!.isNotEmpty())
        } finally { printer.stop() }
    }

    @Test fun serviceCommandsProduceExpectedMqttPackets() = runBlocking<Unit> {
        val link = Link(report()); val printer = DirectPrinter(config, true, { link })
        try {
            val entities = registry(printer)
            fun id(key: String) = entities.first { it.jsonObject.s("translation_key") == key }.jsonObject.s("entity_id")!!
            printer.command("light/turn_on"); assertEquals("on", link.sent.last().o("system")?.s("led_mode"))
            printer.command("light/turn_off"); assertEquals("off", link.sent.last().o("system")?.s("led_mode"))
            for ((key,gcode) in listOf("target_nozzle_temperature" to "M104", "target_bed_temperature" to "M140")) {
                printer.command("number/set_value", "entity_id" to id(key), "value" to "55")
                assertEquals("$gcode S55\n", link.sent.last().o("print")?.s("param"))
            }
            for ((mode,n) in listOf("silent" to "1", "standard" to "2", "sport" to "3", "ludicrous" to "4")) {
                printer.command("select/select_option", "option" to mode); assertEquals(n, link.sent.last().o("print")?.s("param"))
            }
            for (button in listOf("pause", "resume", "stop")) {
                printer.command("button/press", "entity_id" to id(button)); assertEquals(button, link.sent.last().o("print")?.s("command"))
            }
            printer.command("fan/set_percentage", "percentage" to "50"); assertEquals("M106 P1 S128\n", link.sent.last().o("print")?.s("param"))
            printer.command("fan/turn_off"); assertEquals("M106 P1 S0\n", link.sent.last().o("print")?.s("param"))
            printer.command("bambu_lab/skip_objects", "objects" to "1,broken,3"); assertEquals("[1,3]", link.sent.last().o("print")!!["obj_list"].toString())
            printer.command("bambu_lab/move_axis", "axis" to "Home"); assertEquals("G28\n", link.sent.last().o("print")?.s("param"))
            printer.command("bambu_lab/move_axis", "axis" to "Z", "distance" to "5"); assertTrue(link.sent.last().o("print")!!.s("param")!!.contains("Z5.0 F900"))
            printer.command("bambu_lab/extrude_retract", "type" to "Retract"); assertTrue(link.sent.last().o("print")!!.s("param")!!.contains("E-10.0"))
            printer.command("bambu_lab/unload_filament"); assertEquals("255", link.sent.last().o("print")?.s("target"))
            printer.command("bambu_lab/print_project_file", "filepath" to "cache/test.3mf", "plate" to "2", "use_ams" to "true", "ams_mapping" to "0,1", "bed_leveling" to "false", "flow_cali" to "true")
            val print = link.sent.last().o("print")!!
            assertEquals("Metadata/plate_2.gcode", print.s("param")); assertEquals("file:///sdcard/cache/test.3mf", print.s("url")); assertEquals("0", print.s("auto_bed_leveling")); assertEquals("1", print.s("extrude_cali_flag"))
            printer.command("bambu_lab/calibration", "option" to "1")
            printer.command("bambu_lab/xcam_control_set", "module_name" to "spaghetti_detector", "enable" to "true")
            printer.command("bambu_lab/print_option", "sound_enable" to "true")
            printer.command("bambu_lab/ams_user_setting", "ams_id" to "0", "startup_read_option" to "true")
            printer.command("switch/turn_on")
            printer.command("homeassistant/update_entity"); assertEquals("pushall", link.sent.last().o("pushing")?.s("command"))
            assertFailsWith<ApiException> { printer.command("unsupported/service") }
            assertFailsWith<ApiException> { printer.command("bambu_lab/print_project_file") }
            assertFailsWith<ApiException> { printer.command("bambu_lab/print_option") }
            assertFailsWith<ApiException> { printer.command("bambu_lab/load_filament", "entity_id" to "missing") }
        } finally { printer.stop() }
    }

    @Test fun optionalServiceFieldsAndAdditionalFansUseSafeProtocolDefaults() = runBlocking<Unit> {
        val link=Link(report(",\"chamber_target_temper\":35")); val printer=DirectPrinter(config,true,{link})
        try {
            val entities=registry(printer)
            fun id(key: String)=entities.first { it.jsonObject.s("translation_key")==key }.jsonObject.s("entity_id")!!
            printer.command("number/set_value","entity_id" to id("target_nozzle_temperature"),"value" to "bad")
            assertEquals("M104 S0\n",link.sent.last().o("print")!!.s("param"))
            val count=link.sent.size
            printer.command("number/set_value","entity_id" to "missing")
            printer.command("button/press","entity_id" to "missing")
            assertEquals(count,link.sent.size)
            for ((key,n) in listOf("aux_fan" to 2,"chamber_fan" to 3)) {
                printer.command("fan/turn_on","entity_id" to id(key))
                assertEquals("M106 P$n S255\n",link.sent.last().o("print")!!.s("param"))
            }
            printer.command("bambu_lab/move_axis")
            assertTrue(link.sent.last().o("print")!!.s("param")!!.contains("G1 X0.0 F3000"))
            printer.command("bambu_lab/extrude_retract","type" to "Extrude")
            assertTrue(link.sent.last().o("print")!!.s("param")!!.contains("E10.0"))
            printer.command("bambu_lab/skip_objects")
            assertEquals("[]",link.sent.last().o("print")!!["obj_list"].toString())
            printer.command("bambu_lab/print_project_file","filepath" to "https://files.example/test.3mf")
            val sent=link.sent.last().o("print")!!
            assertEquals("https://files.example/test.3mf",sent.s("url")); assertEquals("Metadata/plate_1.gcode",sent.s("param"))
            assertEquals("1",sent.s("auto_bed_leveling")); assertEquals("0",sent.s("extrude_cali_flag"))
            assertNull(sent["ams_mapping"])
            printer.command("bambu_lab/print_project_file","filepath" to "test.3mf","use_ams" to "true","ams_mapping" to "bad, 1, ,3")
            assertEquals("[1,3]",link.sent.last().o("print")!!["ams_mapping"].toString())
            printer.command("bambu_lab/calibration")
            printer.command("bambu_lab/ams_user_setting")
            assertFailsWith<ApiException> { printer.command("bambu_lab/print_option","auto_recovery" to "unknown") }
            assertEquals(JsonNull,printer.ws(listOf(buildJsonObject { put("type","unknown") })).single())
        } finally { printer.stop() }
    }

    @Test fun aChangedAmsRegistryForcesTheLiveSubscriptionToReconnect() = runBlocking<Unit> {
        val messages=kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val initial=CompletableDeferred<Unit>()
        val link=object : LanLink {
            override suspend fun run(onConnected: ()->Unit,onMessage: (String)->Unit) {
                onConnected(); onMessage(report())
                for(message in messages) onMessage(message)
            }
            override fun publish(payload: String)=Unit
            override fun close()=Unit
        }
        val printer=DirectPrinter(config,true,{link})
        try {
            registry(printer)
            val stream=async { runCatching { printer.stream(buildJsonObject {}) { initial.complete(Unit) } } }
            withTimeout(3000) { initial.await() }
            messages.send(report(",\"ams\":{\"ams\":[{\"id\":0,\"tray\":[{}]}]}"))
            val error=withTimeout(3000) { stream.await() }.exceptionOrNull()
            assertIs<ApiException>(error); assertEquals("The printer's parts changed",error.message)
        } finally { printer.stop(); messages.close() }
    }

    @Test fun storageListsUploadsAndCachesOnlyPrinterFiles() = runBlocking<Unit> {
        val storage = Storage(); val printer = DirectPrinter(config, storageFactory = { storage })
        try {
            assertTrue(printer.get("/api/bambu_lab/print_history").contains("test.3mf"))
            assertTrue(printer.get("/api/bambu_lab/videos").contains("clip.mp4"))
            assertContentEquals(byteArrayOf(1,2,3), printer.bytes("/api/bambu_lab/file_cache/TEST123/prints/cache/test.3mf"))
            var progress = 0f
            printer.upload("new.3mf", byteArrayOf(6,7)) { progress = it }
            assertEquals(1f, progress); assertContentEquals(byteArrayOf(6,7), storage.files["/cache/new.3mf"])
            assertFailsWith<ApiException> { printer.get("/api/unknown") }
            assertFailsWith<ApiException> { printer.bytes("/api/unknown") }
            assertFailsWith<ApiException> { printer.bytes("/api/image_proxy/missing") }
            assertEquals("""{"status":"present"}""", printer.post("/api/bambu_lab/ensure_cache_file", "{}"))
        } finally { printer.stop() }
    }

    @Test fun signedFirmwareBlocksControlAndBadCredentialsFailSetup() = runBlocking<Unit> {
        val link = Link(report(",\"fun\":\"20000000\"")); val printer = DirectPrinter(config, true, { link })
        try {
            registry(printer)
            assertFailsWith<ApiException> { printer.command("button/press") }
            printer.command("homeassistant/update_entity")
        } finally { printer.stop() }
        val failed = DirectPrinter(config, true, { Link("{}", "Bad access code") })
        try { assertFailsWith<ApiException> { registry(failed) } } finally { failed.stop() }
    }
    @Test fun missingTimelapseDirectoryIsEmptyButOtherStorageFailuresRemainVisible() = runBlocking<Unit> {
        var code=550
        val storage=object : PrinterStorage {
            override suspend fun list(dir: String): List<FileEntry> = throw PrinterStorageException(code,"Directory unavailable")
            override suspend fun read(path: String,offset: Long,length: Int) = error("No read expected")
            override suspend fun write(path: String,data: ByteArray,progress: (Float)->Unit) = error("No write expected")
        }
        val printer=DirectPrinter(config,storageFactory={storage})
        try {
            assertEquals("""{"videos":[]}""",printer.get("/api/bambu_lab/videos"))
            code=451
            assertEquals(451,assertFailsWith<PrinterStorageException> { printer.get("/api/bambu_lab/videos") }.code)
        } finally { printer.stop() }
    }

    @Test fun thumbnailMissIsCachedAndJpegCameraDoesNotPretendToOfferRtsp() = runBlocking<Unit> {
        val storage=Storage()
        val link=Link(report())
        val printer=DirectPrinter(config.copy(model="P1S"),true,{link},{storage})
        try {
            registry(printer)
            assertFailsWith<ApiException> { printer.ws(listOf(buildJsonObject { put("type","camera/stream") })) }
            for(attempt in 1..2) assertEquals("No picture in that file",assertFailsWith<ApiException> {
                printer.bytes("/api/bambu_lab/file_cache/TEST123/prints/cache/test.3mf.png")
            }.message)
        } finally { printer.stop() }
    }

    @Test fun physicalAmsSlotsUseAddressedProtocolCommands() = runBlocking<Unit> {
        val ams=""","ams":{"ams":[{"id":"0","tray":[{"id":"1","tray_type":"PLA"}]},{"id":"128","tray":[{"id":"0","tray_type":"PETG"}]}]},"vt_tray":{"tray_type":"PLA"},"chamber_target_temper":35"""
        val link=Link(report(ams));val printer=DirectPrinter(config,true,{link})
        try {
            val entities=registry(printer)
            val trays=entities.map { it.jsonObject }.filter { it.s("translation_key") in listOf("tray","external_spool") }
            for ((suffix,target) in listOf("AMS_1_tray_2" to 1,"AMS_2_tray_1" to 128,"external_spool" to 254)) {
                val row=trays.first { it.s("unique_id")!!.endsWith(suffix) }
                printer.command("bambu_lab/load_filament","entity_id" to row.s("entity_id")!!)
                assertEquals(target.toString(),link.sent.last().o("print")?.s("target"))
            }
        } finally { printer.stop() }
    }

}
