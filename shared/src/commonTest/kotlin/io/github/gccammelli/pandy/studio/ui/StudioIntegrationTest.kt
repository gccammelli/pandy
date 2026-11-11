// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ApiException
import io.github.gccammelli.pandy.CONTROL_BLOCKED
import io.github.gccammelli.pandy.Entity
import io.github.gccammelli.pandy.FakeHomeAssistant
import io.github.gccammelli.pandy.Printer
import io.github.gccammelli.pandy.printers
import io.github.gccammelli.pandy.ui.debugDownload
import io.github.gccammelli.pandy.loadedSpools
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.io.Zip
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.ui.Nav
import io.github.gccammelli.pandy.ui.Screen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Studio inside Pandy: getting the slicer, files opened with Pandy, and printing a sliced plate. */
class StudioIntegrationTest {
    private val sha = "ab".repeat(32)
    private fun release(name: String = PLUGIN_ASSET, digest: String? = "sha256:$sha", size: Long = 92_000_000,
                        url: String = "https://github.com/gccammelli/pandy-slicer-plugin/releases/download/v1.0.0/$PLUGIN_ASSET") =
        """{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"https://example.invalid/notes.txt","size":3},
            {"name":"$name","browser_download_url":"$url","size":$size${digest?.let { ""","digest":"$it"""" } ?: ""}}]}"""

    // ---------- Get the slicer ----------

    @Test fun thePluginComesFromTheReleaseWithItsChecksumAndSize() =
        assertEquals(PluginDownload("https://github.com/gccammelli/pandy-slicer-plugin/releases/download/v1.0.0/$PLUGIN_ASSET", sha, 92_000_000, "v1.0.0"), pluginDownload(release()))

    @Test fun aReleaseWithoutThePluginOrThatCannotBeReadIsNotInstalled() {
        assertEquals("The slicer isn't published yet.", assertFailsWith<ApiException> { pluginDownload(release(name = "other.apk")) }.message)
        assertEquals("The slicer isn't published yet.", assertFailsWith<ApiException> { pluginDownload("""{"tag_name":"v1"}""") }.message)
        assertFailsWith<ApiException> { pluginDownload("<html>rate limited</html>") }
    }

    @Test fun aPluginWithoutAChecksumIsNotInstalled() {
        for (digest in listOf(null, "", "sha256:abc", "sha512:$sha$sha", "sha256:${"zz".repeat(32)}")) {
            assertTrue("checksum" in assertFailsWith<ApiException>("$digest") { pluginDownload(release(digest = digest)) }.message!!)
        }
        assertEquals(sha, pluginDownload(release(digest = "sha256:${sha.uppercase()}")).sha256)
    }

    @Test fun aPluginOfAnImplausibleSizeIsNotInstalled() {
        for (size in listOf(0L, -1L, PLUGIN_MAX_BYTES + 1)) assertTrue("size" in assertFailsWith<ApiException> { pluginDownload(release(size = size)) }.message!!)
        assertEquals(PLUGIN_MAX_BYTES, pluginDownload(release(size = PLUGIN_MAX_BYTES)).size)
    }

    @Test fun aPluginFromOutsideTheProjectsReleasesIsOnlyTakenByDebugBuilds() {
        for (url in listOf("http://github.com/gccammelli/pandy-slicer-plugin/releases/download/v1/$PLUGIN_ASSET", "https://github.com/someone/pandy-slicer-plugin/releases/download/v1/$PLUGIN_ASSET",
            "https://github.com.example.invalid/gccammelli/pandy-slicer-plugin/releases/download/v1/$PLUGIN_ASSET", "http://127.0.0.1:8765/$PLUGIN_ASSET", "")) {
            assertTrue("outside GitHub" in assertFailsWith<ApiException>(url) { pluginDownload(release(url = url)) }.message!!)
        }
        assertEquals("http://127.0.0.1:8765/$PLUGIN_ASSET", pluginDownload(release(url = "http://127.0.0.1:8765/$PLUGIN_ASSET"), anyHost = true).url)
    }

    // ---------- Open with Pandy ----------

