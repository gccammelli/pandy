package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ZipCoverageTest {
    private val wanted = listOf(Regex("a\\.txt"))
    private fun zip() = storedZip("a.txt" to "hello", "b.txt" to "world")
    private fun ByteArray.set32(at: Int, v: Long) { for (i in 0 until 4) this[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
    private fun ByteArray.set16(at: Int, v: Int) { this[at] = (v and 0xFF).toByte(); this[at + 1] = ((v shr 8) and 0xFF).toByte() }
    private suspend fun read(data: ByteArray, max: Long = 1024) = zipEntry(data.size.toLong(), wanted, max) { o, l -> data.copyOfRange(o.toInt(), (o + l).toInt().coerceAtMost(data.size)) }
    private fun eocd(data: ByteArray) = data.size - 22
    private fun firstCentral(data: ByteArray) = (0 until data.size - 4).first { i -> data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() && data[i + 2] == 1.toByte() && data[i + 3] == 2.toByte() }

    @Test fun anIntactArchiveYieldsTheWantedEntry() = runTest {
        assertEquals("hello", read(zip())!!.second.decodeToString())
        assertNull(zipEntry(10, wanted) { _, _ -> ByteArray(10) }) // too small to hold a directory
    }

    @Test fun damagedDirectoriesAreRefusedRatherThanReadPastTheirBounds() = runTest {
        val d = zip(); val e = eocd(d)
        for (corrupt in listOf<(ByteArray) -> Unit>(
            { it.set32(e + 16, it.size.toLong() + 1) },          // directory starts beyond the file
            { it.set32(e + 12, 0x7FFFFFFFL) },                    // directory claims to be enormous
            { it.set32(e + 12, 9_000_000L) },                     // larger than the 8 MiB limit
            { it.set32(e + 12, it.getInt32(e + 12) + 100L) },     // runs past the end of the file
            { it.set32(e + 12, 40L) },                            // cut short in the middle of an entry
            { it.set32(firstCentral(it) + 42, 0x7FFFFFF0L) },     // local header offset out of range
            { it.set16(it.indexOfLocalHeader() + 26, 60_000) },   // file name longer than the archive
            { it.set16(firstCentral(it) + 10, 12) },              // an unsupported compression method
            { it.set32(firstCentral(it) + 20, 7L) },              // stored size that disagrees with its content
            { it.set32(firstCentral(it) + 24, 0xFFFFFFF0L) },     // content larger than anything allowed
            { it.set32(firstCentral(it) + 20, 0xFFFFFFF0L) },     // stored size beyond what one read can hold
            { it.set32(firstCentral(it) + 20, it.size.toLong() - 10) }, // stored size longer than the bytes that follow it
        )) {
            val copy = zip(); corrupt(copy)
            assertNull(read(copy), "a damaged archive must not yield data")
        }
        val laterDamage = zip(); val second = (firstCentral(laterDamage) + 1 until laterDamage.size - 4).first { i -> laterDamage[i] == 0x50.toByte() && laterDamage[i + 1] == 0x4b.toByte() && laterDamage[i + 2] == 1.toByte() && laterDamage[i + 3] == 2.toByte() }
        laterDamage.set32(second, 0L) // a damaged later entry ends the listing; earlier entries still read
        assertEquals("hello", read(laterDamage)!!.second.decodeToString())
        assertNull(read(zip(), max = 2)) // an entry over the caller's size limit
        assertNull(read(zip().also { it.set16(firstCentral(it) + 10, 8) })) // "deflated" data that is not
    }

    private fun ByteArray.getInt32(at: Int) = (0 until 4).sumOf { (this[at + it].toLong() and 0xFF) shl (8 * it) }
    private fun ByteArray.indexOfLocalHeader() = 0
}
