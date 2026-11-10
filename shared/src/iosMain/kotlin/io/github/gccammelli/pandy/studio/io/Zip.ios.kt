// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import io.github.gccammelli.pandy.inflateRaw
import io.github.gccammelli.pandy.zipStored

actual object Zip {
    /** Largest total size of a ZIP's contents the app unpacks into memory. */
    const val MAX_EXPANDED = 400L shl 20

    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

    /** Reads through the central directory: stored and deflated entries, no ZIP64. */
    actual fun read(bytes: ByteArray): Map<String, ByteArray> {
        fun bad(why: String): Nothing = throw IllegalArgumentException("Malformed ZIP archive: $why")
        var e = bytes.size - 22
        while (e >= 0 && bytes.u32(e) != 0x06054b50L) e--
        if (e < 0) bad("missing ZIP signature")
        val count = bytes.u16(e + 10)
        var i = bytes.u32(e + 16).also { if (it > bytes.size) bad("directory out of range") }.toInt()
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        repeat(count) {
            if (i + 46 > bytes.size || bytes.u32(i) != 0x02014b50L) bad("broken directory")
            val method = bytes.u16(i + 10); val comp = bytes.u32(i + 20); val size = bytes.u32(i + 24)
            val n = bytes.u16(i + 28); val x = bytes.u16(i + 30); val c = bytes.u16(i + 32); val local = bytes.u32(i + 42)
            if (i + 46L + n + x + c > bytes.size || local + 30 > bytes.size) bad("broken directory")
            val name = bytes.copyOfRange(i + 46, i + 46 + n).decodeToString()
            total += size
            // A small archive can expand to gigabytes (a ZIP bomb): stop well before the phone runs out of memory.
            require(total <= MAX_EXPANDED) { "The file expands to more than ${MAX_EXPANDED shr 20} MB" }
            val l = local.toInt()
            val start = l + 30L + bytes.u16(l + 26) + bytes.u16(l + 28)
            if (start + comp > bytes.size) bad("entry out of range")
            val raw = bytes.copyOfRange(start.toInt(), (start + comp).toInt())
            entries[name] = when (method) {
                0 -> raw.also { if (comp != size) bad("invalid stored entry") }
                8 -> runCatching { inflateRaw(raw, size.toInt()) }.getOrElse { bad("invalid data") }
                else -> bad("unsupported compression")
            }
            i += 46 + n + x + c
        }
        return entries
    }

    /** Always stored: nothing here needs the archives small. */
    actual fun write(entries: List<Pair<String, ByteArray>>, deflate: Boolean): ByteArray {
        for ((name, _) in entries) require(name.isNotEmpty() && !name.startsWith("/")) { "ZIP entry name must be a non-empty relative path" }
        return zipStored(entries)
    }
}
