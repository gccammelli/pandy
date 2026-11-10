// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.model

import kotlin.math.*

/** A three-dimensional vector with single-precision components. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 { val len = length(); return if (len == 0f) this else this * (1f / len) }
}

/** A 4×4 single-precision matrix stored in OpenGL column-major order. */
class Mat4(val m: FloatArray) {
    init { require(m.size == 16) { "Mat4 requires exactly 16 values" } }

    operator fun times(other: Mat4): Mat4 {
        val out = FloatArray(16)
        for (c in 0..3) for (r in 0..3) for (k in 0..3) out[c * 4 + r] += m[k * 4 + r] * other.m[c * 4 + k]
        return Mat4(out)
    }

    fun transformPoint(v: Vec3): Vec3 {
        val x = m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]
        val y = m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]
        val z = m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]
        val w = m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15]
        return if (w == 0f || w == 1f) Vec3(x, y, z) else Vec3(x / w, y / w, z / w)
    }

    fun transformDirection(v: Vec3) = Vec3(m[0] * v.x + m[4] * v.y + m[8] * v.z, m[1] * v.x + m[5] * v.y + m[9] * v.z, m[2] * v.x + m[6] * v.y + m[10] * v.z)

    fun transposed(): Mat4 = Mat4(FloatArray(16) { i -> m[(i % 4) * 4 + i / 4] })

    fun inverted(): Mat4? {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c * 4 + r].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0..3) {
            var pivot = col
            for (r in col + 1..3) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (a[pivot][col] == 0.0 || !a[pivot][col].isFinite()) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val div = a[col][col]
            for (c in 0..7) a[col][c] /= div
            for (r in 0..3) if (r != col) {
                val factor = a[r][col]
                for (c in 0..7) a[r][c] -= factor * a[col][c]
            }
        }
        return Mat4(FloatArray(16) { i -> a[i % 4][i / 4 + 4].toFloat() })
    }

    /** Returns the 12 values used by the 3MF row-vector transform attribute. */
    fun toRowMajor3x4String(): String = listOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10], m[12], m[13], m[14]).joinToString(" ") { it.toString() }

    companion object {
        fun identity() = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        fun translation(x: Float, y: Float, z: Float) = identity().also { it.m[12] = x; it.m[13] = y; it.m[14] = z }
        fun scale(x: Float, y: Float, z: Float) = Mat4(floatArrayOf(x,0f,0f,0f, 0f,y,0f,0f, 0f,0f,z,0f, 0f,0f,0f,1f))
        fun rotationX(degrees: Float): Mat4 { val a = degrees * (PI.toFloat() / 180f); val c = cos(a); val s = sin(a); return Mat4(floatArrayOf(1f,0f,0f,0f, 0f,c,s,0f, 0f,-s,c,0f, 0f,0f,0f,1f)) }
        fun rotationY(degrees: Float): Mat4 { val a = degrees * (PI.toFloat() / 180f); val c = cos(a); val s = sin(a); return Mat4(floatArrayOf(c,0f,-s,0f, 0f,1f,0f,0f, s,0f,c,0f, 0f,0f,0f,1f)) }
        fun rotationZ(degrees: Float): Mat4 { val a = degrees * (PI.toFloat() / 180f); val c = cos(a); val s = sin(a); return Mat4(floatArrayOf(c,s,0f,0f, -s,c,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)) }
        fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): Mat4 {
            require(aspect != 0f && near > 0f && far > near) { "Invalid perspective frustum" }
            val f = 1f / tan(fovYDeg * PI.toFloat() / 360f)
            return Mat4(floatArrayOf(f / aspect,0f,0f,0f, 0f,f,0f,0f, 0f,0f,(far + near) / (near - far),-1f, 0f,0f,(2f * far * near) / (near - far),0f))
        }
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            val f = (target - eye).normalized(); val s = f.cross(up).normalized(); val u = s.cross(f)
            return Mat4(floatArrayOf(s.x,u.x,-f.x,0f, s.y,u.y,-f.y,0f, s.z,u.z,-f.z,0f, -s.dot(eye),-u.dot(eye),f.dot(eye),1f))
        }
        fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): Mat4 {
            require(r != l && t != b && f != n) { "Invalid orthographic frustum" }
            return Mat4(floatArrayOf(2f/(r-l),0f,0f,0f, 0f,2f/(t-b),0f,0f, 0f,0f,-2f/(f-n),0f, -(r+l)/(r-l),-(t+b)/(t-b),-(f+n)/(f-n),1f))
        }
        fun fromThreeMf(value: String): Mat4 {
            val parts = value.trim().split(Regex("\\s+"))
            require(parts.size == 12) { "3MF transform must contain 12 numbers" }
            val v = parts.map { p -> p.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid 3MF transform number: $p") }
            return Mat4(floatArrayOf(v[0],v[1],v[2],0f, v[3],v[4],v[5],0f, v[6],v[7],v[8],0f, v[9],v[10],v[11],1f))
        }
    }
}
