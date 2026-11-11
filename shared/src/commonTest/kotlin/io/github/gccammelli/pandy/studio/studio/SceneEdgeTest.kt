// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.model.Shapes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bed parsing, plate layout and the out-of-bounds check on their less common inputs. */
class SceneEdgeTest {
    private fun obj(scene: Scene, w: Float, d: Float, h: Float = 10f) =
        PlateObject(scene.newId(), "Mochi", Shapes.box(w, d, h)).also { scene.add(it) }

    @Test fun bedFromAreaPointsUsesTheirBoundingRectangle() {
        val bed = Bed.of(listOf("10x20", "110x20", "110x220", "10x220"), "300")
        assertEquals(Bed(10f, 20f, 100f, 200f, 300f), bed)
        assertEquals(60f, bed.centerX); assertEquals(120f, bed.centerY)
    }

    @Test fun bedFallsBackToTheDefaultForMissingOrBrokenPoints() {
        assertEquals(Bed(), Bed.of(null, null))
        assertEquals(Bed(), Bed.of(listOf("garbage", "1x2x3", "ax5", "5xb"), "100"))
        assertEquals(256f, Bed.of(listOf("0x0", "50x40"), "tall").height)
        assertEquals(Bed(0f, 0f, 50f, 40f, 256f), Bed.of(listOf("0x0", "50x40", "bad"), null))
    }

    @Test fun removingTheSelectedObjectClearsTheSelectionButAnotherLeavesIt() {
        val scene = Scene(); val a = obj(scene, 10f, 10f); val b = obj(scene, 10f, 10f)
        assertEquals(b.id, scene.selected)
        scene.remove(a.id); assertEquals(b.id, scene.selected); assertEquals(1, scene.objects.size)
        scene.remove(b.id); assertNull(scene.selected); assertNull(scene.selection)
    }

    @Test fun arrangeStartsANewRowWhenTheCurrentOneIsFull() {
        val scene = Scene(); scene.bed = Bed(0f, 0f, 100f, 100f, 100f)
        val a = obj(scene, 60f, 30f); val b = obj(scene, 60f, 30f)
        scene.arrange()
        assertTrue(kotlin.math.abs(a.y - b.y) > 20f, "rows ${a.y} ${b.y}")
        assertEquals(50f, (a.x + b.x) / 2, 0.01f); assertEquals(50f, (a.y + b.y) / 2, 0.01f)
    }

    @Test fun arrangeKeepsAnOversizedObjectOnItsOwnRowWithoutLooping() {
        val scene = Scene(); scene.bed = Bed(0f, 0f, 50f, 50f, 50f)
        val a = obj(scene, 80f, 20f)
        scene.arrange()
        assertEquals(25f, a.x, 0.01f)
    }

    @Test fun arrangingAnEmptyPlateDoesNothing() {
        val scene = Scene(); val v = scene.version
        scene.arrange()
        assertEquals(v, scene.version)
    }

    @Test fun outsideChecksEverySideAndTheHeight() {
        val scene = Scene(); scene.bed = Bed(0f, 0f, 100f, 100f, 50f)
        fun put(x: Float, y: Float, h: Float = 10f) = obj(scene, 10f, 10f, h).also { it.x = x; it.y = y }
        val ok = put(50f, 50f); val left = put(2f, 50f); val front = put(50f, 2f); val right = put(98f, 50f); val back = put(50f, 98f); val tall = put(50f, 50f, 60f)
        val out = scene.outside()
        assertEquals(listOf(left, front, right, back, tall).map { it.id }, out.map { it.id })
        assertTrue(ok !in out)
    }

    @Test fun sceneObjectsCarryTheFinalTransform() {
        val scene = Scene(); val o = obj(scene, 10f, 10f, 10f); o.x = 30f; o.y = 40f
        val so = scene.sceneObjects().single()
        assertEquals("Mochi", so.name)
        assertEquals(o.transform().m.toList(), so.transform.m.toList())
    }
}