    private val stl = Stl.writeBinary(Shapes.box(10f, 10f, 10f))
    private val plain3mf = Zip.write(listOf("3D/3dmodel.model" to "<model/>".encodeToByteArray()))
    private val project3mf = Zip.write(listOf("3D/3dmodel.model" to "<model/>".encodeToByteArray(), "Metadata/project_settings.config" to "{}".encodeToByteArray()))

    private fun studio(host: StudioTestHost = StudioTestHost(), preferred: String? = null) = runBlocking { Studio(host, this).also { it.load(preferred).join() } }

    @BeforeTest @AfterTest fun clean() { StudioSession.reset(); Nav.stack.clear(); Nav.tab = 1; debugDownload.value = null }

    @Test fun modelsOpenInStudioAndProjectsInThePrintSheet() {
        Nav.go(Screen.About)
        assertTrue(StudioSession.open("Cube.STL", stl))
        assertEquals(STUDIO_TAB, Nav.tab)
        assertTrue(Nav.stack.isEmpty())
        assertTrue(StudioSession.open("plain.3mf", plain3mf))
        assertEquals(listOf("Cube.STL", "plain.3mf"), StudioSession.pending.map { it.first })
        assertNull(debugDownload.value)

        assertTrue(StudioSession.open("project.3mf", project3mf))
        assertEquals(0, Nav.tab)
        assertEquals("project.3mf", debugDownload.value!!.name)
        assertTrue(debugDownload.value!!.printSheet)
        assertEquals(2, StudioSession.pending.size)

        Nav.tab = 2
        assertFalse(StudioSession.open("notes.pdf", byteArrayOf(1)))
        assertEquals(2, Nav.tab)
    }

    @Test fun sharedFilesWithoutAUsableNameAreNamedByTheirContent() {
        assertEquals("Cube.STL", sharedModelName("Cube.STL", plain3mf))
        assertEquals("part.3mf", sharedModelName("part.3mf", stl))
        assertEquals("1234.3mf", sharedModelName("1234", plain3mf))
        assertEquals("1234.stl", sharedModelName("1234", stl))
        assertEquals("download.bin.3mf", sharedModelName("download.bin", plain3mf))
        assertEquals("notes.pdf", sharedModelName("notes.pdf", plain3mf))
        assertEquals("model.stl", sharedModelName("model", byteArrayOf()))
    }

    @Test fun waitingFilesGoOnThePlateOnceTheProfilesAreLoaded() {
        StudioSession.open("cube.stl", stl)
        StudioSession.open("broken.stl", byteArrayOf(1, 2, 3))
        StudioSession.takePending() // no profiles yet: keeps waiting
        assertEquals(2, StudioSession.pending.size)
        val s = runBlocking { StudioSession.start(StudioTestHost(), null, this).also { StudioSession.takePending() } } // still loading
        assertEquals(2, StudioSession.pending.size)
        StudioSession.takePending()
        assertTrue(StudioSession.pending.isEmpty())
        assertEquals(1, s.scene.objects.size)
        assertTrue(StudioSession.notice!!.startsWith("Couldn't open broken.stl"))
        assertEquals(listOf(StudioScreen.Home, StudioScreen.Plate), StudioSession.nav.stack.toList())
        assertTrue(StudioSession.fullScreen)
        assertEquals(s, StudioSession.start(StudioTestHost(emptyMap()), null))
    }

    @Test fun backLeavesTheEditorBeforeTheApp() {
        Nav.tab = STUDIO_TAB
        StudioSession.nav.go(StudioScreen.Plate); StudioSession.nav.go(StudioScreen.Settings(1))
        Nav.go(Screen.About)
        assertTrue(Nav.back()); assertEquals(StudioScreen.Settings(1), StudioSession.nav.current) // Pandy's own screens first
        assertTrue(Nav.back()); assertEquals(StudioScreen.Plate, StudioSession.nav.current)
        assertTrue(Nav.back()); assertFalse(StudioSession.fullScreen)
        assertFalse(Nav.back())
        StudioSession.nav.go(StudioScreen.Plate)
        Nav.tab = 1
        assertFalse(Nav.back()) // on another tab the editor keeps its place
        assertEquals(StudioScreen.Plate, StudioSession.nav.current)
    }

