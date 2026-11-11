// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.model

import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.slicer.OptionDef
import io.github.gccammelli.pandy.studio.slicer.PresetLibrary
import io.github.gccammelli.pandy.studio.slicer.ProfileSource
import io.github.gccammelli.pandy.studio.slicer.SliceOutcome
import io.github.gccammelli.pandy.studio.slicer.SliceRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Geometry containers, option kinds and run results on their boundary values. */
class ModelEdgeTest {
    private val unit = Box(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 1f))

    @Test fun aBoxIsEmptyWhenAnyAxisIsInverted() {
        assertFalse(unit.isEmpty)
        assertTrue(Box(Vec3(2f, 0f, 0f), Vec3(1f, 1f, 1f)).isEmpty)
        assertTrue(Box(Vec3(0f, 2f, 0f), Vec3(1f, 1f, 1f)).isEmpty)
        assertTrue(Box(Vec3(0f, 0f, 2f), Vec3(1f, 1f, 1f)).isEmpty)
        assertEquals(Vec3(0f, 0f, 0f), Box(Vec3(0f, 0f, 2f), Vec3(1f, 1f, 1f)).center)
        assertEquals(Vec3(0.5f, 0.5f, 0.5f), unit.center)
    }

    @Test fun unionIgnoresEmptyBoxesOnEitherSide() {
        val empty = Box(Vec3(1f, 1f, 1f), Vec3(0f, 0f, 0f))
        assertEquals(unit, empty.union(unit)); assertEquals(unit, unit.union(empty))
        val far = Box(Vec3(-1f, 2f, -3f), Vec3(0.5f, 3f, 0.5f))
        assertEquals(Box(Vec3(-1f, 0f, -3f), Vec3(1f, 3f, 1f)), unit.union(far))
    }

    @Test fun aMeshMustBeWellFormed() {
        assertFailsWith<IllegalArgumentException> { Mesh(FloatArray(4), IntArray(0)) }
        assertFailsWith<IllegalArgumentException> { Mesh(FloatArray(9), IntArray(2)) }
        assertFailsWith<IllegalArgumentException> { Mesh(FloatArray(9), intArrayOf(0, 1, 3)) }
        assertFailsWith<IllegalArgumentException> { Mesh(FloatArray(9), intArrayOf(0, 1, -1)) }
        assertEquals(1, Mesh(FloatArray(9), intArrayOf(0, 1, 2)).triangleCount)
    }

    @Test fun threeMfTransformRejectsNonFiniteNumbers() {
        assertFailsWith<IllegalArgumentException> { Mat4.fromThreeMf("1 0 0 0 1 0 0 0 1 0 0 Infinity") }
        assertFailsWith<IllegalArgumentException> { Mat4.fromThreeMf("1 0 0 0 1 0 0 0 1 0 NaN 0") }
        assertEquals(5f, Mat4.fromThreeMf(" 1 0 0 0 1 0 0 0 1 5 0 0 ").m[12])
    }

    @Test fun optionKindsCoverVectorAndEnumLabelEdgeCases() {
        assertTrue(OptionDef("coBools").isBool); assertTrue(OptionDef("coBool").isBool); assertFalse(OptionDef("coInt").isBool)
        assertTrue(OptionDef("coPercents").isVector); assertTrue(OptionDef("coFloats").isVector); assertFalse(OptionDef("coString").isVector)
        assertFalse(OptionDef("coFloat").isVector)
        val labelled = OptionDef("coEnum", enum = listOf("a", "b"), enum_labels = listOf("Alpha"))
        assertEquals("Alpha", labelled.labelOf("a")); assertEquals("b", labelled.labelOf("b")); assertEquals("z", labelled.labelOf("z"))
        assertEquals("a", OptionDef("coEnum", enum = listOf("a")).labelOf("a")); assertEquals("a", OptionDef("coEnum").labelOf("a"))
    }

    @Test fun aSliceRunIsOkOnlyWithZeroExitCodeSuccessfulOutcomeAndOutput() {
        fun run(code: Int, outcome: SliceOutcome?, out: ByteArray?) = SliceRun(outcome, code, out, 1, emptyList(), null).ok
        assertTrue(run(0, SliceOutcome(0, ""), byteArrayOf(1)))
        assertFalse(run(1, SliceOutcome(0, ""), byteArrayOf(1)))
        assertFalse(run(0, SliceOutcome(2, ""), byteArrayOf(1)))
        assertFalse(run(0, null, byteArrayOf(1)))
        assertFalse(run(0, SliceOutcome(0, ""), null))
    }

    @Test fun asciiStlNeedsBothTheSolidKeywordAndAFacet() {
        fun msg(s: String) = assertFailsWith<IllegalArgumentException> { Stl.read(s.encodeToByteArray()) }.message.orEmpty()
        assertTrue("expected a valid binary length" in msg("solid abc and nothing else"))
        assertTrue("expected a valid binary length" in msg("a facet but no solid keyword"))
        assertTrue("invalid vertex" in msg("solid a facet vertex 0 0 Infinity"))
        assertTrue("invalid vertex" in msg("solid a facet vertex 0 NaN 0"))
    }

    @Test fun presetIndexEntriesNeedANameAndCompatibilityMustBeAList() {
        fun lib(files: Map<String, String>) = PresetLibrary(ProfileSource { files[it] })
        assertFailsWith<IllegalArgumentException> { lib(mapOf("profiles/BBL.json" to """{"machine_list":[{"sub_path":"a.json"}]}""")) }
        val files = mapOf(
            "profiles/BBL.json" to """{"machine_list":[{"name":"M","sub_path":"m.json"}],"process_list":[{"name":"P","sub_path":"p.json"},{"name":"Q","sub_path":"q.json"}]}""",
            "profiles/BBL/m.json" to """{"name":"M","instantiation":"true"}""",
            "profiles/BBL/p.json" to """{"name":"P","instantiation":"true","compatible_printers":"M"}""",
            "profiles/BBL/q.json" to """{"name":"Q","instantiation":"true","compatible_printers":[]}""",
        )
        val l = lib(files)
        assertTrue(l.processes("M").isEmpty()); assertTrue(l.filaments("M").isEmpty())
        assertEquals(listOf("M"), l.printers().flatMap { it.nozzles }.map { it.name })
    }
}
