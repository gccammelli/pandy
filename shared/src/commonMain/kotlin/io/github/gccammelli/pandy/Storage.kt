package io.github.gccammelli.pandy

/*
 * The printer's own storage (SD card or internal), over FTPS on port 990 in direct mode: print files (.3mf) in its
 * root and /cache, timelapses in /timelapse. 3MF files are zips; [zipEntry] reads one file out of one by fetching only
 * the bytes it needs, so a thumbnail costs kilobytes instead of the whole print file.
 */

/** [modified] is "YYYY-MM-DDTHH:MM" (the printer's local time; the year is guessed for recent files, as `ls` omits it). */
data class FileEntry(val name: String, val size: Long, val modified: String, val dir: Boolean)

class PrinterStorageException(val code: Int, detail: String) : Exception("Printer storage: $code ${detail.trim()}")

interface PrinterStorage {
    suspend fun list(dir: String): List<FileEntry>
    /** [length] -1 reads to the end. */
    suspend fun read(path: String, offset: Long = 0, length: Int = -1): ByteArray
    suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit = {})
    /** Deletes one file (not a folder). */
    suspend fun delete(path: String): Unit = throw ApiException("This storage can't delete files")
}

/** What the file manager browses: the printer's own storage (the demo has a pretend one). */
interface PrinterFiles {
    suspend fun listFiles(dir: String): List<FileEntry>
    suspend fun deleteFile(path: String)
}

expect fun printerStorage(c: LanConfig): PrinterStorage

/** Inflates raw DEFLATE data (zip method 8) of known [size]. */
expect fun inflateRaw(data: ByteArray, size: Int): ByteArray

/** Saves bytes to the app's cache and returns a local address a player can open. */
expect suspend fun cacheFile(name: String, bytes: ByteArray): String

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val LIST_LINE = Regex("""^([d-])\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\w{3})\s+(\d+)\s+(\d{1,2}:\d{2}|\d{4})\s+(.+)$""")
private const val MAX_ZIP_ENTRY_BYTES = 8L * 1024 * 1024
private const val MAX_ZIP_DIRECTORY_BYTES = 8L * 1024 * 1024

/** Parses `LIST` lines (Unix `ls -l` style). [year] and [month] (1–12) are today's, to place dates shown with a time. */
fun parseList(lines: List<String>, year: Int, month: Int): List<FileEntry> = lines.mapNotNull { l ->
    val m = LIST_LINE.find(l.trimEnd()) ?: return@mapNotNull null
    val (type, size, mon, day, yt, name) = m.destructured
    val mi = MONTHS.indexOf(mon) + 1
    val (y, time) = if (':' in yt) (if (mi > month) year - 1 else year) to yt.padStart(5, '0') else yt.toInt() to "00:00"
    FileEntry(name, size.toLong(), "$y-${mi.toString().padStart(2, '0')}-${day.padStart(2, '0')}T$time", type == "d")
}

private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

/**
 * Reads the first entry of the zip whose name matches [wanted] (checked in order of [wanted]'s preference: the first
 * pattern that matches any entry wins). [fetch] reads [len] bytes at [offset] of a zip of [total] bytes.
 */
suspend fun zipEntry(total: Long, wanted: List<Regex>, fetch: suspend (offset: Long, len: Int) -> ByteArray): Pair<String, ByteArray>? =
    zipEntry(total, wanted, MAX_ZIP_ENTRY_BYTES, fetch)

suspend fun zipEntry(total: Long, wanted: List<Regex>, maxSize: Long, fetch: suspend (offset: Long, len: Int) -> ByteArray): Pair<String, ByteArray>? {
    if (total < 22 || maxSize < 0) return null
    suspend fun exact(offset: Long, length: Int): ByteArray? {
        if (offset > total || length.toLong() > total - offset) return null // offsets and lengths come from unsigned zip fields: never negative
        return fetch(offset, length).takeIf { it.size == length }
    }
    val tailLen = minOf(total, 65_557L).toInt()
    val tail = exact(total - tailLen, tailLen) ?: return null
    var e = tail.size - 22
    while (e >= 0 && tail.u32(e) != 0x06054b50L) e--
    if (e < 0) return null
    val cdSizeLong = tail.u32(e + 12)
    val cdOffset = tail.u32(e + 16)
    if (cdSizeLong > MAX_ZIP_DIRECTORY_BYTES || cdOffset > total || cdSizeLong > total - cdOffset) return null
    val cdSize = cdSizeLong.toInt()
    val cdInTail = cdOffset - (total - tailLen)
    val cd = if (cdInTail >= 0) {
        tail.copyOfRange(cdInTail.toInt(), cdInTail.toInt() + cdSize)
    } else exact(cdOffset, cdSize) ?: return null
    data class Ent(val name: String, val method: Int, val comp: Long, val size: Long, val local: Long)
    val ents = mutableListOf<Ent>()
    var i = 0
    while (i + 46 <= cd.size && cd.u32(i) == 0x02014b50L) {
        val n = cd.u16(i + 28); val x = cd.u16(i + 30); val c = cd.u16(i + 32)
        val end = i.toLong() + 46 + n + x + c
        if (end > cd.size) return null
        ents += Ent(cd.copyOfRange(i + 46, i + 46 + n).decodeToString(), cd.u16(i + 10), cd.u32(i + 20), cd.u32(i + 24), cd.u32(i + 42))
        i += 46 + n + x + c
    }
    val hit = wanted.firstNotNullOfOrNull { r -> ents.firstOrNull { r.matches(it.name) } } ?: return null
    if (hit.size > maxSize || hit.comp > Int.MAX_VALUE) return null
    val head = exact(hit.local, 30) ?: return null
    val start = hit.local + 30 + head.u16(26) + head.u16(28)
    if (start > total || hit.comp > total - start) return null
    val raw = exact(start, hit.comp.toInt()) ?: return null
    val bytes = when (hit.method) {
        0 -> raw.takeIf { hit.comp == hit.size }
        8 -> runCatching { inflateRaw(raw, hit.size.toInt()) }.getOrNull()
        else -> null
    } ?: return null
    return hit.name to bytes
}

/** Objects on a plate from a 3MF's Metadata/slice_info.config: identify_id → name. */
fun sliceObjects(xml: String, plate: Int): Map<Int, String> {
    val plates = xml.split("<plate>").drop(1)
    val p = plates.firstOrNull { Regex("""key="index"\s+value="$plate"""").containsMatchIn(it) } ?: plates.firstOrNull() ?: return emptyMap()
    return Regex("""<object\s+([^>]*)/?>""").findAll(p).mapNotNull { m ->
        val a = Regex("""(\w+)="([^"]*)"""").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        (a["identify_id"]?.toIntOrNull() ?: return@mapNotNull null) to (a["name"] ?: "")
    }.toMap()
}
