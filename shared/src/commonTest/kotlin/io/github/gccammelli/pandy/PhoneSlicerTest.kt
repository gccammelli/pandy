package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PhoneSlicerTest {
    private val result = """{"version":1,"source_plate":2,"printer":"printer","bed_type":"textured","project_preserved":true,"output_plate":1,"filaments":[{"id":1,"type":"PLA","color":"#AABBCC"}],"applied_settings":{"wall_loops":"3"}}"""
    private val file = zipStored(listOf("Metadata/plate_1.gcode" to "G28\nG29\n".encodeToByteArray()))

    @Test fun requestIncludesPrintChoicesAndNoCredentials() {
        val request = phoneSliceRequest(2, "model", "printer", "textured", "PLA", "#ffffff", mapOf("wall_loops" to "3"))
        assertEquals("2", json.parseToJsonElement(request).let { (it as kotlinx.serialization.json.JsonObject).s("plate") })
        assertFalse(request.contains("access_code")); assertFalse(request.contains("token")); assertFalse(request.contains("cookies"))
    }

    @Test fun checksSettingsAndPlateBeforeAcceptingFile() = runTest {
        suspend fun accept(metadata: String, bytes: ByteArray?, settings: Map<String, String> = emptyMap()) = phoneSliceResult(PhoneSlicerResponse(metadata, bytes), settings, 2, "printer", "textured")
        val (_, plate) = accept(result, file, mapOf("wall_loops" to "3"))
        assertEquals(1, plate.index); assertEquals("PLA", plate.filaments.single()["type"])
        assertFailsWith<ApiException> { accept(result, file, mapOf("wall_loops" to "4")) }
        assertFailsWith<ApiException> { accept(result.replace("\"version\":1", "\"version\":2"), file) }
        assertFailsWith<ApiException> { accept(result.replace("\"project_preserved\":true", "\"project_preserved\":false"), file) }
        assertFailsWith<ApiException> { accept(result.replace("\"source_plate\":2", "\"source_plate\":1"), file) }
        assertFailsWith<ApiException> { accept(result.replace("\"printer\":\"printer\"", "\"printer\":\"other\""), file) }
        assertFailsWith<ApiException> { accept(result.replace("#AABBCC", "#AABBCCC"), file) }
        assertFailsWith<ApiException> { accept(result, null) }
        assertFailsWith<ApiException> { accept(result, byteArrayOf(1, 2, 3)) }
        assertFailsWith<ApiException> { accept(result, zipStored(listOf("Metadata/plate_1.gcode" to "G28\nM104 S400\n".encodeToByteArray()))) }
        assertFailsWith<ApiException> { accept(" ".repeat(128_001) + result, file) }
        assertFailsWith<ApiException> { accept(result.replace("\"output_plate\":1", "\"output_plate\":2"), file) }
        assertFailsWith<ApiException> { accept(result.replace("\"filaments\":[{\"id\":1,\"type\":\"PLA\",\"color\":\"#AABBCC\"}]", "\"filaments\":[]"), file) }
    }
}
