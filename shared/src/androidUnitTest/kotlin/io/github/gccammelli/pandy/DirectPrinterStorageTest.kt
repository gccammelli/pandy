package io.github.gccammelli.pandy

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DirectPrinterStorageTest {
    private fun archive(): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        for ((name,bytes) in mapOf("Metadata/plate_1.png" to byteArrayOf(1,2,3), "Metadata/pick_1.png" to byteArrayOf(4,5),
            "Metadata/slice_info.config" to "<config><plate><metadata key=\"index\" value=\"1\"/><object identify_id=\"7\" name=\"Test part\"/></plate></config>".toByteArray())) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    } }.toByteArray()

    @Test fun printArchiveSuppliesJobPicturesAndCachedMetadata() = runBlocking<Unit> {
        val bytes = archive()
        val reads = mutableListOf<String>()
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String) = if(dir=="/cache") listOf(FileEntry("test.3mf",bytes.size.toLong(),"2026-10-23T08:00",false)) else emptyList()
            override suspend fun read(path: String,offset: Long,length: Int): ByteArray {
                reads += path
                return bytes.copyOfRange(offset.toInt(),if(length<0) bytes.size else (offset+length).toInt().coerceAtMost(bytes.size))
            }
            override suspend fun write(path: String,data: ByteArray,progress: (Float)->Unit) = Unit
        }
        val link = object : LanLink {
            override suspend fun run(onConnected: () -> Unit,onMessage: (String)->Unit) {
                onConnected()
                onMessage("""{"print":{"command":"push_status","subtask_name":"test","gcode_file":"Metadata/plate_1.gcode","gcode_state":"RUNNING"}}""")
                awaitCancellation()
            }
            override fun publish(payload: String) = Unit
            override fun close() = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1","TEST","code","X1C"),true,{link},{storage})
        try {
            printer.ws(listOf(buildJsonObject { put("type","config/entity_registry/list") }))
            withTimeout(5000) {
                while(true) {
                    val image = runCatching { printer.bytes("/api/image_proxy/job") }.getOrNull()
                    if(image != null) { assertContentEquals(byteArrayOf(1,2,3),image); break }
                    delay(10)
                }
            }
            assertContentEquals(byteArrayOf(4,5),printer.bytes("/api/image_proxy/pick_image"))
            assertContentEquals(byteArrayOf(1,2,3),printer.bytes("/api/bambu_lab/file_cache/TEST/prints/cache/test.3mf.png"))
            val before = reads.size
            printer.bytes("/api/bambu_lab/file_cache/TEST/prints/cache/test.3mf.png")
            assertEquals(before,reads.size)
            assertTrue(printer.get("/api/bambu_lab/file_cache/TEST/prints/cache/test.3mf.slice_info.config").contains("Test part"))
            assertTrue(printer.get("/api/bambu_lab/print_history").contains("test.3mf"))
        } finally { printer.stop() }
    }

    @Test fun jobPicturesFindRootCacheAndNewestArchivesAcrossFirmwareFileNames() = runBlocking<Unit> {
        val bytes=archive()
        val cases=listOf(
            Triple("test.3mf","","/test.3mf"),
            Triple("test","Metadata/plate_1.gcode","/cache/test.gcode.3mf"),
            Triple("","/sdcard/custom.3mf","/cache/custom.3mf"),
            Triple("","Metadata/plate_1.gcode","/cache/newest.3mf")
        )
        for((task,gcode,expected) in cases) {
            val reads=java.util.concurrent.ConcurrentLinkedQueue<String>()
            val paths=listOf(expected,"/old.3mf")
            val storage=object : PrinterStorage {
                override suspend fun list(dir: String)=paths.filter { it.substringBeforeLast('/').ifEmpty { "/" }==dir }.map { path ->
                    FileEntry(path.substringAfterLast('/'),bytes.size.toLong(),if(path==expected) "2026-10-23T08:00" else "2025-10-23T08:00",false)
                }
                override suspend fun read(path: String,offset: Long,length: Int): ByteArray {
                    reads+=path
                    check(path in paths)
                    return bytes.copyOfRange(offset.toInt(),if(length<0) bytes.size else (offset+length).toInt())
                }
                override suspend fun write(path: String,data: ByteArray,progress: (Float)->Unit)=Unit
            }
            val link=object : LanLink {
                override suspend fun run(onConnected: ()->Unit,onMessage: (String)->Unit) {
                    onConnected()
                    onMessage(buildJsonObject { putJsonObject("print") {
                        put("command","push_status"); put("subtask_name",task); put("gcode_file",gcode)
                    } }.toString())
                    awaitCancellation()
                }
                override fun publish(payload: String)=Unit
                override fun close()=Unit
            }
            val printer=DirectPrinter(LanConfig("192.0.2.1","TEST","code","X1C"),true,{link},{storage})
            try {
                printer.printers()
                withTimeout(3000) {
                    while(runCatching { printer.bytes("/api/image_proxy/job") }.getOrNull()==null) delay(10)
                }
                assertTrue(reads.isNotEmpty()); assertTrue(reads.all { it==expected },"$task / $gcode: $reads")
                val cachePath="/api/bambu_lab/file_cache/TEST/prints"+expected
                assertTrue(printer.get("$cachePath.slice_info.config").contains("Test part"))
            } finally { printer.stop() }
        }
    }

    @Test fun aJobWithoutAnArchiveIsLookedForThreeTimesThenLeftUntilTheJobChanges() = runBlocking<Unit> {
        val lists = java.util.concurrent.atomic.AtomicInteger()
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String): List<FileEntry> { lists.incrementAndGet(); return emptyList() }
            override suspend fun read(path: String, offset: Long, length: Int): ByteArray = error("nothing to read")
            override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = Unit
        }
        var task = "xyzCalibration_cube"
        val link = object : LanLink {
            override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
                onConnected()
                while (true) { // the printer reports about once a second
                    onMessage(buildJsonObject { putJsonObject("print") { put("command", "push_status"); put("subtask_name", task); put("gcode_file", "/sdcard/$task.gcode") } }.toString())
                    delay(5)
                }
            }
            override fun publish(payload: String) = Unit
            override fun close() = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST", "code", "X1C"), true, { link }, { storage }, jobRetryMs = 20)
        try {
            printer.printers()
            withTimeout(5000) { while (lists.get() < JOB_LOOKUPS * 2) delay(5) } // each look lists /cache and /
            delay(300) // fifteen retry intervals: no more looks
            assertEquals(JOB_LOOKUPS * 2, lists.get())
            task = "next_job"
            withTimeout(5000) { while (lists.get() <= JOB_LOOKUPS * 2) delay(5) }
        } finally { printer.stop() }
    }

    @Test fun aPrinterThatCouldNotBeReachedAtFirstIsUsedOnceItAnswers() = runBlocking<Unit> {
        // The first attempt fails (the printer busy or out of connection slots); the next one gets through. The app
        // must not stay "offline" until it is restarted.
        var attempts = 0
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST", "code", "X1C"), true, {
            object : LanLink {
                override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
                    if (++attempts == 1) throw java.io.IOException("Connection refused")
                    onConnected()
                    onMessage("""{"print":{"command":"push_status","gcode_state":"IDLE"}}""")
                    awaitCancellation()
                }
                override fun publish(payload: String) = Unit
                override fun close() = Unit
            }
        }, { error("no storage") })
        try {
            assertFailsWith<ApiException> { printer.printers() }
            withTimeout(10_000) { while (runCatching { printer.printers() }.isFailure) delay(200) }
            assertEquals("TEST", printer.printers().single().serial)
            assertTrue(attempts >= 2)
        } finally { printer.stop() }
    }

    @Test fun checkJobToleratesNonPrintMessagesAndMissingFtpsFolders() = runBlocking<Unit> {
        val archive = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("Metadata/plate_1.png")); z.write(byteArrayOf(7, 8, 9)); z.closeEntry()
            }
        }.toByteArray()
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String): List<FileEntry> {
                if (dir == "/cache") throw PrinterStorageException(550, "No such directory")
                return listOf(FileEntry("MY_MODEL.3MF", archive.size.toLong(), "2026-11-17 01:02", false))
            }
            override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
                val end = if (length < 0) archive.size else minOf(archive.size, offset.toInt() + length)
                return archive.copyOfRange(offset.toInt(), end)
            }
            override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = Unit
        }
        val link = object : LanLink {
            override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
                onConnected()
                onMessage("""{"info":{"command":"get_version"}}""")
                onMessage("""{"print":{"command":"push_status","subtask_name":"my model.3mf","gcode_file":"/data/other.3mf","gcode_state":"RUNNING"}}""")
                awaitCancellation()
            }
            override fun publish(payload: String) = Unit
            override fun close() = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST", "code", "X1C"), true, { link }, { storage })
        try {
            printer.printers()
            withTimeout(3000) { while (runCatching { printer.bytes("/api/image_proxy/job") }.getOrNull() == null) delay(10) }
            assertContentEquals(byteArrayOf(7, 8, 9), printer.bytes("/api/image_proxy/job"))
        } finally { printer.stop() }
    }

    private fun runningLink(task: String) = object : LanLink {
        override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
            onConnected()
            onMessage("""{"print":{"command":"push_status","subtask_name":"$task","gcode_state":"RUNNING"}}""")
            awaitCancellation()
        }
        override fun publish(payload: String) = Unit
        override fun close() = Unit
    }

    private fun zipOf(marker: Byte) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { z -> z.putNextEntry(ZipEntry("Metadata/plate_1.png")); z.write(byteArrayOf(marker)); z.closeEntry() }
    }.toByteArray()

    @Test fun jobFileKeepsTheExactNameWhenSimilarOnesExist() = runBlocking<Unit> {
        // "my_model.3mf" and "My Model.3mf" are other prints: only the file named like the job may supply its cover.
        val files = mapOf("/cache/My Model.3MF" to zipOf(1), "/cache/my model.3mf" to zipOf(2), "/cache/my_model.3mf" to zipOf(3))
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String) = files.filterKeys { it.substringBeforeLast('/') == dir }
                .map { (p, b) -> FileEntry(p.substringAfterLast('/'), b.size.toLong(), "2026-11-17 01:02", false) }
            override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
                val b = files.getValue(path); return b.copyOfRange(offset.toInt(), if (length < 0) b.size else minOf(b.size, offset.toInt() + length))
            }
            override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST", "code", "X1C"), true, { runningLink("my model") }, { storage })
        try {
            printer.printers()
            withTimeout(3000) { while (runCatching { printer.bytes("/api/image_proxy/job") }.getOrNull() == null) delay(10) }
            assertContentEquals(byteArrayOf(2), printer.bytes("/api/image_proxy/job"))
        } finally { printer.stop() }
    }

    @Test fun aFailingListingIsNotReadAsAnEmptyPrinter() = runBlocking<Unit> {
        // A wrong access code or an FTPS outage must surface, not look like "no prints" and be remembered for 30 s.
        var down = true
        val bytes = zipOf(9)
        val storage = object : PrinterStorage {
            override suspend fun list(dir: String): List<FileEntry> {
                if (down) throw PrinterStorageException(530, "Login incorrect")
                return if (dir == "/cache") emptyList() else listOf(FileEntry("a.3mf", bytes.size.toLong(), "2026-11-17 01:02", false))
            }
            override suspend fun read(path: String, offset: Long, length: Int) = bytes
            override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) = Unit
        }
        val printer = DirectPrinter(LanConfig("192.0.2.1", "TEST", "code", "X1C"), true, { runningLink("") }, { storage })
        try {
            printer.printers()
            assertFails { printer.get("/api/bambu_lab/print_history") }
            down = false
            assertTrue(printer.get("/api/bambu_lab/print_history").contains("a.3mf")) // the failure was not cached
        } finally { printer.stop() }
    }
}
