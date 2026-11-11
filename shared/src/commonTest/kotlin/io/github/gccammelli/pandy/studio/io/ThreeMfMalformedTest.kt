// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Mesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the 3MF, STL and matrix readers say about broken input, and the lenient XML forms they accept. */
class ThreeMfMalformedTest {
    private val tri = """<mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh>"""

    private fun archive(model: String, vararg more: Pair<String, String>) =
        Zip.write(listOf("3D/3dmodel.model" to model.encodeToByteArray()) + more.map { it.first to it.second.encodeToByteArray() })

    private fun fails(model: String, vararg more: Pair<String, String>): String =
        assertFailsWith<IllegalArgumentException> { ThreeMf.read(archive(model, *more)) }.message.orEmpty()

    private fun model(resources: String, build: String = """<build><item objectid="1"/></build>""", attrs: String = "") =
        """<model$attrs><resources>$resources</resources>$build</model>"""

    @Test fun anArchiveWithoutAModelIsRejected() {
        val msg = assertFailsWith<IllegalArgumentException> { ThreeMf.read(Zip.write(listOf("readme.txt" to byteArrayOf(1)))) }.message.orEmpty()
        assertTrue("no model document" in msg, msg)
    }

    @Test fun anyDotModelEntryStandsInForTheDefaultPath() {
        val loaded = ThreeMf.read(Zip.write(listOf("other/part.model" to model("""<object id="1">$tri</object>""").encodeToByteArray())))
        assertEquals("Object 1", loaded.single().name)
    }

    @Test fun notAZipIsReportedAsInvalid() {
        val msg = assertFailsWith<IllegalArgumentException> { ThreeMf.read(byteArrayOf(1, 2, 3, 4, 5)) }.message.orEmpty()
        assertTrue(msg.startsWith("Invalid 3MF ZIP"), msg)
    }

    @Test fun buildItemsAndObjectsMustBeReferencedCorrectly() {
        assertTrue("no objectid" in fails(model("""<object id="1">$tri</object>""", """<build><item/></build>""")))
        assertTrue("object 9 was not found" in fails(model("""<object id="1">$tri</object>""", """<build><item objectid="9"/></build>""")))
        assertTrue("object has no id" in fails(model("""<object>$tri</object>""")))
    }

    @Test fun aModelWithoutBuildItemsHasNoObjects() {
        assertEquals(0, ThreeMf.read(archive("""<model><resources><object id="1">$tri</object></resources></model>""")).size)
    }

    @Test fun componentsMustBeResolvable() {
        assertTrue("component has no objectid" in fails(model("""<object id="1"><components><component/></components></object>""")))
        assertTrue("component object 7 was not found" in fails(model("""<object id="1"><components><component objectid="7"/></components></object>""")))
        assertTrue("component model not found" in fails(model("""<object id="1"><components><component objectid="1" p:path="/3D/missing.model" xmlns:p="x"/></components></object>""")))
    }

    @Test fun aComponentCycleIsRejected() {
        val msg = fails(model("""<object id="1"><components><component objectid="2"/></components></object><object id="2"><components><component objectid="1"/></components></object>"""))
        assertTrue("Cyclic" in msg, msg)
    }

    @Test fun componentPathsAreNormalisedAndReusedAcrossComponents() {
        val part = model("""<object id="1">$tri</object>""", "")
        val root = model(
            """<object id="5"><components><component objectid="1" p:path="\3D\.\Objects\..\Objects\a.model" xmlns:p="x"/><component objectid="1" p:path="/3D/Objects/a.model" xmlns:p="x"/></components></object>""",
            """<build><item objectid="5"/></build>""",
        )
        val loaded = ThreeMf.read(archive(root, "3D/Objects/a.model" to part)).single()
        assertEquals(2, loaded.mesh.triangleCount)
    }

    @Test fun trianglesAndVerticesMustBeValid() {
        fun withMesh(m: String) = fails(model("""<object id="1">$m</object>"""))
        assertTrue("Invalid 3MF triangle index" in withMesh("""<mesh><vertices><vertex x="0" y="0" z="0"/></vertices><triangles><triangle v1="0" v2="x" v3="0"/></triangles></mesh>"""))
        assertTrue("out of range" in withMesh("""<mesh><vertices><vertex x="0" y="0" z="0"/></vertices><triangles><triangle v1="0" v2="0" v3="5"/></triangles></mesh>"""))
        assertTrue("Invalid 3MF vertex y" in withMesh("""<mesh><vertices><vertex x="0" y="nan" z="0"/></vertices></mesh>"""))
        assertTrue("Invalid 3MF vertex z" in withMesh("""<mesh><vertices><vertex x="0" y="0"/></vertices></mesh>"""))
    }

    @Test fun anObjectWithoutAMeshIsEmpty() {
        assertEquals(0, ThreeMf.read(archive(model("""<object id="1" name="Mochi"/>"""))).single().mesh.triangleCount)
    }

