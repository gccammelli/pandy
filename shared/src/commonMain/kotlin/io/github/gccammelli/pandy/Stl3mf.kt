package io.github.gccammelli.pandy

/** Whether [data] is a binary STL (84 + 50 bytes per triangle) or an ASCII one ("solid" … "facet"). */
fun looksLikeStl(data: ByteArray): Boolean {
    if (data.size >= 84 && data.size.toLong() == 84L + 50L * u32(data, 80)) return true
    return data.size > 15 && data.decodeToString(0, minOf(data.size, 512)).trimStart().startsWith("solid") &&
        data.decodeToString(0, minOf(data.size, 4096)).contains("facet")
}

private fun u32(d: ByteArray, at: Int): Long =
    (d[at].toLong() and 0xFF) or ((d[at + 1].toLong() and 0xFF) shl 8) or ((d[at + 2].toLong() and 0xFF) shl 16) or ((d[at + 3].toLong() and 0xFF) shl 24)

private fun f32(d: ByteArray, at: Int) = Float.fromBits(u32(d, at).toInt())

/**
 * Wraps an STL into a plain 3MF (millimetres, one object) that Pandy Slicer slices with the printer's own settings. The model
 * is stood on the bed (lowest point at z 0) and centred on [bed] mm; the slicer arranges it again, so this only keeps the
 * preview and a wrong-sized bed from putting it outside the plate.
 */
fun stlTo3mf(data: ByteArray, bed: Float = 90f): ByteArray {
    val binary = data.size >= 84 && data.size.toLong() == 84L + 50L * u32(data, 80)
    // x, y, z per triangle corner.
    var corners = FloatArray(0)
    var used = 0
    if (binary) {
        val count = u32(data, 80)
        corners = FloatArray(count.toInt() * 9)
        for (t in 0 until count.toInt()) {
            val base = 84 + t * 50 + 12 // after the normal
            for (c in 0 until 3) for (axis in 0 until 3) corners[used++] = f32(data, base + c * 12 + axis * 4)
        }
    } else {
        for (line in data.decodeToString().lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 4 && parts[0].equals("vertex", true)) {
                if (used + 3 > corners.size) corners = corners.copyOf(maxOf(corners.size * 2, 3000))
                for (i in 1..3) corners[used++] = parts[i].toFloatOrNull() ?: throw ApiException("That STL file is damaged")
            }
        }
    }
    if (used == 0 || used % 9 != 0) throw ApiException("That STL file has no model in it")
    for (i in 0 until used) if (!corners[i].isFinite()) throw ApiException("That STL file is damaged")
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = minX; var maxY = maxX; var minZ = minX
    for (i in 0 until used step 3) {
        minX = minOf(minX, corners[i]); maxX = maxOf(maxX, corners[i])
        minY = minOf(minY, corners[i + 1]); maxY = maxOf(maxY, corners[i + 1])
        minZ = minOf(minZ, corners[i + 2])
    }
    val dx = bed - (minX + maxX) / 2; val dy = bed - (minY + maxY) / 2; val dz = -minZ
    val index = HashMap<Triple<Int, Int, Int>, Int>()
    val vertices = StringBuilder(); val triangles = StringBuilder()
    var next = 0
    val ids = IntArray(3)
    for (t in 0 until used / 9) {
        for (c in 0 until 3) {
            val x = corners[t * 9 + c * 3]; val y = corners[t * 9 + c * 3 + 1]; val z = corners[t * 9 + c * 3 + 2]
            ids[c] = index.getOrPut(Triple(x.toBits(), y.toBits(), z.toBits())) {
                vertices.append("<vertex x=\"").append(x + dx).append("\" y=\"").append(y + dy).append("\" z=\"").append(z + dz).append("\"/>")
                next++
            }
        }
        if (ids[0] != ids[1] && ids[1] != ids[2] && ids[0] != ids[2]) triangles.append("<triangle v1=\"${ids[0]}\" v2=\"${ids[1]}\" v3=\"${ids[2]}\"/>")
    }
    val model = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
<resources><object id="1" type="model"><mesh><vertices>$vertices</vertices><triangles>$triangles</triangles></mesh></object></resources>
<build><item objectid="1"/></build></model>"""
    return zipStored(listOf(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""".encodeToByteArray(),
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""".encodeToByteArray(),
        "3D/3dmodel.model" to model.encodeToByteArray(),
    ))
}

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

private fun crc32(data: ByteArray): Int {
    var c = -1
    for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}

/** A zip with its files stored uncompressed. */
internal fun zipStored(files: List<Pair<String, ByteArray>>): ByteArray {
    val out = GrowingBytes()
    val central = GrowingBytes()
    fun GrowingBytes.u16(v: Int) { append(byteArrayOf(v.toByte(), (v shr 8).toByte()), 2) }
    fun GrowingBytes.u32(v: Int) { append(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()), 4) }
    for ((name, body) in files) {
        val n = name.encodeToByteArray(); val crc = crc32(body); val offset = out.size
        out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0); out.u16(0); out.u16(0x21)
        out.u32(crc); out.u32(body.size); out.u32(body.size); out.u16(n.size); out.u16(0)
        out.append(n, n.size); out.append(body, body.size)
        central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0); central.u16(0); central.u16(0x21)
        central.u32(crc); central.u32(body.size); central.u32(body.size); central.u16(n.size); central.u16(0); central.u16(0)
        central.u16(0); central.u16(0); central.u32(0); central.u32(offset); central.append(n, n.size)
    }
    val start = out.size
    val dir = central.toByteArray()
    out.append(dir, dir.size)
    out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(files.size); out.u16(files.size); out.u32(dir.size); out.u32(start); out.u16(0)
    return out.toByteArray()
}
