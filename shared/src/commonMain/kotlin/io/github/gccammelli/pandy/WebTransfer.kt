package io.github.gccammelli.pandy

/** Decodes a bounded WebView transfer one chunk at a time, avoiding a second giant base64 string. */
class ChunkCollector(private val total: Int, private val maxBytes: Int) {
    val totalChunks get() = total
    private val parts = arrayOfNulls<ByteArray>(total)
    private var count = 0
    private var size = 0
    var rejected = false
        private set

    fun add(index: Int, bytes: ByteArray): ByteArray? {
        if (rejected || index !in parts.indices) return null
        if (parts[index] == null) {
            if (bytes.size > maxBytes - size) {
                rejected = true
                return null
            }
            parts[index] = bytes
            count++
            size += bytes.size
        }
        if (count != total) return null
        val out = ByteArray(size)
        var offset = 0
        parts.forEachIndexed { i, part ->
            part!!.copyInto(out, offset)
            offset += part.size
            parts[i] = null
        }
        return out
    }
}
