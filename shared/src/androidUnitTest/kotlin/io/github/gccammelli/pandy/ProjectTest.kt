package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTest {
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
}
