// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gccammelli.pandy.studio.gcode.GcodeModel
import io.github.gccammelli.pandy.studio.io.SceneObject
import io.github.gccammelli.pandy.studio.model.Box
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Mesh
import io.github.gccammelli.pandy.studio.model.Vec3

/** The printable area: a rectangle from (originX, originY), as Bambu presets give it ("printable_area"). */
data class Bed(val originX: Float = 0f, val originY: Float = 0f, val width: Float = 256f, val depth: Float = 256f, val height: Float = 256f) {
    val centerX get() = originX + width / 2
    val centerY get() = originY + depth / 2

    companion object {
        /** From a printer preset's printable_area ("0x0", "256x0", "256x256", "0x256") and printable_height. */
        fun of(area: List<String>?, height: String?): Bed {
            val pts = area.orEmpty().mapNotNull { p -> p.split('x').takeIf { it.size == 2 }?.let { (a, b) -> a.toFloatOrNull()?.let { x -> b.toFloatOrNull()?.let { y -> x to y } } } }
            if (pts.isEmpty()) return Bed()
            val minX = pts.minOf { it.first }; val minY = pts.minOf { it.second }
            return Bed(minX, minY, pts.maxOf { it.first } - minX, pts.maxOf { it.second } - minY, height?.toFloatOrNull() ?: 256f)
        }
    }
}

/**
 * A model on the plate. Its mesh is kept as loaded, centred on its own footprint; the placement is a position on the
 * bed, a rotation (Z for spinning, X/Y for laying a face down) and a scale. The object always sits on the bed: its
 * lowest point after rotation and scale is at Z 0, as Bambu Studio keeps it.
 */
class PlateObject(val id: Long, name: String, val mesh: Mesh) {
    var name by mutableStateOf(name)
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
    var rotX by mutableStateOf(0f)
    var rotY by mutableStateOf(0f)
    var rotZ by mutableStateOf(0f)
    var scale by mutableStateOf(Vec3(1f, 1f, 1f))
    /** 0-based filament (AMS slot in the job) this object prints with. */
    var filament by mutableIntStateOf(0)

    private fun orientation() = Mat4.rotationZ(rotZ) * Mat4.rotationY(rotY) * Mat4.rotationX(rotX) * Mat4.scale(scale.x, scale.y, scale.z)

    /** Bounds after rotation and scale, before moving on the bed. */
    fun localBounds(): Box = mesh.transformed(orientation()).bounds()

    fun transform(): Mat4 {
        val b = localBounds()
        return Mat4.translation(x - b.center.x, y - b.center.y, -b.min.z) * orientation()
    }

    fun worldBounds(): Box = mesh.transformed(transform()).bounds()

    fun copy(newId: Long) = PlateObject(newId, name, mesh).also {
        it.x = x; it.y = y; it.rotX = rotX; it.rotY = rotY; it.rotZ = rotZ; it.scale = scale; it.filament = filament
    }

    companion object {
        /** Re-centres a loaded mesh on its footprint so placement is about the object's middle. */
        fun of(id: Long, obj: SceneObject): PlateObject {
            val world = obj.mesh.transformed(obj.transform)
            val b = world.bounds()
            val centred = world.transformed(Mat4.translation(-b.center.x, -b.center.y, -b.min.z))
            return PlateObject(id, obj.name, centred).also { it.x = b.center.x; it.y = b.center.y }
        }
    }
}

enum class ViewMode { PREPARE, PREVIEW }

/** Everything the 3D view draws. Changes bump [version], which the view watches to redraw. */
class Scene {
    val objects = mutableStateListOf<PlateObject>()
    var selected by mutableStateOf<Long?>(null)
    var bed by mutableStateOf(Bed())
    var mode by mutableStateOf(ViewMode.PREPARE)
    var preview by mutableStateOf<GcodeModel?>(null)
    /** Visible preview layers, 1-based inclusive. */
    var layerLow by mutableIntStateOf(1)
    var layerHigh by mutableIntStateOf(Int.MAX_VALUE)
    /** Filament colours as #RRGGBB, by filament index. */
    var colors by mutableStateOf(listOf("#E0409C"))
    var version by mutableIntStateOf(0)
    private var nextId = 1L

    fun touch() { version++ }
    fun newId() = nextId++
    val selection get() = objects.firstOrNull { it.id == selected }

    fun add(o: PlateObject) { objects.add(o); selected = o.id; touch() }
    fun remove(id: Long) { objects.removeAll { it.id == id }; if (selected == id) selected = null; touch() }

    /** Lays objects out in rows from the bed's front-left corner, 8 mm apart, largest first (a simple arrange). */
    fun arrange(gap: Float = 8f) {
        var cx = bed.originX + gap; var cy = bed.originY + gap; var row = 0f
        for (o in objects.sortedByDescending { it.localBounds().size.let { s -> s.x * s.y } }) {
            val s = o.localBounds().size
            if (cx + s.x > bed.originX + bed.width - gap && cx > bed.originX + gap) { cx = bed.originX + gap; cy += row + gap; row = 0f }
            o.x = cx + s.x / 2; o.y = cy + s.y / 2
            cx += s.x + gap; row = maxOf(row, s.y)
        }
        // Centre the whole layout on the bed.
        val all = objects.map { it.worldBounds() }.reduceOrNull { a, b -> a.union(b) } ?: return
        val dx = bed.centerX - all.center.x; val dy = bed.centerY - all.center.y
        objects.forEach { it.x += dx; it.y += dy }
        touch()
    }

    /** Objects that stick out of the printable volume (they would not slice as placed). */
    fun outside(): List<PlateObject> = objects.filter { o ->
        val b = o.worldBounds()
        b.min.x < bed.originX - 0.01f || b.min.y < bed.originY - 0.01f || b.max.x > bed.originX + bed.width + 0.01f ||
            b.max.y > bed.originY + bed.depth + 0.01f || b.max.z > bed.height + 0.01f
    }

    /** The plate as the slicer gets it: every object with its final placement. */
    fun sceneObjects(): List<SceneObject> = objects.map { SceneObject(it.name, it.mesh, it.transform()) }
}
