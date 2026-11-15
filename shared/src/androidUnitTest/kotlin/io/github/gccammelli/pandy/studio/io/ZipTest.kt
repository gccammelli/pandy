// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipTest {
    @Test fun roundTripsCompressedAndStoredEntries() {
        val entries=listOf("one.txt" to "hello".encodeToByteArray(),"folder/two.bin" to byteArrayOf(0,1,-1))
        for(deflate in listOf(true,false)) { val archive=Zip.write(entries,deflate);assertContentEquals(archive,Zip.write(entries,deflate));val read=Zip.read(archive);assertEquals(entries.map { it.first }.toSet(),read.keys);for((name,bytes) in entries)assertContentEquals(bytes,read[name]) }
    }
    @Test fun invalidArchiveHasClearFailure() { assertFailsWith<IllegalArgumentException>{Zip.read(byteArrayOf(1,2,3))} }

    /** An archive laid out as the slicers write it: every entry's sizes in a data descriptor after its data. */
    private fun slicerStyleZip(entries: List<Triple<String, ByteArray, Boolean>>): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val central = java.io.ByteArrayOutputStream()
        fun le(o: java.io.OutputStream, v: Long, n: Int) { for (k in 0 until n) o.write(((v shr (8 * k)) and 0xff).toInt()) }
        for ((name, data, deflate) in entries) {
            val packed = if (!deflate) data else java.io.ByteArrayOutputStream().also { b ->
                java.util.zip.DeflaterOutputStream(b, java.util.zip.Deflater(9, true)).use { it.write(data) } }.toByteArray()
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val offset = out.size().toLong(); val n = name.encodeToByteArray(); val method = if (deflate) 8L else 0L
            le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 8, 2); le(out, method, 2); le(out, 0, 4); le(out, 0, 4); le(out, 0, 4); le(out, 0, 4)
            le(out, n.size.toLong(), 2); le(out, 0, 2); out.write(n); out.write(packed)
            le(out, 0x08074b50, 4); le(out, crc, 4); le(out, packed.size.toLong(), 4); le(out, data.size.toLong(), 4)
            le(central, 0x02014b50, 4); le(central, 20, 2); le(central, 20, 2); le(central, 8, 2); le(central, method, 2); le(central, 0, 4)
            le(central, crc, 4); le(central, packed.size.toLong(), 4); le(central, data.size.toLong(), 4); le(central, n.size.toLong(), 2)
            le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 4); le(central, offset, 4); central.write(n)
        }
        val dirOffset = out.size().toLong(); out.write(central.toByteArray())
        le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, entries.size.toLong(), 2); le(out, entries.size.toLong(), 2)
        le(out, central.size().toLong(), 4); le(out, dirOffset, 4); le(out, 0, 2)
        return out.toByteArray()
    }

    @Test fun readsEntriesStoredWithADataDescriptorAsTheSlicersWriteThem() {
        val gcode = ("G1 X1 Y1\n".repeat(20_000)).encodeToByteArray()
        val png = ByteArray(3000) { (it * 7).toByte() }
        val bytes = slicerStyleZip(listOf(Triple("Metadata/plate_1.png", png, false), Triple("Metadata/plate_1.gcode", gcode, true)))
        val entries = Zip.read(bytes)
        assertEquals(listOf("Metadata/plate_1.png", "Metadata/plate_1.gcode"), entries.keys.toList())
        assertContentEquals(png, entries["Metadata/plate_1.png"]); assertContentEquals(gcode, entries["Metadata/plate_1.gcode"])
        // A damaged entry is reported, not silently dropped.
        val damaged = bytes.copyOf().also { it[60] = (it[60] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { Zip.read(damaged) }
    }
}
