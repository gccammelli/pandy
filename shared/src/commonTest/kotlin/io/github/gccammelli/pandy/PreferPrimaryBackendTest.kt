package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class PreferPrimaryBackendTest {
    private class Backend(override val base: String) : HaBackend {
        var failPost = false
        var failRead: Exception? = null
        var failWs = false
        var failStream = false
        var wsCalls = 0
        var streamCalls = 0
        var postCalls = 0

        override suspend fun get(path: String): String { failRead?.let { throw it }; return base }
        override suspend fun post(path: String, body: String): String {
            postCalls++
            if(failPost) error("Connection dropped after sending")
            return base
        }
        override suspend fun bytes(path: String) = base.encodeToByteArray()
        override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
            wsCalls++
            if (failWs) error("offline")
            return listOf(JsonPrimitive(base))
        }
        override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) {
            streamCalls++
            if (failStream) error("offline")
            onEvent(buildJsonObject {})
        }
    }

    @Test
    fun fallsBackAndKeepsUsingRemoteBackend() = runTest {
        val local = Backend("lan://printer")
        val remote = Backend("https://ha.example")
        local.failWs = true
        val backend = PreferPrimaryBackend(local, remote)

        assertEquals("https://ha.example", (backend.ws(emptyList()).single() as JsonPrimitive).content)
        assertFalse(backend.usingPrimary)
        backend.post("/api/services/test", "{}")
        assertEquals(1, remote.postCalls)
        assertEquals("https://ha.example", backend.base)
    }

    @Test
    fun streamFailureRetriesWithRemoteRegistry() = runTest {
        val local = Backend("lan://printer")
        val remote = Backend("https://ha.example")
        local.failStream = true
        val backend = PreferPrimaryBackend(local, remote)

        assertFailsWith<IllegalStateException> { backend.stream(buildJsonObject {}) {} }
        assertFalse(backend.usingPrimary)
        backend.stream(buildJsonObject {}) {}
        assertEquals(1, remote.streamCalls)
    }
    @Test fun failedCommandsAreNeverAutomaticallySentTwice() = runTest {
        val local = Backend("lan://printer").apply { failPost=true }
        val remote = Backend("https://ha.example")
        val backend = PreferPrimaryBackend(local,remote)
        assertFailsWith<IllegalStateException> { backend.post("/api/services/button/press","{}") }
        assertEquals(1,local.postCalls); assertEquals(0,remote.postCalls)
        assertFalse(backend.usingPrimary)
    }

    @Test fun unavailableLocalReadFallsBackButUnsupportedEndpointDoesNotSwitchControls() = runTest {
        val local = Backend("lan://printer")
        val remote = Backend("https://ha.example")
        val backend = PreferPrimaryBackend(local,remote)
        local.failRead=ApiException(DirectPrinter.NEEDS_HA)
        assertEquals("https://ha.example",backend.get("/api/ha-only"))
        kotlin.test.assertTrue(backend.usingPrimary)
        local.failRead=ApiException("Printer offline")
        assertEquals("https://ha.example",backend.get("/api/config"))
        assertFalse(backend.usingPrimary)
        kotlin.test.assertContentEquals("https://ha.example".encodeToByteArray(),backend.bytes("/image"))
    }

}