    @Test fun modelRootAndUnitMustBeValid() {
        assertTrue("no model element" in fails("<notamodel/>"))
        assertTrue("Unsupported 3MF unit: furlong" in fails(model("""<object id="1">$tri</object>""", attrs = """ unit="furlong"""")))
        assertEquals(1, ThreeMf.read(archive(model("""<object id="1">$tri</object>""", attrs = """ unit="MILLIMETER""""))).size)
    }

    @Test fun transformsMustHaveTwelveNumbers() {
        val build = { t: String -> """<build><item objectid="1" transform="$t"/></build>""" }
        assertTrue("12 numbers" in fails(model("""<object id="1">$tri</object>""", build("1 2 3"))))
        assertTrue("Invalid 3MF transform number: x" in fails(model("""<object id="1">$tri</object>""", build("1 0 0 0 1 0 0 0 1 0 0 x"))))
        assertEquals(1, ThreeMf.read(archive(model("""<object id="1">$tri</object>""", build("  ")))).size)
    }

    @Test fun xmlDialectsAreTolerated() {
        val xml = """<?xml version="1.0"?><!DOCTYPE model [<!ENTITY x "'>'">]><!-- a comment --><model unit='millimeter'><![CDATA[ <ignored> ]]><resources><object id="1" name="Mochi &amp; Luna &lt;&#65;&#x42;&gt; &quot;&apos; &unknown; &#zz; &bare">$tri</object></resources><build><item objectid = "1" /></build></model>"""
        assertEquals("""Mochi & Luna <AB> "' &unknown; &#zz; &bare""", ThreeMf.read(archive(xml)).single().name)
    }

    @Test fun brokenXmlIsRejectedWithASpecificMessage() {
        assertTrue("Malformed XML comment" in fails("<!-- never closed"))
        assertTrue("Malformed XML CDATA" in fails("<![CDATA[ never closed"))
        assertTrue("Unterminated XML declaration" in fails("<?xml version"))
        assertTrue("Malformed XML tag" in fails("< >"))
        assertTrue("Unterminated XML closing tag" in fails("<model></model"))
        assertTrue("Unterminated XML start tag" in fails("<model "))
        assertTrue("Malformed XML attribute" in fails("<model \"x\">"))
        assertTrue("lacks value" in fails("<model unit>"))
        assertTrue("must be quoted" in fails("<model unit=millimeter>"))
        assertTrue("Unterminated XML attribute" in fails("<model unit=\"mm>"))
        assertTrue("Empty XML document" in fails("no tags at all"))
    }

    @Test fun fixedFormattingRoundsHalvesToEvenAndRejectsHugeOrNonFiniteValues() {
        assertEquals("1.500000", ThreeMf.formatFixed6(1.5f))
        assertEquals("0.500000", ThreeMf.formatFixed6(0.5f))
        assertFailsWith<IllegalArgumentException> { ThreeMf.formatFixed6(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ThreeMf.formatFixed6(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { ThreeMf.formatFixed6(1e30f) }
    }

    @Test fun matrixRejectsWrongSizeAndPivotlessInversion() {
        assertFailsWith<IllegalArgumentException> { Mat4(FloatArray(15)) }
        assertEquals(null, Mat4(FloatArray(16)).inverted())
        assertEquals(null, Mat4(FloatArray(16) { Float.NaN }).inverted())
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(40f, 0f, 1f, 10f) }
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(40f, 1f, 0f, 10f) }
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(40f, 1f, 5f, 5f) }
        assertFailsWith<IllegalArgumentException> { Mat4.ortho(1f, 1f, 0f, 1f, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Mat4.ortho(0f, 1f, 2f, 2f, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Mat4.ortho(0f, 1f, 0f, 1f, 3f, 3f) }
    }

    @Test fun perspectivePointsAreDividedByW() {
        val p = Mat4.perspective(60f, 1f, 1f, 100f).transformPoint(io.github.gccammelli.pandy.studio.model.Vec3(1f, 1f, -10f))
        assertTrue(p.x in 0f..1f && p.z < 1f, "$p")
    }

    @Test fun stlRejectsBadInput() {
        val msg = { b: ByteArray -> assertFailsWith<IllegalArgumentException> { Stl.read(b) }.message.orEmpty() }
        assertTrue("too short" in msg(byteArrayOf(1, 2)))
        assertTrue("expected a valid binary length" in msg(ByteArray(100) { 1 }))
        assertTrue("incomplete vertex" in msg("solid a facet vertex 1 2".encodeToByteArray()))
        assertTrue("invalid vertex" in msg("solid a facet vertex 1 2 nope".encodeToByteArray()))
        assertTrue("complete triangular" in msg("solid a facet endfacet".encodeToByteArray()))
        assertTrue("complete triangular" in msg("solid a facet vertex 0 0 0 vertex 1 0 0 endfacet".encodeToByteArray()))
    }

    @Test fun binaryStlWithNonFiniteVerticesIsRejected() {
        val bytes = Stl.writeBinary(Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2)))
        // Overwrite the first vertex x with a NaN pattern (offset 84 header + 12 normal).
        bytes[96] = 0; bytes[97] = 0; bytes[98] = 0xC0.toByte(); bytes[99] = 0x7F
        assertTrue("non-finite" in assertFailsWith<IllegalArgumentException> { Stl.read(bytes) }.message.orEmpty())
    }
}
