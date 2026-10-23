package io.github.gccammelli.pandy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SlicerClientTest {
    private val printer = LanConfig("192.0.2.1", "TEST123", "private-code", "X1C")
    private class Storage : PrinterStorage {
        var written: Pair<String, ByteArray>? = null
        override suspend fun list(dir: String) = emptyList<FileEntry>()
        override suspend fun read(path: String, offset: Long, length: Int) = byteArrayOf()
        override suspend fun write(path: String, data: ByteArray, progress: (Float) -> Unit) { written = path to data }
    }
    private class Supervisor(override val base: String) : HaBackend {
        override suspend fun get(path: String) = error("unused")
        override suspend fun post(path: String, body: String) = error("unused")
        override suspend fun bytes(path: String) = error("unused")
        override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) = error("unused")
        override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map { c -> json.parseToJsonElement(when(c.s("endpoint")) {
            "/addons" -> """{"addons":[{"slug":"pandy_slicer"}]}"""
            "/addons/pandy_slicer/info" -> """{"state":"started","ingress_entry":"/ingress/slicer"}"""
            "/ingress/session" -> """{"session":"session-cookie"}"""
            else -> error("unexpected request")
        }) }
    }
    private val info = """{"plates":[{"index":2,"seconds":123,"grams":4.5,"filaments":[{"type":"PLA","grams":4.5}]}]}"""

    @Test fun directSlicerNeverReceivesPrinterCredentialsAndUploadsLocally() = runTest {
        val storage = Storage()
        HttpClient(MockEngine { request ->
            assertEquals("Bearer slicer-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Printer-Code"]); assertNull(request.headers["X-Printer-Ip"])
            assertEquals("a model.3mf", request.url.parameters["name"])
            assertEquals("2", request.url.parameters["plate"])
            assertEquals("auto", request.url.parameters["bed_type"])
            respond(byteArrayOf(1,2,3), headers = headersOf(
                "Content-Disposition" to listOf("attachment; filename=\"result.gcode.3mf\""), "X-Pandy-Result" to listOf(info)))
        }).use { http ->
            val client = SlicerClient("slicer.local", "slicer-token", http = http, storageFactory = { storage })
            val (path,plate) = client.sliceAndUpload(byteArrayOf(9),2,"a model.3mf",printer, "X1C")
            assertEquals("cache/result.gcode.3mf",path)
            assertEquals(123,plate!!.seconds); assertEquals(4.5,plate.grams); assertEquals("PLA",plate.filaments.single()["type"])
            assertEquals("/cache/result.gcode.3mf",storage.written!!.first); assertContentEquals(byteArrayOf(1,2,3),storage.written!!.second)
        }
    }

    @Test fun onlyHttpsHomeAssistantIngressCanReceivePrinterCredentials() = runTest {
        for (secure in listOf(true,false)) {
            val storage = Storage()
            HttpClient(MockEngine { request ->
                assertEquals("ingress_session=session-cookie", request.headers[HttpHeaders.Cookie])
                assertNull(request.headers[HttpHeaders.Authorization])
                assertEquals(if(secure) "private-code" else null, request.headers["X-Printer-Code"])
                if(secure) {
                    assertEquals("1",request.url.parameters["upload"])
                    respond("""{"path":"cache/remote.3mf",${info.drop(1)}""")
                } else respond(byteArrayOf(8,7))
            }).use { http ->
                val client = SlicerClient(null,null,Supervisor(if(secure) "https://ha.example" else "http://ha.example"),http, { storage })
                val result = client.sliceAndUpload(byteArrayOf(9),2,"model.3mf",printer)
                if(secure) { assertEquals("cache/remote.3mf",result.first); assertNull(storage.written) }
                else { assertEquals("cache/model.gcode.3mf",result.first); assertNotNull(storage.written) }
            }
        }
    }

    @Test fun healthAndSliceFailuresRemainExplicit() = runTest {
        for ((status,body) in listOf(200 to """{"version":"2.0"}""",401 to "{}",500 to "not json",200 to "not json")) {
            HttpClient(MockEngine { respond(body,HttpStatusCode.fromValue(status)) }).use { http ->
                val client = SlicerClient("slicer.local:8737","token",http=http)
                if(status==200 && body.startsWith("{")) assertEquals("2.0",client.health())
                else assertFailsWith<ApiException> { client.health() }
            }
        }
        for ((status,body) in listOf(500 to """{"error":"Slice failed"}""",503 to "unavailable")) {
            HttpClient(MockEngine { respond(body,HttpStatusCode.fromValue(status)) }).use { http ->
                val client = SlicerClient("slicer.local",null,http=http)
                assertFailsWith<ApiException> { client.sliceAndUpload(byteArrayOf(1),1,"m.3mf",printer) }
            }
        }
    }
}
