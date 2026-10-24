package io.github.gccammelli.pandy

/** A zip with stored (uncompressed) entries, enough for the printer's partial-read zip reader. */
fun storedZip(vararg entries: Pair<String, String>): ByteArray {
    fun le(v: Int, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
    val out = mutableListOf<Byte>(); val central = mutableListOf<Byte>()
    for ((name, text) in entries) {
        val nameBytes = name.encodeToByteArray(); val data = text.encodeToByteArray(); val offset = out.size
        out += le(0x04034b50, 4).toList() + le(20, 2).toList() + le(0, 2).toList() + le(0, 2).toList() + le(0, 4).toList() + le(0, 4).toList() +
            le(data.size, 4).toList() + le(data.size, 4).toList() + le(nameBytes.size, 2).toList() + le(0, 2).toList() + nameBytes.toList() + data.toList()
        central += le(0x02014b50, 4).toList() + le(20, 2).toList() + le(20, 2).toList() + le(0, 2).toList() + le(0, 2).toList() + le(0, 4).toList() + le(0, 4).toList() +
            le(data.size, 4).toList() + le(data.size, 4).toList() + le(nameBytes.size, 2).toList() + le(0, 2).toList() + le(0, 2).toList() + le(0, 2).toList() + le(0, 2).toList() +
            le(0, 4).toList() + le(offset, 4).toList() + nameBytes.toList()
    }
    val directoryOffset = out.size
    out += central
    out += le(0x06054b50, 4).toList() + le(0, 2).toList() + le(0, 2).toList() + le(entries.size, 2).toList() + le(entries.size, 2).toList() +
        le(central.size, 4).toList() + le(directoryOffset, 4).toList() + le(0, 2).toList()
    return out.toByteArray()
}
