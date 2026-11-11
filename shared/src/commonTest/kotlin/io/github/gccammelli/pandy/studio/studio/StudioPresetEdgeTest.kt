// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.slicer.EngineChoice
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.ProfileSource
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.ui.StudioTestHost
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Studio's printer choice and engine restore on profile sets that are not the tidy happy path. */
class StudioPresetEdgeTest {
    private fun profiles(machine: String, process: String, filaments: String = "[]", filamentFiles: Map<String, String> = emptyMap()) = mapOf(
        "profiles/BBL.json" to """{"machine_list":[{"name":"Bambu Lab Mochi 0.4 nozzle","sub_path":"m.json"}],
            "process_list":[{"name":"0.16mm Optimal","sub_path":"p1.json"},{"name":"0.08mm Extra Fine","sub_path":"p2.json"}],"filament_list":$filaments}""",
        "profiles/BBL/m.json" to machine,
        "profiles/BBL/p1.json" to """{"name":"0.16mm Optimal","type":"process","instantiation":"true","layer_height":"0.16","compatible_printers":["Bambu Lab Mochi 0.4 nozzle"]$process}""",
        "profiles/BBL/p2.json" to """{"name":"0.08mm Extra Fine","type":"process","instantiation":"true","layer_height":"0.08","compatible_printers":["Bambu Lab Mochi 0.4 nozzle"]}""",
    ) + filamentFiles

    private fun loaded(files: Map<String, String>) = runBlocking { Studio(StudioTestHost(files), this).also { it.load().join() } }

    @Test fun aPrinterWithoutAreaOrDefaultsGetsTheDefaultBedAndTheFirstProcess() {
        val s = loaded(profiles("""{"name":"Bambu Lab Mochi 0.4 nozzle","type":"machine","instantiation":"true","printer_model":"Mochi"}""", ""))
        assertEquals(Bed(), s.scene.bed)
        assertEquals("0.08mm Extra Fine", s.process?.name) // sorted by layer height, no default named
        assertTrue(s.filaments.isEmpty()); assertTrue(s.filamentChoices.isEmpty()); assertTrue(!s.canSlice)
    }

    @Test fun thePrinterNamesItsDefaultProcessAndFilamentAsPlainStrings() {
        val files = profiles(
            """{"name":"Bambu Lab Mochi 0.4 nozzle","type":"machine","instantiation":"true","printer_model":"Mochi","printable_area":["0x0","180x0","180x180","0x180"],
                "printable_height":"150","default_print_profile":"0.16mm Optimal","default_filament_profile":"Luna PLA"}""", "",
            """[{"name":"Luna PLA","sub_path":"f1.json"},{"name":"Bambu Robbie PETG","sub_path":"f2.json"}]""",
            mapOf(
                "profiles/BBL/f1.json" to """{"name":"Luna PLA","type":"filament","instantiation":"true","default_filament_colour":["#112233"],"compatible_printers":["Bambu Lab Mochi 0.4 nozzle"]}""",
                "profiles/BBL/f2.json" to """{"name":"Bambu Robbie PETG","type":"filament","instantiation":"true","default_filament_colour":"red","compatible_printers":["Bambu Lab Mochi 0.4 nozzle"]}""",
            ),
        )
        val s = loaded(files)
        assertEquals(Bed(0f, 0f, 180f, 180f, 150f), s.scene.bed)
        assertEquals("0.16mm Optimal", s.process?.name)
        assertEquals(listOf("Bambu Robbie PETG", "Luna PLA"), s.filamentChoices.map { it.name }) // Bambu presets first
        assertEquals("Luna PLA", s.filaments.single().first.name); assertEquals("#112233", s.filaments.single().second)
        s.setFilament(1, s.filamentChoices.first())
        assertEquals("#E0409C", s.filaments[1].second) // a colour that is not #RRGGBB falls back
        s.choosePrinter(s.printer!!)
        assertEquals(2, s.filaments.size) // kept across a re-choice of the same printer
    }

