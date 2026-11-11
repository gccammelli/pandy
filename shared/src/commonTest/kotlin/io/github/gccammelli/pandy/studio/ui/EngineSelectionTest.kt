package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.slicer.*
import io.github.gccammelli.pandy.studio.studio.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EngineSelectionTest {
    private val choices = listOf(EngineChoice("bambu", "Bambu Studio", "2.8"), EngineChoice("orca", "OrcaSlicer", "2.5"))
    private fun host(id: String = "bambu", fail: Boolean = false, saved: MutableMap<String, String>? = null): Platform {
        val source = StudioTestHost(studioTestProfiles.mapValues { (_, text) ->
            if (id == "orca") text.replace("\"layer_height\":\"0.2\"", "\"layer_height\":\"0.16\"") else text
        })
        if (id == "orca") source.settings["printer"] = "Bambu Lab A1 mini 0.4 nozzle"
        saved?.let { source.settings.putAll(it) }
        return object : Platform by source {
            override suspend fun engineInfo() = EngineInfo("version", "hash", id, id, id)
            override suspend fun engines() = choices
            override fun forEngine(id: String) = host(id, fail)
            override fun saveSetting(key: String, value: String) { source.saveSetting(key, value); if (key == "engine") saved?.put(key, value) }
            override suspend fun profiles(): ProfileSource {
                if (fail && id == "orca") error("Missing Orca profiles")
                return source.profiles()
            }
        }
    }

    @Test fun switchingKeepsGeometryButReloadsSameNamedProfilesAndClearsOverrides() = runBlocking {
        val studio = Studio(host(), this)
        studio.load().join()
        studio.addSample("cube", Shapes.box(10f, 10f, 10f))
        val model = studio.scene.objects.single()
        model.x = 100f; model.y = 120f
        studio.set("layer_height", Setting.One("0.3"))
        studio.setFilament(1, studio.filamentChoices.last())
        model.filament = 1
        studio.state = SliceState.Failed("old result")
        studio.chooseEngine("orca")!!.join()
        assertEquals("orca", studio.engine!!.id)
        assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", studio.printer!!.name)
        assertEquals("0.16", studio.value("layer_height")!!.first)
        assertTrue(studio.overrides.isEmpty())
        assertEquals(1, studio.filaments.size)
        assertEquals(0, model.filament)
        assertSame(model, studio.scene.objects.single())
        assertEquals(100f, model.x); assertEquals(120f, model.y)
        assertEquals(SliceState.Idle, studio.state)
        assertTrue(studio.canSlice)
        studio.chooseEngine("bambu")!!.join()
        assertEquals("0.2", studio.value("layer_height")!!.first)
    }

    @Test fun reloadingTheCurrentEngineClearsStaleSettingsButReselectingItIsANoOp() = runBlocking {
        val studio = Studio(host(), this); studio.load().join()
        studio.set("layer_height", Setting.One("0.3"))
        assertNull(studio.chooseEngine("bambu"))
        assertEquals("0.3", studio.value("layer_height")!!.first)
        studio.chooseEngine("bambu", reload = true)!!.join()
        assertEquals("0.2", studio.value("layer_height")!!.first)
        assertTrue(studio.overrides.isEmpty())
    }

    @Test fun unavailableOrBusyEngineDoesNotChangeTheCurrentSession() = runBlocking {
        val studio = Studio(host(), this); studio.load().join()
        assertFailsWith<IllegalArgumentException> { studio.chooseEngine("unknown") }
        studio.state = SliceState.Running("slicing", null)
        assertFailsWith<IllegalArgumentException> { studio.chooseEngine("orca") }
        assertEquals("bambu", studio.engine!!.id)
    }

    @Test fun failedProfileLoadCannotReusePreviousEngineSettings() = runBlocking {
        val studio = Studio(host(fail = true), this); studio.load().join()
        studio.addSample("cube", Shapes.box(10f, 10f, 10f))
        studio.chooseEngine("orca")!!.join()
        assertEquals("Missing Orca profiles", studio.loadError)
        assertNull(studio.engine); assertTrue(studio.scene.colors.isEmpty())
        assertNull(studio.library); assertNull(studio.schema); assertNull(studio.job())
        assertFalse(studio.canSlice)
        assertEquals(1, studio.scene.objects.size)
    }

    @Test fun theChosenEngineIsRememberedAcrossSessionsUnlessItIsNoLongerInstalledOrFailedToLoad() = runBlocking {
        val saved = mutableMapOf<String, String>()
        val first = Studio(host(saved = saved), this); first.load().join()
        assertEquals("bambu", saved["engine"])
        first.chooseEngine("orca")!!.join()
        assertEquals("orca", saved["engine"])

        val second = Studio(host(saved = saved), this); second.load().join()
        assertEquals("orca", second.engine!!.id)
        assertEquals("0.16", second.value("layer_height")!!.first)
        second.chooseEngine("bambu", reload = true)!!.join()
        assertEquals("bambu", second.engine!!.id)

        val missing = Studio(host(saved = mutableMapOf("engine" to "removed")), this); missing.load().join()
        assertEquals("bambu", missing.engine!!.id)

        val failed = mutableMapOf<String, String>()
        val studio = Studio(host(fail = true, saved = failed), this); studio.load().join()
        studio.chooseEngine("orca")!!.join()
        assertEquals("bambu", failed["engine"])
    }

    @Test fun aRememberedEngineThatNoLongerLoadsOpensThePluginDefaultAndStaysRemembered() = runBlocking {
        val saved = mutableMapOf("engine" to "orca")
        val studio = Studio(host(fail = true, saved = saved), this); studio.load().join()
        assertNull(studio.loadError)
        assertEquals("bambu", studio.engine!!.id)
        assertEquals("0.2", studio.value("layer_height")!!.first)
        assertEquals("orca", saved["engine"])
    }
}
