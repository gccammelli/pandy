// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.gcode.Feature
import io.github.gccammelli.pandy.studio.gcode.GcodeParser
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.model.Vec3
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The GL renderer and orbit camera with the GLES calls as Robolectric no-ops: the state they keep is asserted, not pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlRendererTest {
    private val gcode = """M83
G90
; CHANGE_LAYER
; Z_HEIGHT: 0.2
; LAYER_HEIGHT: 0.2
; FEATURE: Outer wall
G1 X1 Y0 Z0.2 E0.5
G1 X2 Y0 E0.5
; FEATURE: Sparse infill
G1 X2 Y2 E0.5
; CHANGE_LAYER
; Z_HEIGHT: 0.4
; LAYER_HEIGHT: 0.2
; FEATURE: Top surface
G1 X0 Y2 Z0.4 E0.5
"""

    private fun field(r: Any, name: String): Any? = r.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(r)

    private fun frame(
        bed: Bed = Bed(), objects: Int = 1, preview: Boolean = false, dark: Boolean = false, selected: Int = 0,
        outside: Set<Int> = emptySet(), low: Int = 1, high: Int = Int.MAX_VALUE, colors: Int = 1,
    ) = Frame(
        bed, List(objects) { Shapes.box(10f, 10f, 10f) to Mat4.translation(20f * it, 0f, 0f) },
        List(colors) { GlRenderer.hex("#33AA55") }, selected, if (preview) GcodeParser.parse(gcode) else null, low, high, dark, outside,
    )

    private fun renderer(camera: OrbitCamera = OrbitCamera()) = GlRenderer(camera).also {
        it.onSurfaceCreated(null, null); it.onSurfaceChanged(null, 400, 300)
    }

    @Test fun surfaceChangeSizesTheCamera() {
        val cam = OrbitCamera(); renderer(cam)
        assertEquals(400, cam.width); assertEquals(300, cam.height)
    }

    @Test fun drawingWithoutAFrameClearsAndStops() {
        val r = renderer(); r.onDrawFrame(null)
        assertNull(r.frame); assertEquals(0, (field(r, "meshCache") as Map<*, *>).size)
    }

    @Test fun drawingObjectsUploadsEachMeshOnceAndBuildsTheBed() {
        val r = renderer(); r.frame = frame(objects = 2)
        r.onDrawFrame(null); r.onDrawFrame(null)
        val cache = field(r, "meshCache") as Map<*, *>
        assertEquals(2, cache.size)
    }

    @Test fun bedGeometryHasTrianglesGridAndEdge() {
        val r = renderer(); r.frame = frame(bed = Bed(0f, 0f, 50f, 30f, 40f))
        r.onDrawFrame(null)
        assertEquals(6, field(r, "bedTris"))
        // grid lines every 10 mm: 4 verticals + 2 horizontals, 2 vertices each, plus 8 edge vertices
        assertEquals((4 + 2) * 2 + 8, field(r, "bedLines"))
        assertEquals(Bed(0f, 0f, 50f, 30f, 40f) to false, field(r, "bedFor"))
    }

    @Test fun changingTheBedOrThemeRebuildsTheBed() {
        val r = renderer(); r.frame = frame(); r.onDrawFrame(null)
        val first = field(r, "bedVbo")
        r.onDrawFrame(null)
        assertEquals(first, field(r, "bedVbo"))
        r.frame = frame(dark = true); r.onDrawFrame(null)
        assertEquals(Bed() to true, field(r, "bedFor"))
        r.frame = frame(bed = Bed(10f, 10f, 100f, 100f, 100f), dark = true); r.onDrawFrame(null)
        assertEquals(Bed(10f, 10f, 100f, 100f, 100f) to true, field(r, "bedFor"))
        assertEquals((9 + 9) * 2 + 8, field(r, "bedLines"))
    }

    @Test fun meshesRemovedFromThePlateAreDroppedFromTheCache() {
        val r = renderer(); val cache = field(r, "meshCache") as Map<*, *>
        r.frame = frame(objects = 3, selected = 2, outside = setOf(1), colors = 1)
        r.onDrawFrame(null)
        assertEquals(3, cache.size)
        r.frame = frame(objects = 1); r.onDrawFrame(null)
        assertEquals(1, cache.size)
    }

    @Test fun missingColoursFallBackToTheFirstOne() {
        val r = renderer(); r.frame = frame(objects = 2, colors = 1, selected = -1)
        r.onDrawFrame(null)
        assertEquals(2, (field(r, "meshCache") as Map<*, *>).size)
    }

    @Test fun previewBuildsLayerStartsAndRebuildsOnlyForANewModel() {
        val r = renderer(); r.frame = frame(preview = true)
        r.onDrawFrame(null)
        val starts = field(r, "layerStarts") as IntArray
        assertEquals(listOf(0, 6, 8), starts.toList())
        val model = field(r, "previewFor"); assertNotNull(model)
        r.onDrawFrame(null)
        assertTrue(model === field(r, "previewFor"))
        r.frame = frame(preview = true); r.onDrawFrame(null)
        assertTrue(model !== field(r, "previewFor"))
        assertEquals(0, (field(r, "meshCache") as Map<*, *>).size)
    }

    @Test fun previewLayerRangeIsClamped() {
        val r = renderer()
        r.frame = frame(preview = true, low = -5, high = 1); r.onDrawFrame(null)
        r.frame = frame(preview = true, low = 9, high = 2); r.onDrawFrame(null)
        r.frame = frame(preview = true, low = 2, high = Int.MAX_VALUE); r.onDrawFrame(null)
        assertNotNull(field(r, "previewFor"))
    }

    @Test fun hexColoursParseAndFallBackToThePandyPink() {
        assertEquals(listOf(1f, 0f, 0f, 1f), GlRenderer.hex("#FF0000").toList())
        val bad = GlRenderer.hex("zzz")
        assertEquals(0xE0 / 255f, bad[0]); assertEquals(0x40 / 255f, bad[1]); assertEquals(0x9C / 255f, bad[2])
        assertEquals(Feature.entries.size, GlRenderer.FEATURE_COLORS.size)
    }

    @Test fun orbitCameraFramesTheBedAndReturnsAnEyeAboveIt() {
        val cam = OrbitCamera(); cam.frame(Bed(0f, 0f, 200f, 100f, 100f))
        assertEquals(Vec3(100f, 50f, 0f), cam.target); assertEquals(350f, cam.distance)
        assertTrue(cam.eye().z > 0f)
    }

    @Test fun cameraRaysAndPlaneHits() {
        val cam = OrbitCamera(); cam.width = 400; cam.height = 300; cam.frame(Bed())
        val (o, d) = assertNotNull(cam.ray(200f, 150f))
        assertTrue(d.z < 0f)
        val hit = assertNotNull(cam.onPlane(200f, 150f))
        assertEquals(0f, hit.z, 0.01f); assertEquals(128f, hit.x, 1f); assertEquals(128f, hit.y, 1f)
        assertTrue(o.z > 0f)
        // Looking above the horizon never meets the bed.
        cam.pitch = 5f
        assertNull(cam.onPlane(200f, 0f))
    }

    @Test fun aPlaneParallelToTheViewNeverHits() {
        val cam = OrbitCamera(); cam.width = 400; cam.height = 300; cam.pitch = 0f; cam.target = Vec3(0f, 0f, 100f)
        assertNull(cam.onPlane(200f, 150f))
    }
}
