package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stl3mfTest {
    // A 10 mm tetrahedron far from the bed, in ASCII and binary.
    private val tetra = listOf(
        floatArrayOf(100f, 200f, 50f, 110f, 200f, 50f, 100f, 210f, 50f),
        floatArrayOf(100f, 200f, 50f, 100f, 210f, 50f, 100f, 200f, 60f),
        floatArrayOf(100f, 200f, 50f, 100f, 200f, 60f, 110f, 200f, 50f),
        floatArrayOf(110f, 200f, 50f, 100f, 200f, 60f, 100f, 210f, 50f),
    )

    private fun ascii() = buildString {
        append("solid t\n")
        for (t in tetra) { append(" facet normal 0 0 0\n  outer loop\n"); for (c in 0 until 3) append("   vertex ${t[c * 3]} ${t[c * 3 + 1]} ${t[c * 3 + 2]}\n"); append("  endloop\n endfacet\n") }
        append("endsolid t\n")
    }.encodeToByteArray()

    private fun binary(): ByteArray {
        val out = ByteArray(84 + 50 * tetra.size)
        out[80] = tetra.size.toByte()
        tetra.forEachIndexed { i, t ->
            for (k in 0 until 9) { val b = t[k].toBits(); for (j in 0 until 4) out[84 + i * 50 + 12 + k * 4 + j] = (b shr (8 * j)).toByte() }
        }
        return out
    }

    @Test fun detectsStlAndNotZipsOrText() {
        assertTrue(looksLikeStl(ascii())); assertTrue(looksLikeStl(binary()))
        assertFalse(looksLikeStl(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(100)))
        assertFalse(looksLikeStl("hello world, not a model at all".encodeToByteArray()))
    }

    @Test fun asciiAndBinaryGiveTheSameCentredModel() = runTest {
        val a = stlTo3mf(ascii()); val b = stlTo3mf(binary())
        assertEquals(a.decodeToString().length, b.decodeToString().length)
        for (zip in listOf(a, b)) {
            assertTrue(zip[0] == 'P'.code.toByte() && zip[1] == 'K'.code.toByte())
            val model = zipEntry(zip.size.toLong(), listOf(Regex("3D/3dmodel\\.model"))) { o, l -> zip.copyOfRange(o.toInt(), (o + l).toInt()) }!!.second.decodeToString()
            assertEquals(4, Regex("<vertex ").findAll(model).count()) // shared corners are merged
            assertEquals(4, Regex("<triangle ").findAll(model).count())
            assertTrue(model.contains("unit=\"millimeter\""))
            // 10 mm wide model centred on x/y 90, standing on z 0.
            assertTrue(model.contains("x=\"85.0\"") && model.contains("x=\"95.0\"") && model.contains("z=\"0.0\"") && model.contains("z=\"10.0\""), model)
        }
    }

    @Test fun theWrappedModelReadsAsAProjectWithOnePlate() = runTest {
        val project = readProject(stlTo3mf(binary()), preview = false)
        assertEquals(1, project.plates.size)
        assertTrue(project.filaments.isEmpty())
        assertFalse(project.sliced)
    }

    @Test fun refusesEmptyOrDamagedStl() {
        assertFailsWith<ApiException> { stlTo3mf("solid x\nendsolid x\n".encodeToByteArray()) }
        assertFailsWith<ApiException> { stlTo3mf("solid x\nfacet\nvertex a b c\nvertex 1 2 3\nvertex 4 5 6\nendfacet\n".encodeToByteArray()) }
    }

    @Test fun malformedStlIsRefusedWithAReason() {
        fun ascii(vararg vertices: String) = ("solid x\nfacet normal 0 0 0\nouter loop\n" + vertices.joinToString("\n") { "vertex $it" } + "\nendloop\nendfacet\nendsolid x\n").encodeToByteArray()
        assertTrue(assertFailsWith<ApiException> { stlTo3mf(ascii("0 0 0", "1 0 0")) }.message!!.contains("no model")) // a triangle needs three corners
        assertTrue(assertFailsWith<ApiException> { stlTo3mf(ascii("0 0 0", "1 0 0", "NaN 1 0")) }.message!!.contains("damaged"))
        assertTrue(assertFailsWith<ApiException> { stlTo3mf("".encodeToByteArray()) }.message!!.contains("no model"))
        assertFalse(looksLikeStl("solid but nothing else of a model in here, just words".encodeToByteArray()))
    }

    @Test fun degenerateTrianglesAreLeftOut() = runTest {
        val flat = "solid x\nfacet\nnormal 0 0 1\nvertex 0 0 0\nvertex 0 0 0\nvertex 1 0 0\nendfacet\nfacet\nvertex 0 0 0\nvertex 1 0 0\nvertex 1 0 0\nendfacet\nfacet\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 0 0\nendfacet\nfacet\nvertex -5 -5 -5\nvertex 1 0 0\nvertex 0 0 9\nendfacet\nfacet\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendfacet\nendsolid x\n"
        val zip = stlTo3mf(flat.encodeToByteArray())
        val model = zipEntry(zip.size.toLong(), listOf(Regex("3D/3dmodel\\.model"))) { o, l -> zip.copyOfRange(o.toInt(), (o + l).toInt()) }!!.second.decodeToString()
        assertEquals(2, Regex("<triangle ").findAll(model).count())
    }
}
