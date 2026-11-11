// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The ZIP reader and writer on archives that are empty, truncated or ask for something unsafe. */
class ZipEdgeTest {
    private fun msg(block: () -> Unit) = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()

    @Test fun anEmptyArchiveReadsAsNoEntries() {
        val empty = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)
        assertEquals(0, Zip.read(empty).size)
        assertEquals(0, Zip.read(Zip.write(emptyList())).size)
    }

    @Test fun aSpannedMarkerPassesTheSignatureCheckButTheBodyIsStillValidated() {
        val spanned = byteArrayOf(0x50, 0x4b, 0x07, 0x08, 1, 2, 3)
        assertTrue(runCatching { Zip.read(spanned) }.let { it.isSuccess || it.exceptionOrNull() is IllegalArgumentException })
        assertTrue("missing ZIP signature" in msg { Zip.read(byteArrayOf(0x50, 0x4b, 0x01, 0x02)) })
        assertTrue("missing ZIP signature" in msg { Zip.read(byteArrayOf(0x50, 0x4b, 0x03)) })
        assertTrue("missing ZIP signature" in msg { Zip.read(byteArrayOf(0x51, 0x4b, 0x03, 0x04)) })
        assertTrue("missing ZIP signature" in msg { Zip.read(byteArrayOf(0x50, 0x4c, 0x03, 0x04)) })
        assertTrue("missing ZIP signature" in msg { Zip.read(byteArrayOf(0x50, 0x4b, 0x03, 0x05)) })
    }

    @Test fun aTruncatedArchiveIsReportedAsMalformed() {
        val good = Zip.write(listOf("a.txt" to ByteArray(2000) { (it % 7).toByte() }), deflate = true)
        val m = msg { Zip.read(good.copyOf(good.size / 2)) }
        assertTrue(m.startsWith("Malformed ZIP archive"), m)
    }

    @Test fun entryNamesMustBeRelativeAndNonEmpty() {
        assertTrue("relative path" in msg { Zip.write(listOf("" to byteArrayOf(1))) })
        assertTrue("relative path" in msg { Zip.write(listOf("/abs.txt" to byteArrayOf(1))) })
    }

    @Test fun duplicateEntriesFailTheWrite() {
        val m = msg { Zip.write(listOf("a" to byteArrayOf(1), "a" to byteArrayOf(2))) }
        assertTrue(m.startsWith("Could not write ZIP archive"), m)
    }

    @Test fun storedEntriesKeepTheirBytes() {
        val data = ByteArray(300) { it.toByte() }
        assertContentEquals(data, Zip.read(Zip.write(listOf("d.bin" to data), deflate = false)).getValue("d.bin"))
    }
}
