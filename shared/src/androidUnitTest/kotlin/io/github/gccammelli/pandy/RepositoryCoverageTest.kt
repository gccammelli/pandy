package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepositoryCoverageTest {
    private class Values(private val values: MutableMap<String, String>) : KeyValue, SecretStore {
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun get(key: String) = getString(key)
        override fun set(key: String, value: String?) = putString(key, value)
        override fun clear() = values.clear()
    }
    private val storeField = Repo::class.java.getDeclaredField("store").apply { isAccessible = true }
    private fun boot(settings: Map<String, String>, secrets: Map<String, String> = emptyMap()) {
        Repo.stop()
        storeField.set(Repo, null)
        Repo.init(PlatformStorage(Values(settings.toMutableMap()), Values(secrets.toMutableMap()), object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
    }
    private fun backend(b: HaBackend) { for (field in listOf("ha", "extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible = true }.set(Repo, b) }

    private val config = LanConfig("192.0.2.1", "COVERAGE-TEST", "test-code", "P1S", "Workshop")
    private fun idleLink() = DirectPrinter(config, true, { object : LanLink {
        override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
            onConnected(); onMessage("""{"print":{"command":"push_status","gcode_state":"IDLE"}}""")
            awaitCancellation()
        }
        override fun publish(payload: String) = Unit
        override fun close() = Unit
    } })
    private suspend fun registryOf(demo: FakeHomeAssistant): Map<String, JsonElement> {
        val types = listOf("config/device_registry/list", "config/entity_registry/list")
        return types.zip(demo.ws(types.map { buildJsonObject { put("type", it) } }).map { it!! }).toMap()
    }

    @Test fun aSavedThemeLoadsAndTheDemoCanBeStartedAndLeft() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            boot(mapOf("theme" to "dark"))
            assertEquals("dark", io.github.gccammelli.pandy.ui.ThemeMode.mode)
            assertFalse(Repo.demo)
            Repo.startDemo()
            assertTrue(Repo.demo); assertTrue(Repo.ha is FakeHomeAssistant); assertSame(Repo.ha, Repo.extras)
            withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.live && !it.printers.isNullOrEmpty() } } }
            Repo.stop(); Repo.start() // a second start while stopped works; restart() is stop + start
            Repo.restart()
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun startingWithoutAConnectionDoesNothingAndActionsAreIgnored() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        boot(emptyMap())
        Repo.start(); assertFalse(Repo.state.value.on)
        val errors = mutableListOf<String>()
        Repo.act({ errors += it }) { error("never runs without a connection") }.join()
        assertTrue(errors.isEmpty())
        backend(FakeHomeAssistant())
        Repo.act({ errors += it }) { throw ApiException("refused") }.join()
        assertEquals(listOf("refused"), errors)
        Repo.act { error("no message handler: the failure is swallowed") }.join()
        Repo.logout(); Dispatchers.resetMain()
    }

    @Test fun aDroppedLiveConnectionReportsTheErrorThenRetriesAfterABackoff() = runBlocking {
        boot(emptyMap())
        val demo = FakeHomeAssistant()
        var attempts = 0
        backend(object : HaBackend by demo {
            override suspend fun ws(requests: List<JsonObject>): List<JsonElement?> {
                attempts++
                if (attempts == 1) throw ApiException("Home Assistant went away")
                if (attempts == 2) throw object : Exception() { override val message: String? = null }
                return demo.ws(requests)
            }
        })
        try {
            Repo.start()
            withTimeout(5000) { while (Repo.state.value.error != "Home Assistant went away") delay(20) }
            assertFalse(Repo.state.value.live)
            withTimeout(8000) { while (attempts < 3) delay(50) } // the loop waits 1 s, then 2 s
            withTimeout(8000) { Repo.state.first { it.live && !it.printers.isNullOrEmpty() } }
            assertNull(Repo.state.value.error)
        } finally { Repo.logout() }
    }

    @Test fun anAuthenticationFailureStopsRetrying() = runBlocking {
        boot(emptyMap())
        var attempts = 0
        backend(object : HaBackend by FakeHomeAssistant() {
            override suspend fun ws(requests: List<JsonObject>): List<JsonElement?> { attempts++; throw AuthException("Token rejected") }
        })
        try {
            Repo.start()
            withTimeout(5000) { while (Repo.state.value.error != "Token rejected") delay(20) }
            delay(300)
            assertEquals(1, attempts)
        } finally { Repo.logout() }
    }

    @Test fun printersWithoutAnAmsSpoolHolderOrCameraSwitchRefuseThoseActions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        boot(emptyMap())
        val demo = FakeHomeAssistant()
        backend(demo)
        val info = demo.printers().single()
        val bare = Printer(info.copy(ams = emptyList(), externalDevice = null, ids = info.ids - "switch.camera"), emptyMap())
        val errors = mutableListOf<String>()
        bare.unload { errors += it }.join()
        assertEquals(listOf("No AMS or spool holder in Home Assistant"), errors)
        bare.toggleCamera(true) { errors += it }.join()
        assertEquals(1, errors.size)
        Repo.logout(); Dispatchers.resetMain()
    }

    @Test fun directSetupKeepsHomeAssistantAndReplacesAPreviousPrinter() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            boot(mapOf("ha_url" to "https://home.example", "mode" to "lan"), mapOf("ha_token" to "token"))
            assertTrue(Repo.extras is HaHttp)
            Repo.connectPrinter(config) { idleLink() }
            assertTrue(Repo.ha is PreferPrimaryBackend); assertTrue(Repo.extras is HaHttp)
            Repo.connectPrinter(config.copy(name = "Renamed")) { idleLink() } // replaces the first direct printer
            assertEquals("Renamed", Repo.lan!!.name)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun homeAssistantCanTakeOverFromADirectPrinterOrBeRefusedWithoutBambuDevices() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val demo = FakeHomeAssistant()
        try {
            val registry = registryOf(demo)
            val settings = mapOf("lan_serial" to config.serial, "lan_ip" to config.ip)
            HaSocket(registry = registry).use { peer ->
                boot(settings, mapOf("lan_code" to "test-code"))
                Repo.connect(peer.url, "token", asPrinter = true)
                assertEquals("ha", Repo.mode); assertTrue(Repo.ha is HaHttp)
                assertNull(Repo.state.value.error)
                Repo.removeHa() // a saved direct printer remains: direct mode resumes
                assertTrue(Repo.ha is DirectPrinter); assertTrue(Repo.state.value.on)
            }
            HaSocket(registry = registry).use { peer ->
                boot(emptyMap())
                Repo.connect(peer.url, "token", asPrinter = false) // no direct printer: only the extras change
                assertTrue(Repo.extras is HaHttp); assertNull(Repo.ha)
            }
            HaSocket(registry = mapOf("config/device_registry/list" to JsonArray(emptyList()), "config/entity_registry/list" to JsonArray(emptyList()))).use { peer ->
                boot(emptyMap())
                assertTrue(assertFailsWith<ApiException> { Repo.connect(peer.url, "token") }.message!!.contains("no Bambu Lab printers"))
            }
            boot(emptyMap())
            Repo.removeHa() // nothing saved: stays off
            assertFalse(Repo.state.value.on)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun printOptionsAndPrintStartsTellEachBackendOnlyWhatItUnderstands() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        boot(emptyMap())
        val demo = FakeHomeAssistant()
        val bodies = mutableListOf<JsonObject>()
        val recording = object : HaBackend by demo {
            override suspend fun post(path: String, body: String): String { bodies += json.parseToJsonElement(body).jsonObject; return "[]" }
        }
        val info = demo.printers().single()
        val p = Printer(info, emptyMap())
        try {
            backend(recording)
            p.setPrintOption("auto_recovery", true) {}.join(); assertEquals("1", bodies.last().s("option"))
            p.setPrintOption("auto_recovery", false) {}.join(); assertEquals("0", bodies.last().s("option"))
            p.setPrintOption("auto_switch_filament", true) {}.join(); assertNull(bodies.last()["option"])
            // The bed type goes only to a printer that is controlled directly, never to Home Assistant's service.
            recording.startPrint(p, "cache/a.3mf", 1, null, false, true, true, "hot_plate"); assertNull(bodies.last()["bed_type"])
            val usable = PreferPrimaryBackend(recording, demo)
            usable.startPrint(p, "cache/a.3mf", 1, listOf(0), false, true, true, "hot_plate"); assertEquals("hot_plate", bodies.last().s("bed_type"))
            assertEquals("true", bodies.last().s("use_ams")); assertEquals("0", bodies.last().s("ams_mapping"))
            val failing = object : HaBackend by demo { override suspend fun post(path: String, body: String): String = throw ApiException("down") }
            val fallenBack = PreferPrimaryBackend(failing, recording)
            runCatching { fallenBack.startPrint(p, "cache/a.3mf", 1, null, false, true, true, "hot_plate") }
            fallenBack.startPrint(p, "cache/a.3mf", 1, null, false, true, true, "hot_plate"); assertNull(bodies.last()["bed_type"])
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }
}
