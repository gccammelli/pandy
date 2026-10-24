package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DirectPrinterCoverageTest {
    private val config = LanConfig("192.0.2.1", "TEST123", "test-code", "X1C")

    private class Files(val files: MutableMap<String, ByteArray>, val modified: Map<String, String> = emptyMap()) : PrinterStorage {
        override suspend fun list(dir: String) = files.filterKeys { it.substringBeforeLast('/').ifEmpty { "/" } == dir }
            .map { (p, b) -> FileEntry(p.substringAfterLast('/'), b.size.toLong(), modified[p] ?: "2026-10-01T08:00", false) }
        override suspend fun read(path: String, offset: Long, length: Int): ByteArray = files.getValue(path).let { it.copyOfRange(offset.toInt(), if (length < 0) it.size else (offset + length).toInt().coerceAtMost(it.size)) }
        override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) { files[path] = data }
    }

    private class Link(val report: String) : LanLink {
        override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) { onConnected(); onMessage(report); awaitCancellation() }
        override fun publish(payload: String) = Unit
        override fun close() = Unit
    }

    private fun report(task: String, gcode: String) = """{"print":{"command":"push_status","gcode_state":"RUNNING","mc_percent":12,"subtask_name":"$task","gcode_file":"$gcode"}}"""
    private val project = storedZip("Metadata/plate_2.png" to "cover2", "Metadata/plate_1.png" to "cover1", "Metadata/pick_2.png" to "pick2",
        "Metadata/slice_info.config" to """<config><plate><metadata key="index" value="2"/><object identify_id="7" name="Cube"/><object identify_id="8" name="Cone"/></plate></config>""")

    /** Waits until the printer reports a job with [objects] printable objects (0 = none), via its live stream. */
    private suspend fun jobObjects(files: Map<String, ByteArray>, task: String, gcode: String, modified: Map<String, String> = emptyMap(), expected: Int): Boolean {
        val printer = DirectPrinter(config, true, { Link(report(task, gcode)) }, { Files(files.toMutableMap(), modified) })
        try {
            printer.ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") }))
            var found = false
            val stream = CoroutineScope(Dispatchers.Default).launch {
                printer.stream(buildJsonObject {}) { ev ->
                    ev.o("a")?.entries?.firstOrNull { it.key.endsWith("printable_objects") }?.let { e ->
                        if ((e.value as JsonObject).s("s") == expected.toString()) found = true
                    }
                }
            }
            withTimeout(5000) { while (!found) delay(25) }
            stream.cancel()
            return found
        } finally { printer.stop() }
    }

    @Test fun theCurrentJobsFileIsFoundByTaskNameGcodeNameOrAsTheNewest3mf() = runBlocking<Unit> {
        assertTrue(jobObjects(mapOf("/cache/Panda.3mf" to project), "Panda", "plate_2.gcode", expected = 2))
        assertTrue(jobObjects(mapOf("/cache/Panda.gcode.3mf" to project), "Panda", "plate_2.gcode", expected = 2))
        assertTrue(jobObjects(mapOf("/Panda.3mf" to project), "Panda.3mf", "plate_2.gcode", expected = 2))
        assertTrue(jobObjects(mapOf("/model.gcode.3mf" to project), "", "/data/Metadata/model.gcode", expected = 2))
        assertTrue(jobObjects(mapOf("/cache/job.3mf" to project), "", "job.3mf", expected = 2))
        assertTrue(jobObjects(mapOf("/old.3mf" to storedZip("Metadata/plate_1.png" to "x"), "/new.3mf" to project),
            "", "plate_2.gcode", modified = mapOf("/old.3mf" to "2026-09-01T08:00", "/new.3mf" to "2026-10-02T08:00"), expected = 2))
        assertTrue(jobObjects(mapOf("/cache/Missing.3mf" to byteArrayOf(1, 2)), "Other", "plate_1.gcode", expected = 0))
        assertTrue(jobObjects(mapOf("/cache/Panda.3mf" to storedZip("Metadata/plate_1.png" to "only")), "Panda", "x.gcode", expected = 0))
    }

    @Test fun cachedFilesComeFromTheirPlateSliceInfoOrAreReadRawAndAreRememberedWhenSmall() = runBlocking<Unit> {
        val files = mutableMapOf("/cache/a.3mf" to project, "/cache/b.3mf" to storedZip("Metadata/readme.txt" to "none"), "/cache/c.3mf" to storedZip("Metadata/other.png" to "any"),
            "/timelapse/clip.mp4" to byteArrayOf(4, 5, 6))
        val printer = DirectPrinter(config, storageFactory = { Files(files) })
        try {
            val base = "/api/bambu_lab/file_cache/TEST123"
            assertEquals("cover1", printer.bytes("$base/prints/cache/a.3mf.png").decodeToString())
            files["/cache/a.3mf"] = byteArrayOf() // the first answer is remembered
            assertEquals("cover1", printer.bytes("$base/prints/cache/a.3mf.png").decodeToString())
            assertEquals("any", printer.bytes("$base/prints/cache/c.3mf.png").decodeToString())
            assertFailsWith<ApiException> { printer.bytes("$base/prints/cache/b.3mf.png") }
            assertContentEquals(byteArrayOf(4, 5, 6), printer.bytes("$base/timelapse/clip.mp4"))
            files["/cache/a.3mf"] = project
            assertTrue(printer.bytes("$base/prints/cache/a.3mf.slice_info.config").decodeToString().contains("Cube"))
            assertTrue(printer.bytes("$base/prints/cache/a.slice_info.config").decodeToString().contains("Cone"))
            assertEquals("No slice info", assertFailsWith<ApiException> { printer.bytes("$base/prints/cache/b.3mf.slice_info.config") }.message)
            assertEquals("Not on the printer any more", assertFailsWith<ApiException> { printer.bytes("$base/prints/cache/gone.3mf.png") }.message)
        } finally { printer.stop() }
    }

    @Test fun aDroppedConnectionIsRetriedStrayMessagesAreIgnoredAndCommandsNeedALink() = runBlocking<Unit> {
        var attempts = 0
        val delivered = CompletableDeferred<Unit>()
        val factory: (LanConfig) -> LanLink = {
            attempts++
            val attempt = attempts
            object : LanLink {
                override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
                    if (attempt == 1) throw ApiException("Printer unreachable")
                    onConnected()
                    onMessage("""{"system":{"command":"unrelated"}}""")             // neither print nor info: ignored
                    onMessage("""{"print":{"command":"gcode_line","gcode_state":"IDLE"}}""") // a print message that is not a status push
                    onMessage(report("Panda", "plate_1.gcode"))
                    delivered.complete(Unit)
                    awaitCancellation()
                }
                override fun publish(payload: String) = Unit
                override fun close() = Unit
            }
        }
        val printer = DirectPrinter(config, false, factory, { Files(mutableMapOf()) })
        try {
            assertEquals("Not connected to the printer", assertFailsWith<ApiException> { printer.post("/api/services/light/turn_on", "{}") }.message)
            printer.ws(listOf(buildJsonObject { put("type", "config/device_registry/list") }))
            printer.ws(listOf(buildJsonObject { put("type", "unknown/type") })) // already running: not started a second time
            withTimeout(10_000) { delivered.await() }
            assertTrue(attempts >= 2)
        } finally { printer.stop() }
    }

    @Test fun aJobWithoutAPickMapHasNoObjectPicture() = runBlocking<Unit> {
        val files = mutableMapOf("/cache/Panda.3mf" to storedZip("Metadata/plate_1.png" to "cover"))
        val printer = DirectPrinter(config, true, { Link(report("Panda", "plate_1.gcode")) }, { Files(files) })
        try {
            printer.ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") }))
            var covered = false
            val stream = CoroutineScope(Dispatchers.Default).launch {
                printer.stream(buildJsonObject {}) { ev -> if (ev.o("a")?.keys?.any { it.endsWith("cover_image") } == true) covered = true }
            }
            withTimeout(5000) { while (!covered) delay(25) }
            stream.cancel()
            assertEquals("No job picture", assertFailsWith<ApiException> { printer.bytes("/api/image_proxy/image.pandy_test123_pick_image?v=k") }.message)
            assertEquals("cover", printer.bytes("/api/image_proxy/image.pandy_test123_cover_image?v=k").decodeToString())
        } finally { printer.stop() }
    }

    @Test fun aDirectPrinterThatCannotBeReachedIsStoppedAndHomeAssistantTakesOver() = runBlocking<Unit> {
        var closed = 0
        val unreachable = DirectPrinter(config, true, { object : LanLink {
            override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) { throw ApiException("Bad access code") }
            override fun publish(payload: String) = Unit
            override fun close() { closed++ }
        } })
        val remote = FakeHomeAssistant()
        val both = PreferPrimaryBackend(unreachable, remote)
        assertTrue(both.usingPrimary)
        assertEquals(1, both.printers().size) // the direct attempt fails; the remote registry answers
        assertFalse(both.usingPrimary)
        assertTrue(closed >= 1)
        assertEquals(remote.base, both.base)
        both.printers() // later reads go straight to the fallback
    }
}
