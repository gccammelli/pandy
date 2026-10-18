package io.github.gccammelli.pandy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlicerIngressTest {
    /** Answers Supervisor calls the way Home Assistant does for an administrator. */
    private class Supervisor(val state: String = "started", val entry: String? = "/api/hassio_ingress/abc", val slug: String = "b2cc38e2_pandy_slicer") : HaBackend {
        override val base = "https://ha.example"
        override suspend fun get(path: String) = error("unused")
        override suspend fun post(path: String, body: String) = error("unused")
        override suspend fun bytes(path: String) = error("unused")
        override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) = error("unused")
        override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map { c ->
            json.parseToJsonElement(when (c.s("endpoint")) {
                "/addons" -> """{"addons":[{"slug":"core_mosquitto"},{"slug":"$slug"}]}"""
                "/addons/$slug/info" -> """{"state":"$state"${entry?.let { ""","ingress_entry":"$it"""" }.orEmpty()}}"""
                "/ingress/session" -> """{"session":"s1"}"""
                else -> error("unexpected ${c.s("endpoint")}")
            })
        }
    }

    @Test
    fun findsTheAddOnOnHomeAssistantsAddress() = runTest {
        assertEquals("https://ha.example/api/hassio_ingress/abc" to "s1", Supervisor().slicerIngress())
    }

    @Test
    fun saysWhatToDoWhenTheAddOnIsntReady() = runTest {
        assertEquals("Start the Pandy Slicer add-on in Home Assistant", assertFailsWith<ApiException> { Supervisor(state = "stopped").slicerIngress() }.message)
        assertEquals("Update the Pandy Slicer add-on in Home Assistant", assertFailsWith<ApiException> { Supervisor(entry = null).slicerIngress() }.message)
        assertEquals("Install the Pandy Slicer add-on in Home Assistant", assertFailsWith<ApiException> { Supervisor(slug = "other").slicerIngress() }.message)
    }
}
