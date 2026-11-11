// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import io.github.gccammelli.pandy.PlatformUi
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.slicer.EngineChoice
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.ui.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Studio's settings, sheets and home screen, driven on a Studio loaded from the small test profile set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val schema = """{"layer_height":{"type":"coFloat","label":"Layer height","tooltip":"Height of each layer","sidetext":"mm","min":0.05,"max":0.6},
        "sparse_infill_pattern":{"type":"coEnum","label":"Infill pattern","enum":["grid","gyroid"],"enum_labels":["Grid","Gyroid"],"tooltip":"Pattern of the infill"},
        "enable_support":{"type":"coBool","label":"Enable support","tooltip":"Generate supports"},
        "wall_loops":{"type":"coInt","label":"Wall loops","min":1.0,"max":20.0},
        "sparse_infill_density":{"type":"coPercent","label":"Infill density","min":0.0,"max":100.0}}"""
    private val layout = """{"process":[{"page":"Quality","groups":[{"title":"Layers","options":["layer_height","wall_loops"]}]},
        {"page":"Strength","groups":[{"title":"Infill","options":["sparse_infill_pattern","sparse_infill_density"]},{"title":"Walls","options":["enable_support","ghost_option"]}]},
        {"page":"Dependencies","groups":[{"title":"Hidden","options":["layer_height"]}]}],"filament":[]}"""
    private val files = studioTestProfiles.toMutableMap().apply {
        put("profiles/BBL/process/std.json", """{"type":"process","name":"0.20mm Standard @BBL X1C","instantiation":"true","layer_height":"0.2","wall_loops":"2","sparse_infill_pattern":"grid",
            "enable_support":"0","sparse_infill_density":"15%","compatible_printers":["Bambu Lab X1 Carbon 0.4 nozzle","Bambu Lab A1 mini 0.4 nozzle"]}""")
        put("pandy/schema.json", schema); put("pandy/layout.json", layout)
    }
    private val picked = mutableListOf<Pair<String, ByteArray>>()
    private val host = object : Platform by StudioTestHost(files) {
        override suspend fun pickModels() = picked.toList().also { picked.clear() }
        override suspend fun engineInfo() = EngineInfo("02.08.02.61", "enginehash", "resourcehash")
        override suspend fun engines() = listOf(EngineChoice("bambu", "Bambu Studio", "02.08.02.61"), EngineChoice("orca", "OrcaSlicer", "2.5"))
    }
    private val studio = runBlocking { Studio(host, this).also { it.load().join() } }
    private val nav = StudioNav()

    @Before @After fun clean() { StudioSession.reset(); StudioSession.notice = null }

    private val ui = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = done(null)
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override val canNotify = true
        override fun requestNotifications() = Unit
        override fun watchInBackground(on: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(io.github.gccammelli.pandy.LocalPlatform provides ui, LocalPlatform provides host) { AppTheme(content) }
    }
    private fun field() = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onLast()
    private fun text(t: String) = compose.onNodeWithText(t)
    private fun count(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().size

    // ---------- Settings ----------

    @Test fun settingsListEachPageWithTheValuesInUseAndMarkChanges() {
        show { SettingsScreen(studio, nav, 0) }
        text("Print settings").assertExists()
        text("Quality").assertExists(); text("Strength").assertExists(); assertEquals(0, count("Dependencies"))
        text("Layer height").assertExists(); text("Height of each layer").assertExists(); text("0.2 mm").assertExists(); text("Wall loops").assertExists()
        text("Reset all").assertDoesNotExist()
        studio.set("wall_loops", Setting.One("4"))
        compose.waitForIdle()
        text("Quality · 1").assertExists(); text("4").assertExists(); text("Reset all").assertExists()
        text("Strength").performClick()
        text("Infill pattern").assertExists(); text("Grid").assertExists(); text("15%").assertExists(); assertEquals(0, count("ghost_option"))
        text("Reset all").performClick()
        compose.runOnIdle { assertTrue(studio.overrides.isEmpty()) }
        text("Reset all").assertDoesNotExist()
    }

    @Test fun theSettingsSearchFindsByNameKeyOrHelpAcrossPages() {
        show { SettingsScreen(studio, nav, 1) }
        text("Enable support").assertExists(); text("Layer height").assertDoesNotExist()
        field().performTextInput("layer")
        text("Layer height").assertExists(); text("Enable support").assertDoesNotExist(); text("Strength").assertDoesNotExist()
        field().performTextReplacement("pattern of")
        text("Infill pattern").assertExists(); text("Layer height").assertDoesNotExist()
    }

    @Test fun editingASettingValidatesNumbersAndAppliesOnConfirm() {
        show { SettingsScreen(studio, nav, 0) }
        text("Layer height").performClick()
        assertEquals(2, count("Height of each layer")); text("Min 0.05 · Max 0.6 · Preset: 0.2").assertExists()
        field().performTextReplacement("abc")
        text("Enter a number").assertExists(); text("Apply").assertIsNotEnabled()
        field().performTextReplacement("0.9")
        text("At most 0.6").assertExists()
        field().performTextReplacement("0.28")
        text("Apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(mapOf("layer_height" to Setting.One("0.28")), studio.overrides.toMap()) }
        text("Layer height").assertExists(); text("0.28 mm").assertExists()
        text("Layer height").performClick(); text("Reset to the preset's value").performClick()
        compose.runOnIdle { assertTrue(studio.overrides.isEmpty()) }
    }

    @Test fun enumAndSwitchSettingsChangeInPlaceOrFromTheirSheet() {
        show { SettingsScreen(studio, nav, 1) }
        text("Infill pattern").performClick()
        assertEquals(2, count("Pattern of the infill")); compose.onAllNodesWithText("Gyroid").onLast().performClick()
        compose.runOnIdle { assertEquals(Setting.One("gyroid"), studio.overrides["sparse_infill_pattern"]) }
        text("Gyroid").assertExists(); assertEquals(1, count("Pattern of the infill")) // sheet closed
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        compose.runOnIdle { assertEquals(Setting.One("1"), studio.overrides["enable_support"]) }
        text("Enable support").performClick()
        assertEquals(2, count("Generate supports"))
        text("Reset to the preset's value").performClick()
        compose.runOnIdle { assertFalse("enable_support" in studio.overrides) }
    }

    @Test fun percentSettingsRejectNothingButNumbersOutOfRange() {
        show { SettingsScreen(studio, nav, 1) }
        text("Infill density").performClick()
        field().performTextReplacement("120%")
        text("At most 100").assertExists()
        field().performTextReplacement("30%")
        text("Apply").performClick()
        compose.runOnIdle { assertEquals(Setting.One("30%"), studio.overrides["sparse_infill_density"]) }
    }

    @Test fun theAboutScreenShowsTheEngineAndItsHashes() {
        show { AboutScreen(studio, nav) }
        text("About the engine").assertExists(); text("Bambu Studio 02.08.02.61").assertExists(); text("enginehash").assertExists(); text("resourcehash").assertExists()
    }

    // ---------- Sheets ----------

    @Test fun thePrinterAndQualitySheetsSwitchThePreset() {
        var closed = 0
        show { PrinterSheet(studio) { closed++ } }
        text("Printer").assertExists(); text("A1 mini").assertExists()
        text("X1 Carbon").assertExists()
        compose.onAllNodesWithText("0.4 mm")[studio.printers.indexOfFirst { it.displayName == "A1 mini" }].performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals("Bambu Lab A1 mini 0.4 nozzle", studio.printer!!.name) }
    }

    @Test fun theProcessSheetListsLayerHeightsAndChoosesOne() {
        var closed = 0
        show { ProcessSheet(studio) { closed++ } }
        text("Quality").assertExists(); text("0.20mm Standard").assertExists(); text("Layer height 0.2 mm").assertExists()
        text("0.20mm Standard").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun theFilamentSheetRecoloursSearchesAndRemoves() {
        studio.setFilament(1, studio.filamentChoices.first { "PETG" in it.name }, "#161616")
        var closed = 0
        show { FilamentSheet(studio, 1) { closed++ } }
        text("Filament 2").assertExists(); text("Bambu PETG HF").assertExists(); text("Bambu PLA Basic").assertExists(); text("Remove this filament").assertExists()
        field().performTextInput("pla")
        text("Bambu PETG HF").assertDoesNotExist(); text("Bambu PLA Basic").assertExists()
        text("Bambu PLA Basic").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals("Bambu PLA Basic @BBL X1C", studio.filaments[1].first.name); assertEquals("#161616", studio.filaments[1].second) }
    }

    @Test fun removingAFilamentFromItsSheetDropsTheSlot() {
        studio.setFilament(1, studio.filamentChoices.first { "PETG" in it.name })
        show { FilamentSheet(studio, 1) {} }
        text("Remove this filament").performClick()
        compose.runOnIdle { assertEquals(1, studio.filaments.size) }
    }

    @Test fun validateAndWithValueFollowTheOptionKind() {
        val num = io.github.gccammelli.pandy.studio.slicer.OptionDef("coFloat", min = 1.0, max = 2.0)
        val int = io.github.gccammelli.pandy.studio.slicer.OptionDef("coInt")
        val pct = io.github.gccammelli.pandy.studio.slicer.OptionDef("coPercent")
        assertEquals(null, validate(null, "x")); assertEquals(null, validate(io.github.gccammelli.pandy.studio.slicer.OptionDef("coString"), "x"))
        assertEquals("This setting takes a number, not a percentage", validate(num, "1%")); assertEquals(null, validate(pct, "5%"))
        assertEquals("Enter a whole number", validate(int, "1.5")); assertEquals("At least 1", validate(num, "0.5")); assertEquals("At most 2", validate(num, "3"))
        assertEquals(null, validate(num, " 1.5 "))
        assertEquals(Setting.Many(listOf("5", "5")), withValue(Setting.Many(listOf("1", "2")), "5")); assertEquals(Setting.Many(listOf("5")), withValue(Setting.Many(emptyList()), "5"))
        assertEquals(Setting.One("5"), withValue(null, "5"))
    }

    // ---------- Home ----------

    @Test fun homeStartsPlatesFromSamplesAndShowsPrinterAndEngine() {
        val n = StudioNav()
        show { Home(studio, n) }
        text("New plate").assertExists(); text("X1 Carbon 0.4 nozzle").assertExists(); text("Bambu Studio 02.08.02.61").assertExists()
        text("Back to the plate (1)").assertDoesNotExist()
        text("Gear").performClick()
        compose.runOnIdle { assertEquals(listOf("Gear"), studio.scene.objects.map { it.name }); assertEquals(StudioScreen.Plate, n.current) }
    }

    @Test fun homeOpensPickedModelsAndReportsFilesItCannotOpen() {
        val n = StudioNav()
        picked += "bad.stl" to byteArrayOf(1, 2, 3)
        show { Home(studio, n) }
        text("Open a model").performClick()
        compose.waitUntil(5000) { StudioSession.notice != null }
        compose.runOnIdle { assertTrue(StudioSession.notice!!.startsWith("Couldn't open bad.stl")); assertEquals(StudioScreen.Home, n.current) }
        text(StudioSession.notice!!).assertExists()
        picked += "cube.stl" to Stl.writeBinary(Shapes.box(10f, 10f, 10f))
        text("Open a model").performClick()
        compose.waitUntil(5000) { n.current == StudioScreen.Plate }
        compose.runOnIdle { assertEquals(listOf("cube"), studio.scene.objects.map { it.name }); assertEquals(null, StudioSession.notice) }
    }

    @Test fun homeLetsYouReturnToThePlateAndChangePrinter() {
        val n = StudioNav(); studio.addSample("cube", Shapes.box(10f, 10f, 10f))
        show { Home(studio, n) }
        text("Back to the plate (1)").performClick()
        compose.runOnIdle { assertEquals(StudioScreen.Plate, n.current) }
        text("Printer").performClick()
        text("A1 mini").assertExists()
    }

    @Test fun theEngineSheetOffersEnginesAboutAndTheLoadErrorRetry() {
        val n = StudioNav()
        show { Home(studio, n) }
        text("Slicing engine").performClick()
        text("OrcaSlicer 2.5").assertExists(); text("Reload profiles").assertExists()
        assertEquals(1, count("Changing engines keeps your models"))
        text("About this engine").performClick()
        compose.runOnIdle { assertEquals(StudioScreen.About, n.current) }
    }

    @Test fun anEngineThatFailedToLoadShowsWhyAndCanRetry() {
        val broken = runBlocking { Studio(StudioTestHost(emptyMap()), this).also { it.load().join() } }
        show { Home(broken, StudioNav()) }
        assertEquals(2, count("Unavailable")) // the printer row and the engine row
        assertEquals(1, count("Couldn't load slicer profiles")); text("Try again").assertExists()
    }
}