    @Test fun pickedFilesAreAddedAndFailuresReported() = runBlocking {
        val host = StudioTestHost()
        val s = Studio(host, this).also { it.load().join() }
        assertFalse(pickModels(host, s))
        host.picked = listOf("cube.stl" to stl, "project.3mf" to project3mf)
        assertTrue(pickModels(host, s))
        assertEquals(1, s.scene.objects.size)
        assertTrue(StudioSession.notice!!.startsWith("Couldn't open project.3mf"))
        host.picked = listOf("cube.stl" to stl)
        assertTrue(pickModels(host, s))
        assertNull(StudioSession.notice)
    }

    // ---------- The plate's printer and filaments ----------

    @Test fun studioStartsWithTheConnectedPrinterUnlessTheUserChoseAnother() {
        assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", studio().printer!!.name)
        assertEquals("Bambu Lab A1 mini 0.4 nozzle", studio(preferred = "Bambu Lab A1 mini 0.4 nozzle").printer!!.name)
        assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", studio(preferred = "Bambu Lab H2D 0.4 nozzle").printer!!.name)
        val chosen = StudioTestHost().apply { settings["printer"] = "Bambu Lab X1 Carbon 0.4 nozzle" }
        assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", studio(chosen, "Bambu Lab A1 mini 0.4 nozzle").printer!!.name)
        assertEquals(EngineInfo("02.08.02.61", "e", "r"), studio().engine)
    }

    @Test fun aFailedLoadIsReportedAndCanBeRetried() {
        val host = StudioTestHost(emptyMap())
        val s = studio(host)
        assertTrue(s.loadError != null && s.library == null)
        runBlocking { Studio(StudioTestHost(), this).also { it.load().join(); assertNull(it.loadError) } }
    }

    @Test fun thePlatesFilamentsAreTheOnesItsObjectsUse() {
        val s = studio()
        assertEquals(listOf(mapOf("id" to "1", "type" to "PLA", "color" to "#00AE42")), s.usedFilaments())
        s.setFilament(1, s.filamentChoices.first { it.name.startsWith("Bambu PETG") }, "#112233")
        s.addFile("cube.stl", stl)
        assertEquals(listOf("1"), s.usedFilaments().map { it["id"] })
        s.scene.objects.single().filament = 1
        assertEquals(listOf(mapOf("id" to "2", "type" to "PETG", "color" to "#112233")), s.usedFilaments())
    }

    // ---------- Print ----------

    private fun printer(model: String = "X1C", vararg change: Pair<String, String>): Printer = runBlocking {
        val backend = FakeHomeAssistant(model)
        val changes = mapOf("sensor.print_status" to "idle") + change
        // The demo printer doesn't report every sensor a real one can: those get an entity of their own.
        val demo = backend.printers().single()
        val info = demo.copy(ids = changes.keys.associateWith { "test.${it.substringAfter('.')}" } + demo.ids)
        val json = kotlinx.serialization.json.Json
        val states = Printer(demo, emptyMap()).entityIds.distinct().associateWith { id ->
            val e = json.parseToJsonElement(backend.get("/api/states/$id")).jsonObject
            Entity(id, (e["state"] as kotlinx.serialization.json.JsonPrimitive).content, e["attributes"] as JsonObject)
        } + changes.map { (key, state) -> info.ids.getValue(key).let { it to Entity(it, state, JsonObject(emptyMap())) } }
        Printer(info, states)
    }

