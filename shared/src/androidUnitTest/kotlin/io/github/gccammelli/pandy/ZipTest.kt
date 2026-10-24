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

    @Test fun rejectsOversizedAndShortEntries() = runTest {
        val z = zip()
        val f: suspend (Long, Int) -> ByteArray = { o, l -> z.copyOfRange(o.toInt(), o.toInt() + l) }
        assertNull(zipEntry(z.size.toLong(), listOf(Regex("Metadata/plate_1\\.png")), 100, f))
        assertNull(zipEntry(z.size.toLong(), listOf(Regex("Metadata/plate_1\\.png"))) { _, _ -> ByteArray(0) })
    }
    private fun storedZip(comment: String = ""): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.setComment(comment)
            val entry=ZipEntry("Metadata/plate_1.png").apply {
                method=ZipEntry.STORED; size=png.size.toLong(); compressedSize=size
                crc=java.util.zip.CRC32().apply { update(png) }.value
            }
            z.putNextEntry(entry); z.write(png); z.closeEntry()
        }
    }.toByteArray()

    private fun ByteArray.signature(value: Int) = (0..size-4).first { i ->
        (0..3).all { j -> this[i+j]==(value ushr (j*8)).toByte() }
    }
    private fun ByteArray.set32(offset: Int, value: Long) {
        repeat(4) { this[offset+it]=(value ushr (it*8)).toByte() }
    }
    private suspend fun readPicture(z: ByteArray, fetch: suspend (Long,Int)->ByteArray = { o,l -> z.copyOfRange(o.toInt(),o.toInt()+l) }) =
        zipEntry(z.size.toLong(),listOf(Regex("Metadata/plate_1\\.png")),fetch)

    @Test fun storedPicturesAndDistantCentralDirectoriesUseBoundedRangeReads() = runTest {
        val z=storedZip("x".repeat(65535))
        val ranges=mutableListOf<Pair<Long,Int>>()
        val result=readPicture(z) { o,l -> ranges+=o to l; z.copyOfRange(o.toInt(),o.toInt()+l) }
        assertContentEquals(png,result!!.second)
        assertEquals(4,ranges.size) // Tail, separate directory, local header and stored payload.
        assertEquals(65557,ranges.first().second)
        assertEquals(png.size,ranges.last().second)
    }

    @Test fun corruptAndUnsupportedStoredEntriesAreRejected() = runTest {
        val original=storedZip()
        val cd=original.signature(0x02014b50)
        val unsupported=original.copyOf().apply { this[cd+10]=99 }
        assertNull(readPicture(unsupported))
        val wrongSize=original.copyOf().apply { set32(cd+24,png.size+1L) }
        assertNull(readPicture(wrongSize))
        val outside=original.copyOf().apply { set32(cd+42,size-10L) }
        assertNull(readPicture(outside))
        val invalidName=original.copyOf().apply { this[cd+28]=0xff.toByte(); this[cd+29]=0xff.toByte() }
        assertNull(readPicture(invalidName))
    }

    @Test fun invalidZipBoundsAndShortRangeResponsesDoNotEscapeTheReader() = runTest {
        val z=storedZip(); val e=z.signature(0x06054b50)
        for (changed in listOf(
            z.copyOf().apply { set32(e+12,8L*1024*1024+1) },
            z.copyOf().apply { set32(e+16,size+1L) },
            z.copyOf().apply { set32(e+12,size.toLong()) },
            ByteArray(30)
        )) assertNull(readPicture(changed))
        assertNull(zipEntry(21,emptyList()) { _,_ -> error("Must reject before fetching") })
        assertNull(zipEntry(z.size.toLong(),emptyList(),-1) { _,_ -> error("Must reject before fetching") })
        for (shortCall in 2..3) {
            var calls=0
            assertNull(readPicture(z) { o,l ->
                calls++; z.copyOfRange(o.toInt(),o.toInt()+l-if(calls==shortCall) 1 else 0)
            })
        }
        val distant=storedZip("x".repeat(65535)); var calls=0
        assertNull(readPicture(distant) { o,l -> calls++; if(calls==2) byteArrayOf() else distant.copyOfRange(o.toInt(),o.toInt()+l) })
    }

    @Test fun truncatedAndDamagedDeflatePayloadsAreRejected() = runTest {
        val z=zip()
        assertNull(readPicture(z) { o,l -> if(l==30) z.copyOfRange(o.toInt(),o.toInt()+l).apply { this[26]=0xff.toByte(); this[27]=0xff.toByte() }
            else z.copyOfRange(o.toInt(),o.toInt()+l) })
        assertNull(readPicture(z) { o,l -> if(l!=30 && l!=65557) ByteArray(l) else z.copyOfRange(o.toInt(),o.toInt()+l) })
    }

    @Test fun sliceObjectMetadataUsesPlateIdsAndSkipsMalformedObjects() {
        val xml="""<config><plate><metadata key="index" value="1"/><object identify_id="bad" name="skip"/><object name="skip"/><object identify_id="7"/></plate>
            <plate><metadata key="index" value="2"/><object identify_id="8" name="Second plate"/></plate></config>"""
        assertEquals(mapOf(7 to ""),sliceObjects(xml,1))
        assertEquals(mapOf(8 to "Second plate"),sliceObjects(xml,2))
        assertEquals(mapOf(7 to ""),sliceObjects(xml,99))
        assertEquals(emptyMap(),sliceObjects("<config/>",1))
    }

}
