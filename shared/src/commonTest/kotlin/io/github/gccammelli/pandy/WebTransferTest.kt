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

    @Test fun rejectsOversizeDecodedTransfer() {
        val c = ChunkCollector(2, 3)
        assertNull(c.add(0, byteArrayOf(1, 2)))
        assertNull(c.add(1, byteArrayOf(3, 4)))
        assertTrue(c.rejected)
    }
}
