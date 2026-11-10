// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Mesh
import io.github.gccammelli.pandy.studio.model.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StlThreeMfTest {
    private fun sample()=Mesh(floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f),intArrayOf(0,1,2))

    @Test fun binaryAndAsciiStlRoundTripAndWeld() {
        val binary=Stl.writeBinary(sample());val result=Stl.read(binary)
        assertEquals(3,result.vertexCount);assertEquals(1,result.triangleCount);assertEquals(0f,result.volume())
        val ascii="solid test\n facet normal 0 0 1\n outer loop\n vertex 0 0 0\n vertex 1 0 0\n vertex 0 1 0\n endloop\n endfacet\nendsolid"
        val parsed=Stl.read(ascii.encodeToByteArray());assertEquals(3,parsed.vertexCount);assertEquals(intArrayOf(0,1,2).toList(),parsed.indices.toList())
        assertFailsWith<IllegalArgumentException>{Stl.read("solid bad facet vertex x 0 0".encodeToByteArray())}
        assertFailsWith<IllegalArgumentException>{Stl.read(byteArrayOf(1,2,3))}
    }

    @Test fun plainThreeMfRoundTripAndFixedNumberFormatting() {
        assertEquals("0.000000",ThreeMf.formatFixed6(0f));assertEquals("-0.000000",ThreeMf.formatFixed6(-0.0f))
        assertEquals("0.000000",ThreeMf.formatFixed6(1e-7f));assertEquals("123456.789062",ThreeMf.formatFixed6(123456.789f));assertEquals("-0.000000",ThreeMf.formatFixed6(-0.0000004f))
        val scene=listOf(SceneObject("triangle",sample(),Mat4.translation(2f,3f,4f)))
        val archive=ThreeMf.writePlain(scene)
        val modelXml=Zip.read(archive).getValue("3D/3dmodel.model").decodeToString()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources><object id=\"1\" name=\"triangle\" type=\"model\"><mesh><vertices><vertex x=\"0.000000\" y=\"0.000000\" z=\"0.000000\"/><vertex x=\"1.000000\" y=\"0.000000\" z=\"0.000000\"/><vertex x=\"0.000000\" y=\"1.000000\" z=\"0.000000\"/></vertices><triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles></mesh></object></resources><build><item objectid=\"1\" transform=\"1.000000 0.000000 0.000000 0.000000 1.000000 0.000000 0.000000 0.000000 1.000000 2.000000 3.000000 4.000000\" printable=\"1\"/></build></model>",modelXml)
        val loaded=ThreeMf.read(archive);assertEquals(1,loaded.size);assertEquals("triangle",loaded[0].name)
        assertEquals(scene[0].mesh.positions.toList(),loaded[0].mesh.positions.toList());assertEquals(scene[0].mesh.indices.toList(),loaded[0].mesh.indices.toList())
        assertEquals(Vec3(2f,3f,4f),loaded[0].transform.transformPoint(Vec3(0f,0f,0f)))
    }

    @Test fun threeMfComponentPathAndUnits() {
        val base="""<model unit="centimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06"><resources><object id="5" name="assembly"><components><component objectid="1" p:path="/3D/Objects/object_1.model" transform="1 0 0 0 1 0 0 0 1 2 0 0"/></components></object></resources><build><item objectid="5" transform="1 0 0 0 1 0 0 0 1 0 3 0"/></build></model>"""
        val external="""<model unit="millimeter"><resources><object id="1" name="part"><mesh><vertices><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><vertex x="0" y="0" z="1"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources></model>"""
        val bytes=Zip.write(listOf("3D/3dmodel.model" to base.encodeToByteArray(),"3D/Objects/object_1.model" to external.encodeToByteArray()))
        val object1=ThreeMf.read(bytes).single();assertEquals("assembly",object1.name);assertEquals(21f,object1.mesh.positions[0])
        assertEquals(0f,object1.transform.m[12]);assertEquals(30f,object1.transform.m[13]);assertTrue(object1.mesh.triangleCount==1)
    }

    @Test fun threeMfUnitScalesToMillimeters() {
        val scales=mapOf("millimeter" to 1f,"inch" to 25.4f,"micron" to 0.001f,"centimeter" to 10f,"meter" to 1000f,"foot" to 304.8f)
        for((unit,scale) in scales) {
            val xml="""<model unit="$unit"><resources><object id="1"><mesh><vertices><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><vertex x="0" y="0" z="1"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>"""
            val loaded=ThreeMf.read(Zip.write(listOf("3D/3dmodel.model" to xml.encodeToByteArray()))).single()
            assertEquals(scale,loaded.mesh.positions[0],0.0001f,"unit $unit")
        }
    }
}
