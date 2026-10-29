package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MultiPrinterTest {
    private class Values(val data: MutableMap<String, String> = mutableMapOf()) : KeyValue, SecretStore {
        override fun getString(key: String) = data[key]
        override fun putString(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun get(key: String) = getString(key)
        override fun set(key: String, value: String?) = putString(key, value)
        override fun clear() = data.clear()
    }
    private val settings = Values()
    private val secrets = Values()
    private val store = PlatformStorage(settings, secrets, object : AppFiles {
        override fun read(name: String): ByteArray? = null
        override fun write(name: String, bytes: ByteArray) = Unit
        override fun delete(name: String) = Unit
    })
    private fun boot() {
        Repo.stop()
        Repo::class.java.getDeclaredField("store").apply { isAccessible = true }.set(Repo, null)
        Repo.init(store)
    }

    @Test fun migratesLegacyPrinterAndItsSecret() {
        settings.data.putAll(mapOf("lan_serial" to "DEMO-ONE", "lan_ip" to "10.20.0.1", "lan_name" to "Studio"))
        secrets.data["lan_code"] = "demo-code"
        try {
            boot()
            assertEquals("Studio", Repo.lan?.name)
            assertEquals("demo-code", secrets.get("lan_code_DEMO-ONE"))
            assertNull(secrets.get("lan_code"))
            assertFalse(settings.getString("lan_printers")!!.contains("demo-code"))
            boot()
            assertEquals("DEMO-ONE", Repo.lan?.serial)
        } finally { Repo.logout() }
    }

    @Test fun ignoresIncompleteOrInvalidSavedPrinterEntries() {
        try {
            boot()
            settings.putString("lan_printers", """[{"ip":"10.20.0.1"},{"serial":"missing-ip"},{"serial":"missing-code","ip":"10.20.0.2"},{"serial":"saved","ip":"10.20.0.3"}]""")
            secrets.set("lan_code_saved", "code")
            val saved = Repo.directPrinters.single()
            assertEquals("saved", saved.serial)
            assertEquals("", saved.name)
            Repo.switchDirectPrinter("unknown")
            assertNull(Repo.lan)
            settings.putString("lan_printers", "not json")
            assertTrue(Repo.directPrinters.isEmpty())
        } finally { Repo.logout() }
    }

    @Test fun addsSwitchesPersistsAndRemovesPrinters() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val published = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val a = LanConfig("10.20.0.1", "DEMO-ONE", "code-one", "P1S", "Studio")
        val b = LanConfig("10.20.0.2", "DEMO-TWO", "code-two", "P1S", "Office")
        fun printer(c: LanConfig) = DirectPrinter(c, true, { object : LanLink {
            override suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit) {
                onConnected(); onMessage("""{"print":{"command":"push_status","gcode_state":"IDLE"}}""")
                awaitCancellation()
            }
            override fun publish(payload: String) { synchronized(published) { published.add(c.serial) } }
            override fun close() { synchronized(closed) { closed.add(c.serial) } }
        } })
        suspend fun live(serial: String) = withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.live && it.printers?.singleOrNull()?.serial == serial } } }
        try {
            boot()
            Repo.connectPrinter(a, ::printer); live(a.serial)
            Repo.connectPrinter(b, ::printer); live(b.serial)
            assertEquals(2, Repo.directPrinters.size)
            Repo.switchDirectPrinter(a.serial, ::printer)
            val current = live(a.serial).printers!!.single()
            synchronized(published) { published.clear() }
            current.light(true, {}).join()
            assertTrue(synchronized(published) { published.contains(a.serial) })
            assertTrue(synchronized(closed) { closed.contains(b.serial) })
            boot()
            assertEquals(a.serial, Repo.lan?.serial)
            assertEquals(2, Repo.directPrinters.size)
            Repo.removeDirectPrinter()
            assertEquals(b.serial, Repo.lan?.serial)
            assertNull(secrets.get("lan_code_DEMO-ONE"))
            Repo.removeDirectPrinter()
            assertFalse(Repo.connected)
            assertTrue(Repo.directPrinters.isEmpty())
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }
}
