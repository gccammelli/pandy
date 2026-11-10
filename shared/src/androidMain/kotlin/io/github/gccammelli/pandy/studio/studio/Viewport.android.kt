// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gccammelli.pandy.studio.model.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
private class PlateView(context: Context, val scene: Scene) : GLSurfaceView(context) {
    val camera = OrbitCamera()
    val renderer = GlRenderer(camera)
    var dark = false
    private var framedFor: Bed? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 4)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Copies the scene for the GL thread and redraws. */
    fun push() {
        if (framedFor != scene.bed) { camera.frame(scene.bed); framedFor = scene.bed }
        val objs = scene.objects.toList()
        val out = scene.outside().map { o -> objs.indexOf(o) }.toSet()
        renderer.frame = Frame(
            scene.bed, objs.map { it.mesh to it.transform() },
            objs.map { GlRenderer.hex(scene.colors.getOrElse(it.filament) { scene.colors.firstOrNull() ?: "#E0409C" }) },
            objs.indexOfFirst { it.id == scene.selected }, if (scene.mode == ViewMode.PREVIEW) scene.preview else null,
            scene.layerLow, scene.layerHigh, dark, out,
        )
        requestRender()
    }

    // Gestures: one finger orbits (or drags the touched object across the bed), two fingers pinch and pan.
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f
    private var lastSpan = 0f; private var lastMid = 0f to 0f; private var lastAngle = 0f
    private var dragging: PlateObject? = null; private var grab = Vec3(0f, 0f, 0f); private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; moved = false
                dragging = if (scene.mode == ViewMode.PREPARE) pick(e.x, e.y)?.takeIf { it.id == scene.selected } else null
                dragging?.let { o -> camera.onPlane(e.x, e.y)?.let { p -> grab = Vec3(p.x - o.x, p.y - o.y, 0f) } }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                dragging = null; lastSpan = span(e); lastMid = mid(e); lastAngle = angle(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 12f) moved = true
                if (e.pointerCount >= 2) {
                    val s = span(e); val m = mid(e)
                    if (lastSpan > 0 && s > 1f) camera.distance = (camera.distance * lastSpan / s).coerceIn(40f, 3000f)
                    val k = camera.distance / height * 0.9f
                    val yaw = Math.toRadians(camera.yaw.toDouble())
                    val dx = (m.first - lastMid.first) * k; val dy = (m.second - lastMid.second) * k
                    camera.target = camera.target + Vec3((-dx * kotlin.math.cos(yaw) - dy * kotlin.math.sin(yaw)).toFloat(),
                        (-dx * kotlin.math.sin(yaw) + dy * kotlin.math.cos(yaw)).toFloat(), 0f)
                    lastSpan = s; lastMid = m; lastAngle = angle(e)
                    push()
                } else if (dragging != null && moved) {
                    camera.onPlane(e.x, e.y)?.let { p -> dragging!!.x = p.x - grab.x; dragging!!.y = p.y - grab.y; scene.touch() }
                } else if (moved) {
                    camera.yaw += (e.x - lastX) * 0.35f
                    camera.pitch = (camera.pitch + (e.y - lastY) * 0.3f).coerceIn(5f, 89f)
                    push()
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && scene.mode == ViewMode.PREPARE) { scene.selected = pick(e.x, e.y)?.id; scene.touch() }
                dragging = null
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.getX(if (e.actionIndex == 0) 1 else 0); lastY = e.getY(if (e.actionIndex == 0) 1 else 0); lastSpan = 0f }
        }
        return true
    }

    /** The nearest object whose box the touch ray passes through. */
    private fun pick(x: Float, y: Float): PlateObject? {
        val (o, d) = camera.ray(x, y) ?: return null
        return scene.objects.mapNotNull { obj -> hit(o, d, obj.worldBounds())?.let { t -> obj to t } }.minByOrNull { it.second }?.first
    }

    private fun hit(o: Vec3, d: Vec3, b: io.github.gccammelli.pandy.studio.model.Box): Float? {
        var lo = -Float.MAX_VALUE; var hi = Float.MAX_VALUE
        for ((oc, dc, mn, mx) in listOf(Quad(o.x, d.x, b.min.x, b.max.x), Quad(o.y, d.y, b.min.y, b.max.y), Quad(o.z, d.z, b.min.z, b.max.z))) {
            if (kotlin.math.abs(dc) < 1e-7f) { if (oc < mn || oc > mx) return null; continue }
            var t1 = (mn - oc) / dc; var t2 = (mx - oc) / dc
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            lo = maxOf(lo, t1); hi = minOf(hi, t2)
            if (lo > hi) return null
        }
        return lo.takeIf { hi > 0 }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun mid(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2 to (e.getY(0) + e.getY(1)) / 2
    private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
}

@Composable
actual fun Viewport(scene: Scene, dark: Boolean, modifier: Modifier) {
    val holder = remember { arrayOfNulls<PlateView>(1) }
    AndroidView(factory = { ctx -> PlateView(ctx, scene).also { holder[0] = it; it.dark = dark; it.push() } }, modifier = modifier,
        update = { v -> v.dark = dark; v.push() })
    LaunchedEffect(scene) {
        snapshotFlow { listOf(scene.version, scene.mode, scene.preview, scene.layerLow, scene.layerHigh, scene.selected, scene.bed, scene.colors,
            scene.objects.map { listOf(it.x, it.y, it.rotX, it.rotY, it.rotZ, it.scale, it.filament) }) }
            .collect { holder[0]?.push() }
    }
    DisposableEffect(Unit) { onDispose { holder[0]?.onPause() } }
}
