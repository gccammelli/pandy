// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
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
        readCentral(bytes)?.let { return it }
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

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    /**
     * Reads the entries through the central directory. Unlike a stream reader this also takes entries stored with a data
     * descriptor (sizes after the data), which the slicers write: a stream reader drops or rejects them. Null when the
     * archive has no plain central directory (ZIP64, encrypted), so the stream reader can try.
     */
    private fun readCentral(bytes: ByteArray): Map<String, ByteArray>? {
        var eocd = -1
        var i = bytes.size - 22
        while (i >= maxOf(0, bytes.size - 22 - 0xffff)) { if (u32(bytes, i) == 0x06054b50L) { eocd = i; break }; i-- }
        if (eocd < 0) return null
        val count = u16(bytes, eocd + 10); val dirSize = u32(bytes, eocd + 12); val dirOffset = u32(bytes, eocd + 16)
        if (count == 0xffff || dirOffset + dirSize > eocd) return null
        val entries = LinkedHashMap<String, ByteArray>()
        var p = dirOffset.toInt(); var total = 0L
        repeat(count) {
            if (p + 46 > eocd || u32(bytes, p) != 0x02014b50L) return null
            val flags = u16(bytes, p + 8); val method = u16(bytes, p + 10); val crc = u32(bytes, p + 16)
            val packed = u32(bytes, p + 20); val size = u32(bytes, p + 24)
            val nameLength = u16(bytes, p + 28); val extra = u16(bytes, p + 30); val comment = u16(bytes, p + 32); val local = u32(bytes, p + 42)
            if (flags and 1 != 0 || packed == 0xffffffffL || size == 0xffffffffL || local == 0xffffffffL) return null
            if (p + 46 + nameLength > eocd) return null
            val name = bytes.decodeToString(p + 46, p + 46 + nameLength)
            val l = local.toInt()
            if (l + 30 > bytes.size || u32(bytes, l) != 0x04034b50L) return null
            val start = l + 30 + u16(bytes, l + 26) + u16(bytes, l + 28)
            if (start + packed > bytes.size) return null
            // A small archive can expand to gigabytes (a ZIP bomb): stop well before the phone runs out of memory.
            total += size
            require(total <= MAX_EXPANDED) { "The file expands to more than ${MAX_EXPANDED shr 20} MB" }
            val data = when (method) {
                0 -> { if (packed != size) return null; bytes.copyOfRange(start, start + size.toInt()) }
                8 -> {
                    val inflater = Inflater(true)
                    try {
                        inflater.setInput(bytes, start, packed.toInt())
                        val out = ByteArray(size.toInt()); var n = 0
                        while (n < out.size) { val r = inflater.inflate(out, n, out.size - n); if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break; n += r }
                        if (n != out.size) return null
                        out
                    } finally { inflater.end() }
                }
                else -> return null
            }
            val check = CRC32(); check.update(data)
            require(check.value == crc) { "Malformed ZIP archive: $name is damaged" }
            entries[name] = data
            p += 46 + nameLength + extra + comment
        }
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
