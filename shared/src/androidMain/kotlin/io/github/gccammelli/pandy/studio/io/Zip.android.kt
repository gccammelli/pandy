// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual object Zip {
    /** Largest total size of a ZIP's contents the app unpacks into memory. */
    const val MAX_EXPANDED = 400L shl 20

    actual fun read(bytes: ByteArray): Map<String, ByteArray> {
        val validSignature = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) || (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) || (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte()))
        require(validSignature) { "Malformed ZIP archive: missing ZIP signature" }
        val entries=LinkedHashMap<String,ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                var total = 0L
                while (true) {
                    val entry = input.nextEntry ?: break
                    // A small archive can expand to gigabytes (a ZIP bomb): stop well before the phone runs out of memory.
                    val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        require(total <= MAX_EXPANDED) { "The file expands to more than ${MAX_EXPANDED shr 20} MB" }
                        out.write(buf, 0, n)
                    }
                    entries[entry.name] = out.toByteArray(); input.closeEntry()
                }
            }
        } catch(e: IllegalArgumentException) { throw e } catch(e: Exception) { throw IllegalArgumentException("Malformed ZIP archive: ${e.message ?: "invalid data"}",e) }
        return entries
    }

    actual fun write(entries: List<Pair<String, ByteArray>>, deflate: Boolean): ByteArray {
        val output=ByteArrayOutputStream()
        try {
            ZipOutputStream(output).use { zip ->
                for((name,data) in entries) {
                    require(name.isNotEmpty() && !name.startsWith("/")) { "ZIP entry name must be a non-empty relative path" }
                    val entry=ZipEntry(name);entry.time=315532800000L
                    if(!deflate) { val crc=CRC32();crc.update(data);entry.method=ZipEntry.STORED;entry.size=data.size.toLong();entry.compressedSize=data.size.toLong();entry.crc=crc.value }
                    zip.putNextEntry(entry);zip.write(data);zip.closeEntry()
                }
            }
        } catch(e: IllegalArgumentException) { throw e } catch(e: Exception) { throw IllegalArgumentException("Could not write ZIP archive: ${e.message ?: "invalid data"}",e) }
        return output.toByteArray()
    }
}
