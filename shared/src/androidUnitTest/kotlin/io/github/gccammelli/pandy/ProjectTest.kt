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
            put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Body"/></plate><plate><metadata key="plater_id" value="2"/><metadata key="plater_name" value=""/></plate></config>""".toByteArray())
            put("Metadata/plate_1.png", byteArrayOf(1, 2, 3))
        }
    }.toByteArray()

    @Test fun readsPlatesPrinterAndFilaments() = runTest {
        val p = readProject(project())
        assertEquals(listOf(1, 2), p.plates.map { it.index })
        assertEquals("Body", p.plates[0].name)
        assertEquals(3, p.plates[0].picture?.size); assertEquals(null, p.plates[1].picture)
        assertEquals("X1C", shortModel(p.printerModel))
        assertEquals(listOf("PLA" to "#FFFFFF", "PETG" to "#00AE42"), p.filaments)
        assertEquals(false, p.sliced)
    }

    @Test fun encodesNames() {
        assertEquals("Cube%20%2B%20Sphere%20%C3%A9", "Cube + Sphere é".encodeURLParam())
    }
}
