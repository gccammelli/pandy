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
        var failWs = false
        var failStream = false
        var wsCalls = 0
        var streamCalls = 0
        var postCalls = 0

        override suspend fun get(path: String) = base
        override suspend fun post(path: String, body: String): String {
            postCalls++
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
}
