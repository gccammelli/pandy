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

    @Test fun aPlainModelsFilamentTypeAndColourReachTheSlicerOnlyWhenChosen() = runTest {
        val seen = mutableListOf<io.ktor.http.Parameters>()
        HttpClient(MockEngine { request ->
            seen += request.url.parameters
            respond(byteArrayOf(1), headers = headersOf("Content-Disposition" to listOf("attachment; filename=\"r.gcode.3mf\"")))
        }).use { http ->
            val client = SlicerClient("slicer.local", null, http = http, storageFactory = { Storage() })
            client.sliceAndUpload(byteArrayOf(9), 1, "m.stl", printer, "X1C", "textured_plate", "PETG", "#D32F2F")
            client.sliceAndUpload(byteArrayOf(9), 1, "m.3mf", printer, "X1C", "auto", " ", null)
        }
        assertEquals("PETG", seen[0]["filament_type"]); assertEquals("#D32F2F", seen[0]["filament_color"]); assertEquals("textured_plate", seen[0]["bed_type"])
        assertNull(seen[1]["filament_type"]); assertNull(seen[1]["filament_color"])
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

    @Test fun normalizesAddressesAndPrefersAnExplicitSlicerOverIngress() = runTest {
        for ((address, expected) in listOf(
            " slicer.local/ " to "http://slicer.local:8737",
            "https://slicer.example/" to "https://slicer.example:8737",
            "http://slicer.local:9000/" to "http://slicer.local:9000")) {
            HttpClient(MockEngine { request ->
                assertEquals("$expected/health", request.url.toString())
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                respond("{}")
            }).use { http ->
                val client = SlicerClient(address," ",Supervisor("https://ha.example"),http)
                assertFalse(client.viaHa)
                assertEquals("?",client.health())
            }
        }
        HttpClient(MockEngine { error("An unset slicer must not send a request") }).use { http ->
            assertEquals("Set up Pandy Slicer in Me › Settings",
                assertFailsWith<ApiException> { SlicerClient(null,null,http=http).health() }.message)
        }
    }

    @Test fun ingressHealthFailuresExplainTheHomeAssistantProblem() = runTest {
        for ((status, body, message) in listOf(
            Triple(401,"{}","Home Assistant didn't let Pandy in to the slicer"),
            Triple(500,"{}","The Pandy Slicer add-on didn't answer: is it started?"),
            Triple(200,"not json","The Pandy Slicer add-on didn't answer: is it started?"))) {
            HttpClient(MockEngine { respond(body,HttpStatusCode.fromValue(status)) }).use { http ->
                val client = SlicerClient(null,null,Supervisor("https://ha.example"),http)
                assertTrue(client.viaHa)
                assertEquals(message,assertFailsWith<ApiException> { client.health() }.message)
            }
        }
    }

    @Test fun secureIngressRequiresAnExplicitUploadedPathAndValidResponse() = runTest {
        for ((status, body, message) in listOf(
            Triple(500,"""{"error":"Printer unavailable"}""","Printer unavailable"),
            Triple(503,"unavailable","Pandy Slicer isn't answering (HTTP 503). If its add-on was just updated or restarted, give it a minute; otherwise check the add-on's log."),
            Triple(502,"<html>Bad Gateway</html>","Pandy Slicer isn't answering (HTTP 502). If its add-on was just updated or restarted, give it a minute; otherwise check the add-on's log."),
            Triple(502,"""{"error":"Couldn't put the file on the printer: login failed"}""","Couldn't put the file on the printer: login failed"),
            Triple(418,"teapot","The slicer answered HTTP 418"),
            Triple(200,"not json","The slicer answered HTTP 200"),
            Triple(200,"{}","The slicer didn't say where it put the file"))) {
            HttpClient(MockEngine { respond(body,HttpStatusCode.fromValue(status)) }).use { http ->
                val client = SlicerClient(null,null,Supervisor("https://ha.example"),http,
                    storageFactory={ error("Secure ingress must not upload a second copy") })
                assertEquals(message,assertFailsWith<ApiException> {
                    client.sliceAndUpload(byteArrayOf(1),3,"m.3mf",printer)
                }.message)
            }
        }
    }

    @Test fun sparsePlateMetadataKeepsTheRequestedPlateAndUnknownMetrics() = runTest {
        val plates = listOf(
            "{}" to null,
            """{"plates":{}}""" to null,
            """{"plates":[]}""" to null,
            """{"plates":[null]}""" to null,
            """{"plates":[{}]}""" to SlicedPlate(3,0,0.0,emptyList()),
            """{"plates":[{"index":"bad","seconds":"?","grams":"?","filaments":[null,{}, {"type":"PLA","grams":null,"details":{}}]}]}""" to
                SlicedPlate(3,0,0.0,listOf(emptyMap(),mapOf("type" to "PLA","grams" to "","details" to ""))),
            """{"plates":[{"filaments":{}}]}""" to SlicedPlate(3,0,0.0,emptyList()))
        for ((metadata,expected) in plates) {
            val storage = Storage()
            HttpClient(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers["X-Printer-Code"])
                respond(byteArrayOf(7),headers=headersOf("X-Pandy-Result",metadata))
            }).use { http ->
                val client = SlicerClient("slicer.local",null,http=http,storageFactory={ storage })
                assertEquals("cache/model.gcode.3mf" to expected,
                    client.sliceAndUpload(byteArrayOf(1),3,"m.3mf",printer))
                assertContentEquals(byteArrayOf(7),storage.written!!.second)
            }
        }
    }

    @Test fun connectionMessageNamesTheRouteThatAnswered() {
        assertEquals("Connected through the Home Assistant add-on: Bambu Studio 2.8", slicerConnected(true, "2.8"))
        assertEquals("Connected: Bambu Studio 2.8", slicerConnected(false, "2.8"))
    }

    @Test fun failureTextPrefersTheMessage() {
        assertEquals("Couldn't put the file on the printer: refused", failureText(ApiException("Couldn't put the file on the printer: refused")))
        assertTrue(failureText(RuntimeException()).contains("RuntimeException")) // no message: the exception itself
    }
}
