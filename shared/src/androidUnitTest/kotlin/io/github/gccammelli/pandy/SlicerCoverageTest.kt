package io.github.gccammelli.pandy

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SlicerCoverageTest {
    private fun archive(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    @Test fun downloadsSkipBlankCookiesFillTheBufferExactlyAndStopEndlessStreams() = runTest {
        val cookies = mutableListOf<String?>()
        val exact = ByteArray(64 * 1024) { (it % 251).toByte() }
        val (server, base) = serve { exchange ->
            cookies += exchange.requestHeaders.getFirst("Cookie")
            if (exchange.requestURI.path == "/endless") {
                exchange.sendResponseHeaders(200, 0)
                val chunk = ByteArray(1024 * 1024)
                exchange.responseBody.use { out -> runCatching { repeat(70) { out.write(chunk) } } }
            } else exchange.sendResponseHeaders(200, 0).also { exchange.responseBody.use { it.write(exact) } }
        }
        try {
            assertContentEquals(exact, download("$base/exact", "  "))
            assertNull(cookies.single())
            assertTrue(assertFailsWith<ApiException> { download("$base/endless", null) }.message!!.contains("64 MiB"))
        } finally { server.stop(0) }
    }

    @Test fun aStalledOrRefusedDownloadFailsWithAPlainMessageThatNeverNamesTheUrl() = runBlocking {
        val (server, base) = serve { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(1); it.flush(); Thread.sleep(1500) }
        }
        val before = downloadStallMillis
        downloadStallMillis = 200
        try {
            val failure = runCatching { download("$base/stall", null) }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.message!!.contains("stalled") && "127.0.0.1" !in failure.message!!, "got $failure")
        } finally { downloadStallMillis = before; server.stop(0) }
        val refused = assertFailsWith<ApiException> { download("https://models.invalid/gone", null) }.message!!
        assertTrue(refused.contains("download failed") && "models.invalid" !in refused && !refused.contains("stalled"), "got $refused")
    }

    private val mesh = """<mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh>"""

    @Test fun scannersSkipLookalikesAttributeNamesInsideValuesAndUnterminatedElements() {
        val xml = """<model><resources>
            <object>$mesh</object>
            <object data-id="5" id=7 id="1"><meshy/>$mesh</object>
            <object id="2"><mesh></object>
            <object id="3"><mesh</object>
            <object id="4"><objects/></object>
            </resources><build><item objectid="1"/><item objectid="2"/><item objectid="3"/><item objectid="4"/></build><object id="6">$mesh""".trimIndent()
        assertEquals(1, previewMesh(xml)!!.triangles.size)
        assertEquals(1, previewMesh("""<model><resources><object id="1">$mesh</object></resources></model>""")!!.triangles.size) // no <build>: every object
    }

    @Test fun previewsSurviveIncompleteSettingsMissingComponentPathsAndPlatesWithoutMeshes() = runTest {
        val root = """<model xmlns:p="x"><resources>
            <object id="11"><components><component objectid="1"/><component p:path="/3D/Objects/o.model" objectid="1"/></components></object>
            <object id="12"/></resources><build><item objectid="11"/><item objectid="12"/></build></model>"""
        val settings = """<config><object id="1"><metadata key="extruder" value="2"/></object>
            <plate><metadata key="plater_id" value="x"/></plate>
            <plate><metadata key="plater_id" value="1"/><model_instance><metadata/></model_instance><model_instance><metadata key="object_id" value="12"/></model_instance></plate>
            <plate><metadata key="plater_id" value="2"/><model_instance><metadata key="object_id" value="11"/></model_instance></plate></config>"""
        val data = archive("3D/3dmodel.model" to root, "3D/Objects/o.model" to """<model><resources><object id="1">$mesh</object></resources></model>""",
            "Metadata/model_settings.config" to settings)
        val meshes = readPreviewMeshes(data)
        assertEquals(setOf(2), meshes.keys) // plate 1's only object is empty, so it has no mesh
        assertNull(meshes.getValue(2).triangles.single().material?.takeIf { source -> source == 99 })
        assertEquals(meshes.getValue(2), readProject(data).mesh) // the first plate has none: the project falls back to another plate's
    }

    @Test fun plateTimesFallBackToGcodeWhenSliceInfoSkipsAPlateOrOmitsItsFigures() = runTest {
        val settings = """<config><plate><metadata key="plater_id" value="1"/></plate><plate><metadata key="plater_id" value="2"/></plate><plate><metadata key="plater_id" value="3"/></plate></config>"""
        val sliceInfo = """<config><plate><metadata key="index" value="1"/><object skipped="false"/></plate><plate><metadata key="index" value="3"/></plate></config>"""
        val p = readProject(archive("Metadata/model_settings.config" to settings, "Metadata/slice_info.config" to sliceInfo,
            "Metadata/plate_2.gcode" to "; generated with nothing useful\n"), preview = false)
        assertEquals(listOf(null, null, null), p.plates.map { it.seconds })
        assertEquals(listOf(1, null, null), p.plates.map { it.parts })
        assertEquals(listOf(null, null, null), p.plates.map { it.grams })
    }

    @Test fun cancellingADownloadMidwayCancelsTheCallerInsteadOfReportingAFailure() = runBlocking<Unit> {
        val (server, base) = serve { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.use { it.write(1); it.flush(); Thread.sleep(1500) } }
        try {
            val job = launch(Dispatchers.Default) { download("$base/slow", null) }
            delay(300); job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { server.stop(0) }
    }

    @Test fun onlyTimeoutsAreDescribedAsTimeoutsAndAttributeLookupSkipsLookalikes() {
        assertTrue(io.ktor.client.plugins.HttpRequestTimeoutException("https://x", 1).timedOut())
        assertTrue(io.ktor.client.network.sockets.ConnectTimeoutException("connect", null).timedOut())
        assertTrue(io.ktor.client.network.sockets.SocketTimeoutException("socket", null).timedOut())
        assertTrue(IllegalStateException("outer", RuntimeException("Socket timeout has expired [url=x]")).timedOut())
        assertFalse(IllegalStateException("other", RuntimeException("Connection refused")).timedOut())
        assertEquals("1", xmlAttr("id=\"1\" x=\"2\"", "id")) // at the very start of the text
        assertEquals("1", xmlAttr("id='1'", "id"))
        assertEquals("2", xmlAttr("xid=\"1\" id=\"2\"", "id"))
        assertNull(xmlAttr("id=1", "id")); assertNull(xmlAttr("id", "id")); assertNull(xmlAttr("id=\"unterminated", "id"))
        assertEquals("model.gcode.3mf", slicedFileName("attachment")) // no filename at all
    }
}
