package io.github.gccammelli.pandy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlicerSettingsTest {
    private val schema = """{"version":1,"settings":[
      {"group":"Quality","key":"layer_height","label":"Layer height","type":"number","unit":"mm","min":0.05,"max":0.6},
      {"group":"Support","key":"enable_support","label":"Generate support","type":"choice","options":[{"value":"1","label":"On"},{"value":"0","label":"Off"}]}]}"""
    private val config = LanConfig("192.0.2.1", "TEST", "code", "X1C")
    private class Storage : PrinterStorage {
        var writes = 0
        override suspend fun list(dir: String) = emptyList<FileEntry>()
        override suspend fun read(path: String, offset: Long, length: Int) = byteArrayOf()
        override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) { writes++ }
    }

    @Test fun schemaDescribesOnlyValidValues() {
        val settings = parseSlicerSettings(json.parseToJsonElement(schema).jsonObject)
        assertTrue(settings[0].valid("0.12")); assertTrue(settings[0].valid(".2"))
        for (value in listOf("0", "0.0001", "nan", "1", "0.2 --no-check", "٠.١")) assertFalse(settings[0].valid(value))
        assertEquals("0.12 mm", settings[0].display("0.12"))
        assertTrue(settings[1].valid("0")); assertFalse(settings[1].valid("false"))
        assertEquals("On", settings[1].display("1"))
        for (bad in listOf("{}", """{"version":2,"settings":[]}""", """{"version":1,"settings":[]}""",
            schema.replace("layer_height", "../bad"), schema.replace("\"max\":0.6", "\"max\":-1"),
            schema.replace("\"value\":\"0\"", "\"value\":\"1\""))) {
            assertFailsWith<ApiException> { parseSlicerSettings(json.parseToJsonElement(bad).jsonObject) }
        }
    }

    @Test fun numericAndChoiceSchemasRejectMalformedData() {
        val integer = SlicerSetting("Strength", "wall_loops", "Wall loops", "integer", min = 1.0, max = 20.0)
        assertTrue(integer.valid("4")); assertEquals("4", integer.display("4"))
        for (value in listOf("0", "21", "-1", "1.5", "٤", "abc", "1".repeat(33))) assertFalse(integer.valid(value))
        assertFalse(integer.copy(min = null).valid("4"))
        assertFalse(integer.copy(max = null).valid("4"))
        assertFalse(integer.copy(type = "unknown").valid("4"))
        val choice = SlicerSetting("Support", "enable_support", "Support", "choice", options = listOf(SlicerChoice("1", "On")))
        assertEquals("On", choice.display("1")); assertEquals("0", choice.display("0"))
        val bad = listOf(
            """{"version":1,"settings":[null]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"unknown"}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"integer","min":2,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","min":0}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"choice","options":[]}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"choice","options":[null]}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","unit":"too-long-a-unit","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","min":0,"max":1},{"group":"Q","key":"a","label":"B","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":{}}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"choice"}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"choice","options":[{"value":"1"}]}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","min":"NaN","max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","min":0,"max":"Infinity"}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","min":-1,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"number","max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q\nQ","key":"a","label":"A","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q\rQ","key":"a","label":"A","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"${"x".repeat(81)}","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","label":"A","type":"number","min":0,"max":1}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","min":0,"max":1}]}""",
            """{"version":1,"settings":[${List(101) { "null" }.joinToString(",")}]}""",
            """{"version":1,"settings":[{"group":"Q","key":"a","label":"A","type":"choice","options":[${List(31) { "null" }.joinToString(",")}]}]}""",
        )
        for (source in bad) assertFailsWith<ApiException> { parseSlicerSettings(json.parseToJsonElement(source).jsonObject) }
        assertFalse(settingsConfirmed(null, mapOf("wall_loops" to "4")))
        assertTrue(settingsConfirmed(null, emptyMap()))
        val wrong = json.parseToJsonElement("""{"applied_settings":{"wall_loops":{}}}""").jsonObject
        assertFalse(settingsConfirmed(wrong, mapOf("wall_loops" to "4")))
        val extra = json.parseToJsonElement("""{"applied_settings":{"wall_loops":"4","other":"1"}}""").jsonObject
        assertFalse(settingsConfirmed(extra, mapOf("wall_loops" to "4")))
    }

    @Test fun settingsEndpointReportsAuthorizationServerAndMalformedResponseErrors() = runTest {
        for ((status, body, expected) in listOf(
            Triple(HttpStatusCode.Unauthorized, "{}", "different token"),
            Triple(HttpStatusCode.ServiceUnavailable, "{}", "slicer"),
            Triple(HttpStatusCode.OK, "not JSON", "unsupported settings"),
        )) {
            HttpClient(MockEngine { respond(body, status) }).use { http ->
                val error = assertFailsWith<ApiException> { SlicerClient("slicer.local", "token", http = http).settings() }
                assertTrue(error.message!!.contains(expected, ignoreCase = true))
            }
        }
    }

    @Test fun advancedSettingsAreConfirmedBeforeUploading() = runTest {
        val storage = Storage()
        val paths = mutableListOf<String>()
        val values = mapOf("layer_height" to "0.12", "enable_support" to "1")
        HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("Bearer token", request.headers["Authorization"])
            if (request.url.encodedPath == "/settings") respond(schema)
            else {
                val sent = json.parseToJsonElement(request.headers["X-Pandy-Slicer-Settings"]!!).jsonObject
                assertEquals("0.12", sent.s("layer_height")); assertEquals("1", sent.s("enable_support"))
                assertNull(request.headers["X-Printer-Code"])
                respond(byteArrayOf(1), headers = headersOf("X-Pandy-Result", """{"applied_settings":{"layer_height":"0.12","enable_support":"1"}}"""))
            }
        }).use { http ->
            SlicerClient("slicer.local", "token", http = http, storageFactory = { storage })
                .sliceAndUpload(byteArrayOf(1), 1, "cube", config, settings = values)
        }
        assertEquals(listOf("/settings", "/slice"), paths)
        assertEquals(1, storage.writes)
    }

    @Test fun oldSlicersAndUnconfirmedOrInvalidSettingsCannotUpload() = runTest {
        for (case in listOf("old", "missing", "different", "invalid")) {
            val storage = Storage()
            val paths = mutableListOf<String>()
            HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath == "/settings") {
                    if (case == "old") respond("{}", HttpStatusCode.NotFound) else respond(schema)
                } else {
                    val response = if (case == "different") """{"applied_settings":{"layer_height":"0.2"}}""" else "{}"
                    respond(byteArrayOf(1), headers = headersOf("X-Pandy-Result", response))
                }
            }).use { http ->
                val value = if (case == "invalid") "999" else "0.12"
                val error = assertFailsWith<ApiException> {
                    SlicerClient("slicer.local", null, http = http, storageFactory = { storage })
                        .sliceAndUpload(byteArrayOf(1), 1, "cube", config, settings = mapOf("layer_height" to value))
                }
                if (case == "old") assertTrue(error.message!!.contains("Update Pandy Slicer"))
                if (case == "old" || case == "invalid") assertEquals(listOf("/settings"), paths)
                else assertEquals(listOf("/settings", "/slice"), paths)
            }
            assertEquals(0, storage.writes)
        }
    }

    @Test fun replacingTheSlicerRevalidatesThePreviouslyChosenSettings() = runTest {
        val storage = Storage()
        val paths = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.host == "first.local") respond(schema)
            else respond("""{"version":1,"settings":[{"group":"Strength","key":"wall_loops","label":"Wall loops","type":"integer","min":1,"max":20}]}""")
        }).use { http ->
            val first = SlicerClient("first.local", null, http = http, storageFactory = { storage })
            assertTrue(first.settings().first().valid("0.12"))
            val replacement = SlicerClient("second.local", null, http = http, storageFactory = { storage })
            assertFailsWith<ApiException> {
                replacement.sliceAndUpload(byteArrayOf(1), 1, "cube", config, settings = mapOf("layer_height" to "0.12"))
            }
        }
        assertEquals(listOf("/settings", "/settings"), paths)
        assertEquals(0, storage.writes)
    }

    @Test fun secureIngressAlsoRequiresTheSlicerToConfirmSettings() = runTest {
        val supervisor = object : HaBackend {
            override val base = "https://ha.example"
            override suspend fun get(path: String) = error("unused")
            override suspend fun post(path: String, body: String) = error("unused")
            override suspend fun bytes(path: String) = error("unused")
            override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) = error("unused")
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map {
                json.parseToJsonElement(when (it.s("endpoint")) {
                    "/addons" -> """{"addons":[{"slug":"pandy_slicer"}]}"""
                    "/addons/pandy_slicer/info" -> """{"state":"started","ingress_entry":"/ingress/slicer"}"""
                    else -> """{"session":"session-cookie"}"""
                })
            }
        }
        for (confirmed in listOf(true, false)) {
            HttpClient(MockEngine { request ->
                assertEquals("ingress_session=session-cookie", request.headers["Cookie"])
                if (request.url.encodedPath.endsWith("/settings")) respond(schema)
                else {
                    assertEquals("code", request.headers["X-Printer-Code"])
                    assertEquals("0.12", json.parseToJsonElement(request.headers["X-Pandy-Slicer-Settings"]!!).jsonObject.s("layer_height"))
                    respond(if (confirmed) """{"path":"cache/cube.gcode.3mf","applied_settings":{"layer_height":"0.12"}}"""
                            else """{"path":"cache/cube.gcode.3mf"}""")
                }
            }).use { http ->
                val client = SlicerClient(null, null, supervisor, http, { error("Secure ingress uploads on the server") })
                if (confirmed) assertEquals("cache/cube.gcode.3mf", client.sliceAndUpload(byteArrayOf(1), 1, "cube", config, settings = mapOf("layer_height" to "0.12")).first)
                else assertFailsWith<ApiException> { client.sliceAndUpload(byteArrayOf(1), 1, "cube", config, settings = mapOf("layer_height" to "0.12")) }
            }
        }
    }
}
