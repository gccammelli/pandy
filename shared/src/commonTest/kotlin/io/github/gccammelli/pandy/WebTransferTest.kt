package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebTransferTest {
    @Test fun collectsDecodedChunksOnceAndInOrder() {
        val c = ChunkCollector(3, 10)
        assertNull(c.add(2, byteArrayOf(3)))
        assertNull(c.add(0, byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1, 2, 3), c.add(1, byteArrayOf(2)))
    }

    @Test fun repeatedAndOutOfRangeChunksCannotChangeTheAcceptedBytes() {
        val transfer = ChunkCollector(2, 3)
        assertNull(transfer.add(-1, byteArrayOf(9)))
        assertNull(transfer.add(2, byteArrayOf(9)))
        assertNull(transfer.add(0, byteArrayOf(1)))
        assertNull(transfer.add(0, byteArrayOf(8, 8, 8)))
        assertContentEquals(byteArrayOf(1, 2), transfer.add(1, byteArrayOf(2)))
        assertTrue(!transfer.rejected)
        val oversized = ChunkCollector(2, 1)
        assertNull(oversized.add(0, byteArrayOf(1, 2)))
        assertNull(oversized.add(1, byteArrayOf(3)))
        assertTrue(oversized.rejected)
    }

    @Test fun rejectsOversizeDecodedTransfer() {
        val c = ChunkCollector(2, 3)
        assertNull(c.add(0, byteArrayOf(1, 2)))
        assertNull(c.add(1, byteArrayOf(3, 4)))
        assertTrue(c.rejected)
    }
}
