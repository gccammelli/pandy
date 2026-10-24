package io.github.gccammelli.pandy

import kotlin.test.*

class PreviewMeshTest {
    private val mesh = """<mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh>"""

    @Test fun inlinePreviewAcceptsSingleQuotesAndSkipsUnusableObjectsAndReferences() {
        val xml = """<model><resources>
            <object><mesh/></object><object id="bad"><mesh/></object>
            <object id='1'>$mesh</object>
            <object id="2"><components><component/><component objectid="bad"/><component objectid="99"/></components></object>
            </resources><build><item/><item objectid="bad"/><item objectid="1" transform="invalid"/><item objectid="2"/></build></model>"""
        val triangle = previewMesh(xml)!!.triangles.single()
        assertEquals(PreviewVertex(0f,0f,0f),triangle.a)
        assertEquals(PreviewVertex(1f,0f,0f),triangle.b)
        assertNull(triangle.material)
        assertNull(previewMesh("<model/>"))
    }

    @Test fun sameFileComponentsComposeTransformsAndStopCycles() {
        val xml = """<model><resources><object id="1">$mesh</object>
            <object id="2"><components><component objectid="1" transform="1 0 0 0 1 0 0 0 1 2 3 4"/></components></object>
            <object id="3"><components><component objectid="3"/></components></object>
            </resources><build><item objectid="2" transform="2 0 0 0 2 0 0 0 2 10 20 30"/><item objectid="3"/></build></model>"""
        val triangle = previewMesh(xml)!!.triangles.single()
        assertEquals(PreviewVertex(14f,26f,38f),triangle.a)
        assertEquals(PreviewVertex(16f,26f,38f),triangle.b)
        assertEquals(PreviewVertex(14f,28f,38f),triangle.c)
    }

    @Test fun malformedVerticesAndTrianglesNeverProduceInvalidPreviewGeometry() {
        for (attrs in listOf("y=\"0\" z=\"0\"","x=\"bad\" y=\"0\" z=\"0\"",
            "x=\"0\" z=\"0\"","x=\"0\" y=\"bad\" z=\"0\"","x=\"0\" y=\"0\"","x=\"0\" y=\"0\" z=\"bad\"")) {
            assertNull(previewMesh("""<object id="1"><mesh><vertex $attrs/><triangle v1="0" v2="0" v3="0"/></mesh></object>"""))
        }
        val invalid = listOf("v2=\"1\" v3=\"2\"", "v1=\"0\" v3=\"2\"", "v1=\"0\" v2=\"1\"",
            "v1=\"99\" v2=\"1\" v3=\"2\"", "v1=\"0\" v2=\"99\" v3=\"2\"", "v1=\"0\" v2=\"1\" v3=\"99\"")
        val vertices=mesh.substringBefore("<triangles>")
        for (attrs in invalid) {
            assertNull(previewMesh("""<object id="1">$vertices<triangles><triangle $attrs/></triangles></mesh></object>"""))
        }
    }

    @Test fun previewSizeIsBoundedEvenWhenAComponentRepeatsALargeMesh() {
        val vertices=mesh.substringBefore("<triangles>")
        val triangles="""<triangle v1="0" v2="1" v3="2" p1="3"/>""".repeat(20_001)
        val xml="""<model><resources><object id="1">$vertices<triangles>$triangles</triangles></mesh></object>
            <object id="2"><components><component objectid="1"/><component objectid="1"/></components></object>
            </resources><build><item objectid="2"/><item objectid="1"/></build></model>"""
        val preview=previewMesh(xml)!!
        assertEquals(20_000,preview.triangles.size)
        assertTrue(preview.triangles.all { it.material==3 })
    }

    @Test fun attributeNamesCannotBeConfusedWithASuffixOrUnquotedValues() {
        for (attrs in listOf("otherid=\"2\" id='1'","id=invalid id='1'","id=\"unterminated id='1'")) {
            assertEquals(1,previewMesh("""<object $attrs>$mesh</object>""")?.triangles?.size)
        }
    }
    @Test fun truncatedObjectsMeshesAndTagsNeverProducePartialGeometry() {
        val samples=listOf("<object id='1'", "<object id='1'>$mesh", "<object id='1'><mesh</object>",
            "<object id='1'><mesh><vertex x='0' y='0' z='0'/><triangle v1='0' v2='0' v3='0'</mesh></object>")
        samples.forEach { assertNull(previewMesh(it),it) }
    }

    @Test fun similarlyNamedTagsCannotShiftTheVertexIndicesOrReplaceAMesh() {
        val valid=mesh.replace("<vertices>","<vertices><vertex_extra x='99' y='99' z='99'/>")
            .replace("<triangles>","<triangles><triangle_extra v1='0' v2='0' v3='0'/>")
            .replace("<mesh>","<mesh >")
        val result=previewMesh("<object id='1'><mesh_notes>unrelated</mesh_notes>$valid</object>")!!
        assertEquals(1,result.triangles.size)
        assertEquals(PreviewVertex(1f,0f,0f),result.triangles.single().b)
        assertEquals(PreviewVertex(0f,1f,0f),result.triangles.single().c)
    }

}
