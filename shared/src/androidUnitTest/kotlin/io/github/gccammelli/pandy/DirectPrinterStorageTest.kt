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
}
