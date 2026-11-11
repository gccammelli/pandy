// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.gccammelli.pandy.studio.gcode.GcodeParser
import io.github.gccammelli.pandy.studio.io.SceneObject
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Shapes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 3D plate view: gestures move the camera and objects, and every scene change is copied into a new GL frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = Scene()
    private var dark = false

    private fun plateObject(x: Float = 128f, y: Float = 128f): PlateObject {
        val o = PlateObject.of(scene.newId(), SceneObject("Mochi", Shapes.box(40f, 40f, 40f), Mat4.translation(x, y, 0f)))
        return o
    }

    private fun show(): GLSurfaceView {
        compose.setContent { Viewport(scene, dark) }
        compose.waitForIdle()
        return find(compose.activity.window.decorView)!!
    }

    private fun find(v: View): GLSurfaceView? {
        if (v is GLSurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun GLSurfaceView.renderer(): GlRenderer = javaClass.getDeclaredField("renderer").also { it.isAccessible = true }.get(this) as GlRenderer
    private fun GLSurfaceView.camera(): OrbitCamera = javaClass.getDeclaredField("camera").also { it.isAccessible = true }.get(this) as OrbitCamera

    private fun ev(action: Int, vararg pts: Pair<Float, Float>, index: Int = 0): MotionEvent {
        val props = Array(pts.size) { MotionEvent.PointerProperties().also { p -> p.id = it; p.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { MotionEvent.PointerCoords().also { c -> c.x = pts[it].first; c.y = pts[it].second } }
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, a, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun GLSurfaceView.touch(action: Int, vararg pts: Pair<Float, Float>, index: Int = 0) { assertTrue(dispatchTouchEvent(ev(action, *pts, index = index))) }

    @Test fun showingTheViewFramesTheBedAndPushesAFrame() {
        scene.add(plateObject())
        val v = show()
        assertEquals(scene.bed.centerX, v.camera().target.x)
        val frame = assertNotNull(v.renderer().frame)
        assertEquals(1, frame.objects.size); assertEquals(0, frame.selected); assertNull(frame.preview); assertEquals(false, frame.dark)
        assertEquals(scene.bed.width * 1.75f, v.camera().distance)
    }

    @Test fun sceneChangesAreCopiedIntoNewFrames() {
        val v = show()
        assertEquals(0, v.renderer().frame!!.objects.size)
        scene.add(plateObject()); compose.waitForIdle()
        assertEquals(1, v.renderer().frame!!.objects.size)
        scene.objects[0].x = 40f; compose.waitForIdle()
        assertTrue(v.renderer().frame!!.objects[0].second.m.any { it == 40f })
        scene.mode = ViewMode.PREVIEW
        scene.preview = GcodeParser.parse("M83\nG90\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X1 Y0 Z0.2 E0.5\n"); compose.waitForIdle()
        assertNotNull(v.renderer().frame!!.preview)
        scene.mode = ViewMode.PREPARE; compose.waitForIdle()
        assertNull(v.renderer().frame!!.preview)
    }

    @Test fun anObjectOffTheBedIsFlaggedOutside() {
        scene.add(plateObject(x = 300f))
        val v = show()
        assertEquals(setOf(0), v.renderer().frame!!.outside)
    }

    @Test fun coloursFollowTheObjectsFilamentWithAFallback() {
        scene.colors = listOf("#FF0000", "#00FF00")
        val a = plateObject(); val b = plateObject(); b.filament = 1; val c = plateObject(); c.filament = 7
        scene.add(a); scene.add(b); scene.add(c)
        val f = show().renderer().frame!!
        assertEquals(listOf(1f, 0f, 0f), f.colors[0].take(3)); assertEquals(listOf(0f, 1f, 0f), f.colors[1].take(3)); assertEquals(listOf(1f, 0f, 0f), f.colors[2].take(3))
    }

    @Test fun darkThemeReachesTheFrame() {
        dark = true; scene.add(plateObject())
        assertEquals(true, show().renderer().frame!!.dark)
    }

    @Test fun oneFingerDragOrbitsTheCamera() {
        val v = show(); val cam = v.camera(); val yaw = cam.yaw; val pitch = cam.pitch
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_MOVE, 160f to 140f)
        v.touch(MotionEvent.ACTION_UP, 160f to 140f)
        assertEquals(yaw + 60f * 0.35f, cam.yaw, 0.01f); assertEquals(pitch + 40f * 0.3f, cam.pitch, 0.01f)
    }

    @Test fun orbitPitchIsClamped() {
        val v = show(); val cam = v.camera()
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_MOVE, 100f to 900f)
        assertEquals(89f, cam.pitch)
        v.touch(MotionEvent.ACTION_MOVE, 100f to -900f)
        assertEquals(5f, cam.pitch)
    }

    @Test fun smallMovementsAreTapsNotOrbits() {
        val v = show(); val cam = v.camera(); val yaw = cam.yaw
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_MOVE, 105f to 103f)
        v.touch(MotionEvent.ACTION_UP, 105f to 103f)
        assertEquals(yaw, cam.yaw)
    }

    @Test fun tappingAnObjectSelectsItAndTappingEmptySpaceClears() {
        val o = plateObject(); scene.add(o); scene.selected = null
        val v = show(); val cam = v.camera()
        cam.width = v.width.coerceAtLeast(400); cam.height = v.height.coerceAtLeast(300)
        val p = cam.viewProj().let { m -> m.transformPoint(io.github.gccammelli.pandy.studio.model.Vec3(o.x, o.y, 20f)) }
        val sx = (p.x + 1f) / 2f * cam.width; val sy = (1f - p.y) / 2f * cam.height
        v.touch(MotionEvent.ACTION_DOWN, sx to sy); v.touch(MotionEvent.ACTION_UP, sx to sy)
        assertEquals(o.id, scene.selected)
        v.touch(MotionEvent.ACTION_DOWN, 1f to 1f); v.touch(MotionEvent.ACTION_UP, 1f to 1f)
        assertNull(scene.selected)
    }

    @Test fun draggingTheSelectedObjectMovesItAcrossTheBed() {
        val o = plateObject(); scene.add(o)
        val v = show(); val cam = v.camera()
        cam.width = v.width.coerceAtLeast(400); cam.height = v.height.coerceAtLeast(300)
        val p = cam.viewProj().transformPoint(io.github.gccammelli.pandy.studio.model.Vec3(o.x, o.y, 0f))
        val sx = (p.x + 1f) / 2f * cam.width; val sy = (1f - p.y) / 2f * cam.height
        val x0 = o.x
        v.touch(MotionEvent.ACTION_DOWN, sx to sy)
        v.touch(MotionEvent.ACTION_MOVE, (sx + 60f) to sy)
        v.touch(MotionEvent.ACTION_UP, (sx + 60f) to sy)
        assertTrue(o.x != x0)
        assertEquals(o.id, scene.selected)
    }

    @Test fun pinchZoomsAndTwoFingerDragPans() {
        val v = show(); val cam = v.camera()
        cam.width = 400; cam.height = 300
        val d0 = cam.distance; val t0 = cam.target
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 200f to 100f, index = 1)
        v.touch(MotionEvent.ACTION_MOVE, 100f to 100f, 300f to 100f)
        assertTrue(cam.distance < d0)
        v.touch(MotionEvent.ACTION_MOVE, 150f to 130f, 350f to 130f)
        assertTrue(cam.target != t0)
        v.touch(MotionEvent.ACTION_POINTER_UP, 150f to 130f, 350f to 130f, index = 0)
        v.touch(MotionEvent.ACTION_POINTER_UP, 150f to 130f, 350f to 130f, index = 1)
        v.touch(MotionEvent.ACTION_UP, 350f to 130f)
        assertTrue(cam.distance in 40f..3000f)
    }

    private fun screenOf(cam: OrbitCamera, x: Float, y: Float, z: Float): Pair<Float, Float> {
        val p = cam.viewProj().transformPoint(io.github.gccammelli.pandy.studio.model.Vec3(x, y, z))
        return (p.x + 1f) / 2f * cam.width to (1f - p.y) / 2f * cam.height
    }

    @Test fun tappingInPreviewModeNeverChangesTheSelection() {
        val o = plateObject(); scene.add(o); scene.mode = ViewMode.PREVIEW
        val v = show(); val cam = v.camera(); cam.width = 400; cam.height = 300
        val (sx, sy) = screenOf(cam, o.x, o.y, 20f)
        v.touch(MotionEvent.ACTION_DOWN, sx to sy); v.touch(MotionEvent.ACTION_UP, sx to sy)
        assertEquals(o.id, scene.selected)
        scene.selected = null
        v.touch(MotionEvent.ACTION_DOWN, sx to sy); v.touch(MotionEvent.ACTION_UP, sx to sy)
        assertNull(scene.selected)
    }

    @Test fun aSmallWobbleOnTheSelectedObjectDoesNotMoveIt() {
        val o = plateObject(); scene.add(o)
        val v = show(); val cam = v.camera(); cam.width = 400; cam.height = 300
        val (sx, sy) = screenOf(cam, o.x, o.y, 0f); val x0 = o.x; val y0 = o.y
        v.touch(MotionEvent.ACTION_DOWN, sx to sy); v.touch(MotionEvent.ACTION_MOVE, (sx + 3f) to sy); v.touch(MotionEvent.ACTION_UP, (sx + 3f) to sy)
        assertEquals(x0, o.x); assertEquals(y0, o.y)
    }

    @Test fun pinchingWithBothFingersOnTheSamePointKeepsTheDistance() {
        val v = show(); val cam = v.camera(); cam.width = 400; cam.height = 300
        val d0 = cam.distance
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 100f to 100f, index = 1)
        v.touch(MotionEvent.ACTION_MOVE, 100f to 100f, 100f to 100f)
        assertEquals(d0, cam.distance)
    }

    @Test fun aThirdFingerDoesNotRestartThePinch() {
        val v = show(); val cam = v.camera(); cam.width = 400; cam.height = 300
        v.touch(MotionEvent.ACTION_DOWN, 100f to 100f)
        v.touch(MotionEvent.ACTION_POINTER_DOWN, 100f to 100f, 200f to 100f, 300f to 100f, index = 2)
        v.touch(MotionEvent.ACTION_MOVE, 100f to 100f, 200f to 100f, 300f to 100f)
        assertTrue(cam.distance in 40f..3000f)
    }

    @Test fun anAxisAlignedRayOnlyHitsAnObjectInItsColumn() {
        val inColumn = plateObject(x = 128f, y = 128f); val aside = plateObject(x = 220f, y = 128f)
        scene.add(inColumn); scene.add(aside); scene.selected = null
        val v = show(); val cam = v.camera(); cam.width = 400; cam.height = 300; cam.yaw = 0f
        val (cx, _) = screenOf(cam, cam.target.x, cam.target.y, 0f)
        val (_, sy) = screenOf(cam, 128f, 128f, 20f)
        v.touch(MotionEvent.ACTION_DOWN, cx to sy); v.touch(MotionEvent.ACTION_UP, cx to sy)
        assertEquals(inColumn.id, scene.selected)
        scene.selected = null
        val (_, sy2) = screenOf(cam, 220f, 128f, 20f)
        v.touch(MotionEvent.ACTION_DOWN, cx to sy2); v.touch(MotionEvent.ACTION_UP, cx to sy2)
        assertTrue(scene.selected != aside.id)
    }

    @Test fun leavingTheCompositionDetachesTheView() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { if (visible.value) Viewport(scene, dark) }
        compose.waitForIdle()
        val v = find(compose.activity.window.decorView)!!
        assertTrue(v.isAttachedToWindow)
        visible.value = false; compose.waitForIdle()
        assertNull(find(compose.activity.window.decorView))
    }
}