    private fun plate(vararg filaments: Map<String, String>, preset: String = "Bambu Lab X1 Carbon 0.4 nozzle") = StudioPrint(byteArrayOf(1), "Cube", preset, filaments.toList())
    private fun filament(id: Int, type: String, color: String) = mapOf("id" to "$id", "type" to type, "color" to color)
    private fun hex(color: Long) = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    @Test fun aPlateOnlyPrintsOnAReadyPrinterOfTheModelAndNozzleItWasSlicedFor() {
        val print = plate(filament(1, "PLA", "#FFFFFF"))
        assertNull(studioPrintBlock(print, printer()))
        val name = printer().name
        assertEquals("$name is offline.", studioPrintBlock(print, printer("X1C", "binary_sensor.online" to "off")))
        assertEquals("$name is busy.", studioPrintBlock(print, printer("X1C", "sensor.print_status" to "running")))
        assertEquals("$name is busy.", studioPrintBlock(print, printer("X1C", "sensor.print_status" to "pause")))
        assertEquals(CONTROL_BLOCKED, studioPrintBlock(print, printer("X1C", "binary_sensor.developer_lan_mode" to "off", "binary_sensor.mqtt_encryption" to "on")))
        assertEquals("This plate was sliced for X1 Carbon 0.4 nozzle, and $name is a X1 Carbon 0.6 nozzle. Choose that printer in Studio and slice again.",
            studioPrintBlock(print, printer("X1C", "sensor.nozzle_diameter" to "0.6")))
        assertTrue(studioPrintBlock(plate(filament(1, "PLA", "#FFFFFF"), preset = "Bambu Lab A1 mini 0.4 nozzle"), printer())!!.startsWith("This plate was sliced for A1 mini 0.4 nozzle"))
    }

    @Test fun aPrinterThatHasNotReportedItsStateOrNozzleIsNotAssumedReady() {
        val print = plate(filament(1, "PLA", "#FFFFFF"))
        val name = printer().name
        for (unknown in listOf("unknown", "unavailable")) {
            assertEquals("Pandy doesn't know yet what $name is doing. Try again in a moment.", studioPrintBlock(print, printer("X1C", "sensor.print_status" to unknown)))
            assertEquals("Pandy doesn't know $name's nozzle size yet. Try again in a moment.", studioPrintBlock(print, printer("X1C", "sensor.nozzle_diameter" to unknown)))
        }
        assertEquals("Pandy doesn't know $name's nozzle size yet. Try again in a moment.", studioPrintBlock(print, printer("X1C", "sensor.nozzle_diameter" to "big")))
    }

    @Test fun aSharedProjectIsToldFromAPlainModelBeforeItOpens() {
        assertFalse(StudioSession.isProject("cube.stl", byteArrayOf(1, 2, 3)))
        assertTrue(StudioSession.isProject("broken.3mf", byteArrayOf(1, 2, 3)))
        StudioSession.reset()
        assertTrue(StudioSession.open("broken.3mf", byteArrayOf(1, 2, 3), project = false))
        assertEquals(STUDIO_TAB, Nav.tab)
        StudioSession.reset()
    }

    @Test fun eachFilamentPrintsFromTheLoadedSpoolOfItsTypeAndNearestColour() {
        val p = printer()
        val spools = p.loadedSpools().filter { it.second.known }
        val (tray, spool) = spools.first()
        val other = spools.first { it.second.type != spool.type || it.second.color != spool.color }
        val print = plate(filament(1, spool.type, hex(spool.color)), filament(3, other.second.type, hex(other.second.color)))
        val trays = studioTrays(print, p)
        assertEquals(spool.type to spool.color, p.loadedSpools().first { it.first == trays[0] }.second.let { it.type to it.color })
        assertEquals(other.second.type to other.second.color, p.loadedSpools().first { it.first == trays[1] }.second.let { it.type to it.color })
        // The file's slots are numbered from 1; a slot the plate doesn't use is left unmapped.
        assertEquals(listOf(trays[0], -1, trays[1]), studioMapping(print, p))
    }

    @Test fun aMissingFilamentStopsThePrint() {
        val p = printer()
        val spool = p.loadedSpools().first { it.second.known }.second
        val print = plate(filament(1, spool.type, hex(spool.color)), filament(2, "PA-CF", "#000000"))
        assertEquals(-1, studioTrays(print, p)[1])
        assertTrue(studioTrays(print, p)[0] >= 0)
        assertNull(studioMapping(print, p))
        assertNull(studioMapping(plate(), p))
        assertNull(studioMapping(plate(mapOf("type" to spool.type, "color" to "#FFFFFF")), p))
    }

    @Test fun traysAreNamedAsOnThePrinter() {
        assertEquals(listOf("AMS A1", "AMS A4", "AMS B1", "AMS D4", "AMS HT A", "AMS HT B", "External spool", "External spool 2", "No matching filament loaded"),
            listOf(0, 3, 4, 15, 128, 129, 254, 255, -1).map(::trayLabel))
    }
}
