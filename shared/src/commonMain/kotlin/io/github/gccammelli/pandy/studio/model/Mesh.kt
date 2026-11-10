// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.model

import kotlin.math.sqrt

/** Axis-aligned bounds; an empty box has min greater than max on at least one axis. */
data class Box(val min: Vec3, val max: Vec3) {
    val isEmpty get() = min.x > max.x || min.y > max.y || min.z > max.z
    val size get() = if (isEmpty) Vec3(0f, 0f, 0f) else max - min
    val center get() = if (isEmpty) Vec3(0f, 0f, 0f) else (min + max) * 0.5f
    fun union(other: Box): Box = when { isEmpty -> other; other.isEmpty -> this; else -> Box(Vec3(minOf(min.x,other.min.x),minOf(min.y,other.min.y),minOf(min.z,other.min.z)), Vec3(maxOf(max.x,other.max.x),maxOf(max.y,other.max.y),maxOf(max.z,other.max.z))) }
}

/** Triangle mesh with packed XYZ positions and three indices per triangle. */
class Mesh(val positions: FloatArray, val indices: IntArray) {
    init { require(positions.size % 3 == 0) { "Positions must contain XYZ triples" }; require(indices.size % 3 == 0) { "Indices must contain triangle triples" }; require(indices.all { it in 0 until vertexCount }) { "Mesh index out of range" } }
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
    private fun vertex(i: Int) = Vec3(positions[i*3], positions[i*3+1], positions[i*3+2])

    fun bounds(): Box {
        if (vertexCount == 0) return Box(Vec3(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY), Vec3(Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY))
        var min = vertex(0); var max = min
        for (i in 1 until vertexCount) { val p=vertex(i); min=Vec3(minOf(min.x,p.x),minOf(min.y,p.y),minOf(min.z,p.z)); max=Vec3(maxOf(max.x,p.x),maxOf(max.y,p.y),maxOf(max.z,p.z)) }
        return Box(min,max)
    }
    fun transformed(matrix: Mat4): Mesh { val p=FloatArray(positions.size); for(i in 0 until vertexCount) { val v=matrix.transformPoint(vertex(i)); p[i*3]=v.x;p[i*3+1]=v.y;p[i*3+2]=v.z }; return Mesh(p,indices.copyOf()) }
    fun toFlatShadedBuffers(): Pair<FloatArray,FloatArray> {
        val p=FloatArray(indices.size*3); val n=FloatArray(indices.size*3)
        for(t in 0 until triangleCount) { val a=vertex(indices[t*3]);val b=vertex(indices[t*3+1]);val c=vertex(indices[t*3+2]);val normal=(b-a).cross(c-a).normalized(); for(j in 0..2) { val v=listOf(a,b,c)[j];val k=t*9+j*3;p[k]=v.x;p[k+1]=v.y;p[k+2]=v.z;n[k]=normal.x;n[k+1]=normal.y;n[k+2]=normal.z } }
        return p to n
    }
    fun flatNormals(): FloatArray { val out=FloatArray(triangleCount*3); for(t in 0 until triangleCount) { val a=vertex(indices[t*3]);val b=vertex(indices[t*3+1]);val c=vertex(indices[t*3+2]);val n=(b-a).cross(c-a).normalized();out[t*3]=n.x;out[t*3+1]=n.y;out[t*3+2]=n.z }; return out }
    fun volume(): Float { var sum=0.0; for(t in 0 until triangleCount) { val a=vertex(indices[t*3]);val b=vertex(indices[t*3+1]);val c=vertex(indices[t*3+2]);sum += a.dot(b.cross(c)).toDouble()/6.0 }; return sum.toFloat() }
    fun surfaceArea(): Float { var area=0.0; for(t in 0 until triangleCount) { val a=vertex(indices[t*3]);val b=vertex(indices[t*3+1]);val c=vertex(indices[t*3+2]);area += sqrt((b-a).cross(c-a).dot((b-a).cross(c-a))).toDouble()/2.0 }; return area.toFloat() }
    fun merged(other: Mesh): Mesh { val p=positions.copyOf(positions.size+other.positions.size);other.positions.copyInto(p,positions.size);val ix=indices.copyOf(indices.size+other.indices.size);for(i in other.indices.indices)ix[indices.size+i]=other.indices[i]+vertexCount;return Mesh(p,ix) }

    companion object { val empty get() = Mesh(FloatArray(0), IntArray(0)) }
}
