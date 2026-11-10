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
}
