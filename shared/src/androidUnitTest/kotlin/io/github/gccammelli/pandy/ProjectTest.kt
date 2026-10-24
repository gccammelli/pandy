package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectTest {
    private fun archive(vararg entries: Pair<String,String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, text) ->
            z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
        } }
    }.toByteArray()
    private fun project(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","filament_type":["PLA","PETG"],"filament_colour":["#FFFFFF","#00AE42"]}""".toByteArray())
            put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Body"/><metadata key="bed_type" value="Textured PEI Plate"/></plate><plate><metadata key="plater_id" value="2"/><metadata key="plater_name" value=""/><metadata key="curr_bed_type" value="Cool Plate"/></plate></config>""".toByteArray())
            put("Metadata/plate_1.png", byteArrayOf(1, 2, 3))
            put("3D/3dmodel.model", """<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2" p1="1"/></triangles></mesh></object></resources></model>""".toByteArray())
        }
    }.toByteArray()

    @Test fun readsPlatesPrinterAndFilaments() = runTest {
        val p = readProject(project())
        assertEquals(listOf(1, 2), p.plates.map { it.index })
        assertEquals("Body", p.plates[0].name)
        assertEquals("Textured PEI Plate", p.plates[0].bedType)
        assertEquals("Cool Plate", p.plates[1].bedType)
        assertEquals(3, p.plates[0].picture?.size); assertEquals(null, p.plates[1].picture)
        assertEquals("X1C", shortModel(p.printerModel))
        assertEquals(listOf("PLA" to "#FFFFFF", "PETG" to "#00AE42"), p.filaments)
        assertEquals(false, p.sliced)
        assertEquals(1, p.mesh?.triangles?.size); assertEquals(1, p.mesh?.triangles?.single()?.material)
    }

    @Test fun encodesNames() {
        assertEquals("Cube%20%2B%20Sphere%20%C3%A9", "Cube + Sphere é".encodeURLParam())
    }

    @Test fun followsComponentModelsAndReadsSliceInfo() = runTest {
        val data = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","curr_bed_type":"Cool Plate"}""")
                put("Metadata/model_settings.config", """<config><object id="2"><metadata key="extruder" value="2"/></object><plate><metadata key="plater_id" value="1"/><model_instance><metadata key="object_id" value="2"/></model_instance></plate></config>""")
                put("Metadata/slice_info.config", """<config><plate><metadata key="index" value="1"/><metadata key="prediction" value="42"/><metadata key="weight" value="1.5"/><object identify_id="1" skipped="false"/></plate></config>""")
                put("3D/3dmodel.model", """<model><resources><object id="2"><components><component p:path="/3D/Objects/object_1.model" objectid="1" transform="0 1 0 -1 0 0 0 0 1 1 2 3"/></components></object></resources><build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 10 20 30"/></build></model>""")
                put("3D/Objects/object_1.model", """<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources></model>""")
            }
        }.toByteArray()
        val p = readProject(data)
        assertEquals("Cool Plate", p.plates.single().bedType)
        assertEquals(42, p.plates.single().seconds); assertEquals(1.5, p.plates.single().grams); assertEquals(1, p.plates.single().parts)
        assertEquals(1, p.meshes[1]?.triangles?.size)
        assertEquals(11f, p.meshes[1]?.triangles?.single()?.a?.x)
        assertEquals(22f, p.meshes[1]?.triangles?.single()?.a?.y)
        assertEquals(33f, p.meshes[1]?.triangles?.single()?.a?.z)
        assertEquals(11f, p.meshes[1]?.triangles?.single()?.b?.x)
        assertEquals(23f, p.meshes[1]?.triangles?.single()?.b?.y)
        assertEquals(10f, p.meshes[1]?.triangles?.single()?.c?.x)
        assertEquals(22f, p.meshes[1]?.triangles?.single()?.c?.y)
        assertEquals(1, p.meshes[1]?.triangles?.single()?.material)
    }

    @Test fun readsSliceDetailsWhenPresent() = runTest {
        val data = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","layer_height":0.2,"wall_loops":6,"sparse_infill_density":0.25,"filament_type":["PLA"],"filament_colour":["#FFFFFF"]}""")
                put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>""")
                put("Metadata/plate_1.gcode", "; total estimated printing time = 01:02:03\n; total filament used [g] = 19.0\n; total number of objects = 1\n")
                put("Metadata/plate_2.gcode", "; total estimated printing time (normal mode) = 00h44m00s\n")
            }
        }.toByteArray()
        val p = readProject(data)
        assertEquals(3723, p.plates[0].seconds)
        assertEquals(2640, p.plates[1].seconds)
        assertEquals(19.0, p.plates[0].grams)
        assertEquals(1, p.plates[0].parts)
        assertEquals("0.2 mm layer, 6 walls, 25% infill", p.profileSummary)
    }

    @Test fun rejectsNonProjectsAndKeepsMissingOrMalformedMetadataUnknown() = runTest {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3,4),byteArrayOf(80,1,2,3))) {
            assertFailsWith<ApiException> { readProject(bytes) }
        }
        for (settings in listOf(null,"not json","[]","{}")) {
            val entries = settings?.let { arrayOf("Metadata/project_settings.config" to it) } ?: emptyArray()
            val p = readProject(archive(*entries))
            assertEquals(listOf(ProjectPlate(1,"",null)),p.plates)
            assertNull(p.printerModel); assertNull(p.profileName); assertNull(p.profileSummary)
            assertNull(p.mesh); assertEquals(emptyMap(),p.meshes); assertEquals(emptyList(),p.filaments)
            assertEquals(false,p.sliced)
        }
    }

    @Test fun partialSettingsUseNeutralFilamentColoursAndOnlyValidProfileDetails() = runTest {
        val p = readProject(archive("Metadata/project_settings.config" to """{
            "printer_model":{},"profile_name":" ","layer_height":"?","wall_loops":"?","sparse_infill_density":"?",
            "filament_type":[null,{},"PLA","PETG"],"filament_colour":["#FFFFFF"]}"""))
        assertNull(p.printerModel); assertNull(p.profileName); assertNull(p.profileSummary)
        assertEquals(listOf("PLA" to "#FFFFFF","PETG" to "#888888"),p.filaments)
        for (density in listOf("25%","25")) {
            val named = readProject(archive("Metadata/project_settings.config" to """{"profile_name":"Draft","sparse_infill_density":"$density"}"""))
            assertEquals("Draft",named.profileName); assertEquals("25% infill",named.profileSummary)
        }
    }

    @Test fun sliceInfoDistinguishesUnmatchedPlatesAndSkippedPartsFromMissingMetrics() = runTest {
        val p=readProject(archive(
            "Metadata/model_settings.config" to """<config><plate><metadata/><metadata key="plater_id" value="bad"/></plate><plate><metadata key="plater_id" value="1"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>""",
            "Metadata/slice_info.config" to """<config><plate><metadata key="index" value="1"/><metadata key="prediction" value="?"/><metadata key="weight"/><object skipped="true"/></plate><plate><metadata key="index" value="bad"/></plate></config>"""))
        assertEquals(listOf(1,2),p.plates.map { it.index })
        assertEquals(true,p.sliced)
        assertEquals(true,p.plates.all { it.seconds==null && it.grams==null && it.parts==null })
        val md5=readProject(archive("Metadata/plate_1.gcode.md5" to "digest"))
        assertEquals(true,md5.sliced)
    }

    @Test fun gcodeMetricsAcceptMinuteOnlyAndSecondOnlyTimesAndIgnoreInvalidNumbers() = runTest {
        for ((text,seconds) in listOf("estimated time = 7m" to 420,"estimated time = 9s" to 9,
            "estimated time = 1h" to 3600,"estimated time = unknown" to null)) {
            val p=readProject(archive("Metadata/plate_1.gcode" to "$text\n; filament used [g] = ..\n; number of objects = 2\n"))
            assertEquals(seconds,p.plates.single().seconds)
            assertNull(p.plates.single().grams); assertEquals(2,p.plates.single().parts)
        }
    }

    @Test fun componentPreviewUsesPlateSelectionAndIgnoresMissingComponentFiles() = runTest {
        val vertices="""<mesh><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><triangle v1="0" v2="1" v3="2"/></mesh>"""
        val p=readProject(archive(
            "Metadata/model_settings.config" to """<config><object id="bad"></object><object id="1"><metadata key="extruder" value="2"/></object><object id="2"></object>
                <plate><metadata key="plater_id" value="1"/><model_instance><metadata key="object_id" value="1"/></model_instance><model_instance/></plate>
                <plate><metadata key="plater_id" value="2"/><model_instance><metadata key="object_id" value="2"/></model_instance></plate></config>""",
            "3D/3dmodel.model" to """<model><resources><object id="1">$vertices</object><object id="2"><components><component p:path="/3D/missing.model" objectid="1"/></components></object></resources>
                <build><item objectid="1"/><item objectid="2"/></build></model>"""))
        assertEquals(setOf(1),p.meshes.keys)
        assertEquals(1,p.meshes.getValue(1).triangles.single().material)
        assertEquals(PreviewVertex(1f,0f,0f),p.mesh!!.triangles.single().b)
    }

    @Test fun previewMeshesLoadSeparatelyAndScanTagsWithoutRegexes() = runTest {
        fun archive(vararg entries: Pair<String, String>) = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> entries.forEach { (name, text) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        }.toByteArray()
        val model = """<model><resources>
            <object id="9"/>
            <object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="bad" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices>
            <triangles><triangle v1="0" v2="1" v3="3" p1="1"/><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object>
            <object id="2"></object><object id="x"><mesh></mesh></object><meshy/></resources><build><item objectid="1"/></build></model>"""
        val data = archive("3D/3dmodel.model" to model, "Metadata/model_settings.config" to "<config><plate><metadata key=\"plater_id\" value=\"1\"/></plate></config>")
        val quick = readProject(data, preview = false)
        assertEquals(emptyMap(), quick.meshes); assertNull(quick.mesh)
        val meshes = readPreviewMeshes(data)
        // The malformed vertex keeps its index slot, so the triangle that uses it is skipped rather than shifted onto another vertex.
        assertEquals(1, meshes.getValue(1).triangles.size)
        assertEquals(1, meshes.getValue(1).triangles.single().material)
        assertEquals(meshes, readProject(data).meshes)
        assertEquals(emptyMap(), readPreviewMeshes(archive("Metadata/model_settings.config" to "<config/>")))

    }
    @Test fun oversizedPreviewDoesNotPreventReadingThePrintProfile() = runTest {
        val bytes=archive("3D/3dmodel.model" to ("<model>"+" ".repeat(8*1024*1024)+"</model>"),
            "Metadata/project_settings.config" to """{"profile_name":"Large assembly","printer_model":"Bambu Lab P1S"}""")
        val profile=readProject(bytes,preview=false)
        assertEquals("Large assembly",profile.profileName); assertEquals("Bambu Lab P1S",profile.printerModel)
        assertEquals(1,profile.plates.size); assertNull(profile.mesh)
        assertEquals(emptyMap(),readPreviewMeshes(bytes))
    }

}

class PresetTest {
    @Test fun presets() {
        kotlin.test.assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", printerPreset("X1C", "0.4"))
        kotlin.test.assertEquals("Bambu Lab A1 mini 0.2 nozzle", printerPreset("A1 mini", "0.2"))
        kotlin.test.assertEquals("Bambu Lab P1S 0.4 nozzle", printerPreset("P1S", null))
    }

    @Test fun buildPlateAndFilamentKeysMatchPrinterValues() {
        kotlin.test.assertEquals("eng_plate", buildPlateCode("Engineering Plate"))
        kotlin.test.assertEquals("hot_plate", buildPlateCode("Smooth PEI Plate / High Temp Plate"))
        kotlin.test.assertEquals("Cool Plate SuperTack", buildPlateLabel("Supertack Plate"))
        kotlin.test.assertEquals(filamentKey("PLA", "#ffffff"), filamentKey(" pla ", "FFFFFFFF"))
    }

    @Test fun namesPreserveNewPrinterModelsAndDefaultInvalidNozzleSizes() {
        assertNull(shortModel(null))
        for (model in listOf("X1","X1E","P1S","P1P","P2S","A1","A1 mini","H2D","H2S","Future")) {
            assertEquals(model,shortModel("Bambu Lab $model"))
        }
        assertEquals("Bambu Lab P1S 1.0 nozzle",printerPreset("P1S","1"))
        assertEquals("Bambu Lab P1S 0.4 nozzle",printerPreset("P1S","unknown"))
    }
}
