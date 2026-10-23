package io.github.gccammelli.pandy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DownloadHttpTest {
    @Test fun streamedDownloadsPreserveBytesCookiesAndReportServerFailures() = runTest {
        val bytes=ByteArray(150_000) { (it%251).toByte() }
        val cookies=mutableListOf<String?>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            cookies += exchange.requestHeaders.getFirst("Cookie")
            when(exchange.requestURI.path) {
                "/failure" -> { exchange.sendResponseHeaders(503,4); exchange.responseBody.use { it.write("busy".toByteArray()) } }
                "/oversize" -> { exchange.sendResponseHeaders(200,65L*1024*1024); exchange.responseBody.close() }
                else -> { exchange.sendResponseHeaders(200,0); exchange.responseBody.use { it.write(bytes) } }
            }
        }
        server.start()
        try {
            val base="http://127.0.0.1:${server.address.port}"
            assertContentEquals(bytes,download("$base/model","session=secret"))
            assertEquals("session=secret",cookies.first())
            assertTrue(assertFailsWith<ApiException> { download("$base/failure",null) }.message!!.contains("503"))
            assertTrue(assertFailsWith<ApiException> { download("$base/oversize",null) }.message!!.contains("64 MiB"))
        } finally { server.stop(0) }
    }
}