    @Test fun anOverrideEqualToThePresetValueIsDroppedAndAnUnchangedOneIsKept() {
        val s = loaded(profiles("""{"name":"Bambu Lab Mochi 0.4 nozzle","type":"machine","instantiation":"true"}""", ""))
        s.set("layer_height", Setting.One("0.5")); s.set("layer_height", Setting.One("0.5"))
        assertEquals(1, s.overrides.size)
        s.set("layer_height", s.process!!["layer_height"]!!)
        assertTrue(s.overrides.isEmpty())
    }

    private class Engines(val files: Map<String, String>, val saved: String?, val orcaWorks: Boolean) : Platform by StudioTestHost(files) {
        val store = mutableMapOf<String, String>().also { m -> saved?.let { m["engine"] = it } }
        override fun loadSetting(key: String) = store[key]
        override fun saveSetting(key: String, value: String) { store[key] = value }
        override suspend fun engines() = listOf(EngineChoice("bambu", "Bambu Studio", "1"), EngineChoice("orca", "OrcaSlicer", "2"))
        override suspend fun engineInfo() = EngineInfo("1", "e", "r")
        override fun forEngine(id: String): Platform = if (id != "orca") this else object : Platform by this@Engines {
            override suspend fun engineInfo() = EngineInfo("2", "e2", "r2", "orca", "OrcaSlicer")
            override suspend fun profiles(): ProfileSource = if (orcaWorks) ProfileSource { files[it] } else error("orca profiles are broken")
        }
    }

    private val files = profiles("""{"name":"Bambu Lab Mochi 0.4 nozzle","type":"machine","instantiation":"true"}""", "")

    @Test fun aRememberedEngineIsRestoredAtLoad() = runBlocking<Unit> {
        val host = Engines(files, saved = "orca", orcaWorks = true)
        val s = Studio(host, this); s.load().join()
        assertEquals("orca", s.engine?.id); assertEquals("orca", host.store["engine"])
    }

    @Test fun aRememberedEngineThatNoLongerLoadsFallsBackWithoutForgettingIt() = runBlocking<Unit> {
        val host = Engines(files, saved = "orca", orcaWorks = false)
        val s = Studio(host, this); s.load().join()
        assertEquals("bambu", s.engine?.id); assertNull(s.loadError); assertEquals("orca", host.store["engine"])
    }

    @Test fun anUnknownRememberedEngineIsIgnoredAndChoosingTheCurrentOneIsANoOp() = runBlocking<Unit> {
        val host = Engines(files, saved = "gone", orcaWorks = true)
        val s = Studio(host, this); s.load().join()
        assertEquals("bambu", s.engine?.id); assertEquals("bambu", host.store["engine"])
        assertNull(s.chooseEngine("bambu"))
        s.chooseEngine("orca")!!.join()
        assertEquals("orca", s.engine?.id); assertEquals("orca", host.store["engine"])
        assertTrue(s.overrides.isEmpty())
    }

    @Test fun reloadingTheCurrentEngineRebuildsItsProfiles() = runBlocking<Unit> {
        val s = Studio(Engines(files, saved = null, orcaWorks = true), this); s.load().join()
        s.set("layer_height", Setting.One("0.5"))
        s.chooseEngine("bambu", reload = true)!!.join()
        assertTrue(s.overrides.isEmpty()); assertEquals("Bambu Lab Mochi 0.4 nozzle", s.printer?.name)
    }

    @Test fun aBrokenFirstLoadReportsTheErrorAndCannotChooseEngines() = runBlocking<Unit> {
        val s = Studio(StudioTestHost(emptyMap()), this); s.load().join()
        assertTrue(s.loadError!!.isNotBlank()); assertNull(s.engine)
        assertEquals("Engine selection is unavailable.", assertFailsWith<IllegalStateException> { s.chooseEngine("bambu") }.message)
    }
}
