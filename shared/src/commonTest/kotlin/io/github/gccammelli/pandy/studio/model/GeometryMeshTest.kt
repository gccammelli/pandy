// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryMeshTest {
    private fun close(a:Float,b:Float,epsilon:Float=1e-5f)=assertTrue(kotlin.math.abs(a-b)<epsilon,"$a != $b")

    @Test fun vectorOperationsAndNormalization() {
        val a=Vec3(1f,2f,3f);val b=Vec3(4f,-2f,1f)
        assertEquals(Vec3(5f,0f,4f),a+b);assertEquals(Vec3(-3f,4f,2f),a-b)
        assertEquals(Vec3(2f,4f,6f),a*2f);assertEquals(3f,a.dot(b));assertEquals(Vec3(8f,11f,-10f),a.cross(b))
        close(a.length(),kotlin.math.sqrt(14f));close(a.normalized().length(),1f);assertEquals(Vec3(0f,0f,0f),Vec3(0f,0f,0f).normalized())
    }

    @Test fun matrixCompositionInverseAndTransform() {
        val transform=Mat4.translation(3f,4f,5f)*Mat4.rotationZ(90f)*Mat4.scale(2f,2f,2f)
        val point=transform.transformPoint(Vec3(1f,0f,0f));close(point.x,3f);close(point.y,6f);close(point.z,5f)
        val inverse=assertNotNull(transform.inverted());val restored=inverse.transformPoint(point)
        close(restored.x,1f);close(restored.y,0f);close(restored.z,0f)
        assertNull(Mat4.scale(1f,0f,1f).inverted())
        assertEquals(Vec3(0f,1f,0f),Mat4.rotationZ(90f).transformDirection(Vec3(1f,0f,0f)).let { Vec3(if(kotlin.math.abs(it.x)<1e-5f)0f else it.x,it.y,it.z) })
        val m=Mat4.translation(2f,3f,4f);val product=m*m.inverted()!!
        for(i in 0 until 16)close(product.m[i],Mat4.identity().m[i])
    }

    @Test fun cameraProjectionAndTranspose() {
        val view=Mat4.lookAt(Vec3(0f,0f,1f),Vec3(0f,0f,0f),Vec3(0f,1f,0f))
        close(view.transformPoint(Vec3(0f,0f,0f)).z,-1f)
        close(Mat4.ortho(-1f,1f,-1f,1f,1f,10f).transformPoint(Vec3(1f,1f,-1f)).x,1f)
        assertEquals(1f,Mat4.perspective(90f,1f,1f,10f).m[5])
        val matrix=Mat4.translation(1f,2f,3f);assertEquals(matrix.m.toList(),matrix.transposed().transposed().m.toList())
        assertEquals("1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0 1.0 2.0 3.0",matrix.toRowMajor3x4String())
        assertEquals(matrix.m.toList(),Mat4.fromThreeMf(matrix.toRowMajor3x4String()).m.toList())
    }

    @Test fun meshBoundsNormalsAreaVolumeTransformAndMerge() {
        val tetra=Mesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 0f,0f,1f),intArrayOf(0,2,1, 0,1,3, 0,3,2, 1,2,3))
        val bounds=tetra.bounds();assertEquals(Vec3(0f,0f,0f),bounds.min);assertEquals(Vec3(1f,1f,1f),bounds.max);assertEquals(Vec3(0.5f,0.5f,0.5f),bounds.center)
        close(tetra.volume(),1f/6f);assertTrue(tetra.surfaceArea()>2f)
        val flat=tetra.toFlatShadedBuffers();assertEquals(36,flat.first.size);assertEquals(36,flat.second.size);assertEquals(12,tetra.flatNormals().size)
        val translated=tetra.transformed(Mat4.translation(2f,0f,0f));assertEquals(Vec3(2f,0f,0f),translated.bounds().min)
        assertEquals(8,tetra.merged(tetra).vertexCount);assertEquals(8,tetra.merged(tetra).triangleCount)
        assertTrue(Mesh.empty.bounds().isEmpty);assertEquals(Vec3(0f,0f,0f),Mesh.empty.bounds().size)
        assertEquals(tetra.bounds(),tetra.bounds().union(Mesh.empty.bounds()))
    }
}
