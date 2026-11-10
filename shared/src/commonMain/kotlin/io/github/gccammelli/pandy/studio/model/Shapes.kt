// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Sample models to try the app without a file (the same shapes the equivalence tests slice). */
object Shapes {
    fun box(sx: Float, sy: Float, sz: Float) = Mesh(
        floatArrayOf(0f, 0f, 0f, sx, 0f, 0f, 0f, sy, 0f, sx, sy, 0f, 0f, 0f, sz, sx, 0f, sz, 0f, sy, sz, sx, sy, sz),
        intArrayOf(0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6, 2, 1, 3, 7, 1, 7, 5),
    )

    /** A solid of revolution around Z from (radius, z) points, bottom to top; radius-0 points close it. */
    fun lathe(profile: List<Pair<Float, Float>>, segments: Int = 96): Mesh {
        val pos = ArrayList<Float>(); val idx = ArrayList<Int>(); val rings = ArrayList<List<Int>>()
        for ((r, z) in profile) {
            val start = pos.size / 3
            if (r == 0f) { pos += 0f; pos += 0f; pos += z; rings += listOf(start) }
            else { for (i in 0 until segments) { val a = 2 * PI * i / segments; pos += (r * cos(a)).toFloat(); pos += (r * sin(a)).toFloat(); pos += z }; rings += (start until start + segments).toList() }
        }
        for ((a, b) in rings.zipWithNext()) for (i in 0 until segments) {
            val j = (i + 1) % segments
            when { a.size == 1 -> idx += listOf(a[0], b[j], b[i]); b.size == 1 -> idx += listOf(a[i], a[j], b[0]); else -> idx += listOf(a[i], a[j], b[j], a[i], b[j], b[i]) }
        }
        return Mesh(pos.toFloatArray(), idx.toIntArray())
    }

    fun vase(): Mesh {
        val pts = mutableListOf(0f to 0f)
        for (i in 0..40) { val t = i / 40f; pts += (22f + 9f * sin(t * 2.6f * PI.toFloat()) - 6f * t) to t * 90f }
        pts += 0f to 90f
        return lathe(pts)
    }

    fun torus(R: Float = 16f, r: Float = 6f, seg: Int = 96, sides: Int = 32): Mesh {
        val pos = FloatArray(seg * sides * 3); val idx = IntArray(seg * sides * 6); var k = 0
        for (i in 0 until seg) for (j in 0 until sides) {
            val u = 2 * PI * i / seg; val v = 2 * PI * j / sides; val o = (i * sides + j) * 3
            pos[o] = ((R + r * cos(v)) * cos(u)).toFloat(); pos[o + 1] = ((R + r * cos(v)) * sin(u)).toFloat(); pos[o + 2] = (r + r * sin(v)).toFloat()
            val a = i * sides + j; val b = ((i + 1) % seg) * sides + j; val c = ((i + 1) % seg) * sides + (j + 1) % sides; val d = i * sides + (j + 1) % sides
            idx[k++] = a; idx[k++] = b; idx[k++] = c; idx[k++] = a; idx[k++] = c; idx[k++] = d
        }
        return Mesh(pos, idx)
    }

    /** A spur gear prism: [teeth] teeth of module [module] mm, [h] mm thick, with a bore. */
    fun gear(teeth: Int = 18, module: Float = 2f, h: Float = 8f, bore: Float = 4f, segments: Int = 4): Mesh {
        val n = teeth * segments * 2
        val outer = (0 until n).map { i ->
            val a = 2 * PI * i / n; val tooth = (i / segments) % 2 == 0
            val r = module * teeth / 2 + if (tooth) module else -1.25f * module
            (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
        }
        val inner = (0 until n).map { i -> val a = 2 * PI * i / n; (bore * cos(a)).toFloat() to (bore * sin(a)).toFloat() }
        val pos = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float): Int { pos += x; pos += y; pos += z; return pos.size / 3 - 1 }
        val ob = outer.map { v(it.first, it.second, 0f) }; val ot = outer.map { v(it.first, it.second, h) }
        val ib = inner.map { v(it.first, it.second, 0f) }; val it2 = inner.map { v(it.first, it.second, h) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            idx += listOf(ob[i], ob[j], ot[j], ob[i], ot[j], ot[i]) // outer wall
            idx += listOf(ib[i], it2[j], ib[j], ib[i], it2[i], it2[j]) // bore wall
            idx += listOf(ot[i], ot[j], it2[j], ot[i], it2[j], it2[i]) // top ring
            idx += listOf(ob[i], ib[j], ob[j], ob[i], ib[i], ib[j]) // bottom ring
        }
        return Mesh(pos.toFloatArray(), idx.toIntArray())
    }

    val samples: List<Pair<String, () -> Mesh>> = listOf(
        "Cube" to { box(20f, 20f, 20f) }, "Gear" to { gear() }, "Vase" to { vase() }, "Torus" to { torus() },
    )
}
