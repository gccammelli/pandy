package io.github.gccammelli.pandy

import kotlin.test.*

class RepositorySettingsTest {
    @Test fun deviceSlicerAndNotificationSettingsStayIndependent() {
        val settings = mutableMapOf<String, String>()
        val secrets = mutableMapOf<String, String>()
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = settings[key]
            override fun putString(key: String, value: String?) { if (value == null) settings.remove(key) else settings[key] = value }
            override fun clear() = settings.clear()
        }, object : SecretStore {
            override fun get(key: String) = secrets[key]
            override fun set(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        assertEquals("lan", Repo.mode); assertTrue(Repo.direct); assertFalse(Repo.connected)
        assertNull(Repo.lan); assertNull(Repo.slicer); assertNull(Repo.haApi)
        assertTrue(Repo.authHeaders().isEmpty())
        assertTrue(Repo.showUnavailable); assertTrue(Repo.notify); assertFalse(Repo.timelapseDefault)
        Repo.setShowUnavailable(false); assertFalse(Repo.showUnavailable)
        Repo.setNotify(false); assertFalse(Repo.notify)
        Repo.setTimelapseDefault(true); assertTrue(Repo.timelapseDefault)
        Repo.selected = "printer-a"; assertEquals("printer-a", Repo.selected)
        Repo.setSetting("custom", "value"); assertEquals("value", Repo.setting("custom"))
        Repo.setSetting("custom", null); assertNull(Repo.setting("custom"))
        Repo.setSlicer("  http://localhost:8737  ", "  secret  ")
        assertEquals("http://localhost:8737", Repo.slicerUrl)
        assertEquals("secret", secrets["slicer_token"])
        assertNotNull(Repo.slicer)
        Repo.saveLan(LanConfig("192.0.2.1", "serial", "access", "X1C"))
        assertEquals("192.0.2.1", Repo.lan?.ip)
        assertEquals("access", Repo.lan?.code)
        assertEquals("X1C", Repo.lan?.model)
        Repo.setFilamentPreset("serial", mapOf("PLA|white" to 1, "PETG|green" to 3))
        assertEquals(mapOf("PLA|white" to 1, "PETG|green" to 3), Repo.filamentPreset("serial"))
        settings["filament_preset_serial"] = "invalid,PLA|white:1,PETG|green:not-number,no-separator:2"
        assertEquals(mapOf("PLA|white" to 1), Repo.filamentPreset("serial"))
        Repo.removeDirectPrinter()
        assertNull(Repo.lan); assertFalse(Repo.connected)
        assertEquals("http://localhost:8737", Repo.slicerUrl)
        Repo.setSlicer(" ", " ")
        assertNull(Repo.slicerUrl); assertNull(secrets["slicer_token"])
        Repo.setFilamentPreset("serial", emptyMap()); assertTrue(Repo.filamentPreset("serial").isEmpty())
        Repo.removeHa(); assertNull(Repo.haUrl)
        Repo.setTheme("dark"); Repo.logout()
        assertEquals(mapOf("theme" to "dark"), settings)
        assertTrue(secrets.isEmpty()); assertEquals(UiState(), Repo.state.value)
    }
}
