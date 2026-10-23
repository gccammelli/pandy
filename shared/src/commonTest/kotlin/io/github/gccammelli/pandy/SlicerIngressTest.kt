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

    @Test fun missingSupervisorFieldsAndAccessErrorsFailClosed() = runTest {
        suspend fun check(responses: List<JsonElement?>, expected: String, denied: Boolean = false) {
            val backend = object : HaBackend by Supervisor() {
                override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
                    if (denied) throw ApiException("Forbidden")
                    return if (commands.singleOrNull()?.s("endpoint") == "/addons") responses.take(1)
                    else responses.drop(1)
                }
            }
            assertEquals(expected,assertFailsWith<ApiException> { backend.slicerIngress() }.message)
        }
        val addons=json.parseToJsonElement("""{"addons":[null,{}, {"slug":"pandy_slicer"}]}""")
        val info=json.parseToJsonElement("""{"state":"started","ingress_entry":"/slicer"}""")
        check(listOf(null),"Install the Pandy Slicer add-on in Home Assistant")
        check(listOf(json.parseToJsonElement("{}")),"Install the Pandy Slicer add-on in Home Assistant")
        check(listOf(addons,null,null),"Start the Pandy Slicer add-on in Home Assistant")
        check(listOf(addons,info,null),"Home Assistant didn't open a session for the slicer")
        check(listOf(addons,info,json.parseToJsonElement("{}")),"Home Assistant didn't open a session for the slicer")
        check(emptyList(),"Pandy's Home Assistant user must be an administrator to use the slicer add-on (Forbidden)",denied=true)
    }
}
