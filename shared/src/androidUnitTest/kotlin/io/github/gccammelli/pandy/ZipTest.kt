package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipTest {
    private val png = ByteArray(5000) { (it * 7).toByte() }
    private val gcode = kotlin.random.Random(1).nextBytes(300_000) // incompressible, so the picture sits far from the end

    private fun zip(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("Metadata/plate_1.gcode")); z.write(gcode); z.closeEntry()
            z.putNextEntry(ZipEntry("Metadata/plate_1.png")); z.write(png); z.closeEntry()
            z.putNextEntry(ZipEntry("Metadata/slice_info.config")); z.write("<config/>".toByteArray()); z.closeEntry()
        }
    }.toByteArray()

    @Test fun readsOneEntryByRanges() = runTest {
        val z = zip()
        var fetched = 0
        val (name, bytes) = zipEntry(z.size.toLong(), listOf(Regex("Metadata/plate_1\\.png"))) { o, l -> fetched += l; z.copyOfRange(o.toInt(), o.toInt() + l) }!!
        assertEquals("Metadata/plate_1.png", name)
        assertContentEquals(png, bytes)
        assertEquals(true, fetched < z.size, "read only part of the file")
    }

    @Test fun preferenceAndMissing() = runTest {
        val z = zip()
        val f: suspend (Long, Int) -> ByteArray = { o, l -> z.copyOfRange(o.toInt(), o.toInt() + l) }
        assertEquals("Metadata/slice_info.config", zipEntry(z.size.toLong(), listOf(Regex("nope"), Regex(".*slice_info.*")), f)!!.first)
        assertNull(zipEntry(z.size.toLong(), listOf(Regex("Metadata/pick_1\\.png")), f))
    }
}
