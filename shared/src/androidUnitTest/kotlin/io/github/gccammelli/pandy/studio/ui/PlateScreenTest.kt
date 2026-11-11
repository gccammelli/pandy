// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onFirst
import io.github.gccammelli.pandy.PlatformUi
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.gcode.GcodeParser
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.slicer.SliceOutcome
import io.github.gccammelli.pandy.studio.slicer.SliceRun
import io.github.gccammelli.pandy.studio.slicer.Setting
import io.github.gccammelli.pandy.studio.studio.SliceState
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.studio.studio.ViewMode
import io.github.gccammelli.pandy.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plate editor: top bar, chips, object tools, quick settings and the preview panel (the 3D view itself is not asserted on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlateScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<Pair<String, ByteArray>>()
    private val sliceCalls = mutableListOf<String>()
    private val files = studioTestProfiles.toMutableMap().apply {
        put("profiles/BBL/process/std.json", """{"type":"process","name":"0.20mm Standard @BBL X1C","instantiation":"true","layer_height":"0.2","wall_loops":"2","enable_support":"0",
            "sparse_infill_density":"15%","support_type":"tree(auto)","compatible_printers":["Bambu Lab X1 Carbon 0.4 nozzle","Bambu Lab A1 mini 0.4 nozzle"]}""")
        put("pandy/schema.json", """{"wall_loops":{"type":"coInt","label":"Wall loops","min":1.0,"max":20.0},"sparse_infill_density":{"type":"coPercent","label":"Infill density"}}""")
    }
    private val host = object : Platform by StudioTestHost(files) {
        override suspend fun pickModels() = picked.toList().also { picked.clear() }
        override suspend fun slice(job: io.github.gccammelli.pandy.studio.slicer.SliceJob, onLog: (String) -> Unit): SliceRun {
            sliceCalls += job.process; error("engine unavailable")
        }
    }
    private val saved = mutableListOf<String>()
    private val ui = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) { saved += name; done(null) }
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override val canNotify = true
        override fun requestNotifications() = Unit
        override fun watchInBackground(on: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }
    private val studio = Studio(host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).also { runBlocking { it.load().join() } }
    private val nav = StudioNav()
    private val scene get() = studio.scene

    @Before @After fun clean() { StudioSession.reset(); StudioSession.notice = null }

    private fun show() = compose.setContent {
        CompositionLocalProvider(io.github.gccammelli.pandy.LocalPlatform provides ui, LocalPlatform provides host) { AppTheme { PlateScreen(studio, nav) } }
    }
    private fun text(t: String) = compose.onNodeWithText(t)
    private fun cube() { studio.addSample("Mochi", Shapes.box(20f, 20f, 20f)) }
    private fun count(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().size

    @Test fun anEmptyPlateInvitesAModelAndTheTopBarShowsPrinterQualityAndFilament() {
        picked += "Luna.stl" to Stl.writeBinary(Shapes.box(10f, 10f, 10f))
        show()
        text("The plate is empty").assertExists(); text("X1 Carbon 0.4").assertExists(); text("0.20mm Standard").assertExists(); text("PLA").assertExists()
        text("Slice").assertExists(); text("Preview").assertExists()
        text("Add a model").performClick()
        compose.waitUntil(5000) { scene.objects.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("Luna"), scene.objects.map { it.name }) }
        text("The plate is empty").assertDoesNotExist()
    }

    @Test fun theChipsOpenTheirSheetsAndTheBackArrowLeavesThePlate() {
        nav.go(StudioScreen.Plate)
        show()
        text("X1 Carbon 0.4").performClick(); text("Printer").assertExists(); compose.onNodeWithContentDescription("Close").performClick()
        text("0.20mm Standard").performClick(); text("Quality").assertExists(); compose.onNodeWithContentDescription("Close").performClick()
        text("PLA").performClick(); text("Filament 1").assertExists(); compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertEquals(StudioScreen.Plate, nav.current) }
    }

    @Test fun theObjectPanelRotatesScalesTipsDuplicatesAndDeletes() {
        cube(); show()
        text("Mochi").assertExists(); text("20.0 × 20.0 × 20.0 mm").assertExists(); text("100%").assertExists()
        val o = scene.objects.single()
        text("+90° Z").performClick(); compose.runOnIdle { assertEquals(90f, o.rotZ) }
        text("−90° Z").performClick(); text("−90° Z").performClick(); compose.runOnIdle { assertEquals(-90f, o.rotZ) }
        text("Tip over").performClick(); compose.runOnIdle { assertEquals(90f, o.rotX) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        compose.runOnIdle { assertEquals(2f, o.scale.x, 0.01f) }
        text("200%").assertExists(); text("40.0 × 40.0 × 40.0 mm").assertExists()
        text("Reset").performClick()
        compose.runOnIdle { assertEquals(0f, o.rotX); assertEquals(0f, o.rotZ); assertEquals(1f, o.scale.x) }
        text("Duplicate").performClick(); compose.runOnIdle { assertEquals(2, scene.objects.size) }
        text("Delete").performScrollTo().performClick(); compose.runOnIdle { assertEquals(listOf(1L), scene.objects.map { it.id }); assertNull(scene.selected) }
        text("Add").assertExists() // back to the plate tools
    }

    @Test fun deselectingShowsThePlateToolsWhichArrangeAndClear() {
        cube(); studio.addSample("Robbie", Shapes.box(20f, 20f, 20f))
        show()
        text("Robbie").assertExists()
        compose.onNodeWithContentDescription("Deselect").performClick()
        text("Arrange").assertIsEnabled(); text("Clear").assertIsEnabled()
        scene.objects.forEach { it.x = 40f; it.y = 40f }
        text("Arrange").performClick()
        compose.runOnIdle {
            val a = scene.objects[0].worldBounds(); val b = scene.objects[1].worldBounds()
            assertTrue(a.max.x <= b.min.x || b.max.x <= a.min.x || a.max.y <= b.min.y || b.max.y <= a.min.y)
        }
        text("Clear").performClick()
        compose.runOnIdle { assertTrue(scene.objects.isEmpty()); assertNull(scene.selected) }
        text("Arrange").assertIsNotEnabled(); text("Clear").assertIsNotEnabled(); text("The plate is empty").assertExists()
    }

    @Test fun quickSettingsToggleSupportsAndEditInfillAndWalls() {
        cube(); scene.selected = null
        show()
        text("No supports").performClick()
        compose.runOnIdle { assertEquals(Setting.One("1"), studio.overrides["enable_support"]) }
        text("Supports: on").performClick()
        compose.runOnIdle { assertTrue(studio.overrides.isEmpty()) }
        text("Infill 15%").performClick(); text("Infill density").assertExists(); compose.onNodeWithContentDescription("Close").performClick()
        text("Walls 2").performClick(); text("Wall loops").assertExists(); compose.onNodeWithContentDescription("Close").performClick()
        text("Brim: auto_brim").assertExists()
        compose.runOnIdle { studio.set("wall_loops", Setting.One("3")) }
        text("All settings · 1 changed").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(StudioScreen.Settings(), nav.current) }
    }

    @Test fun theSettingsToolAndTheAddToolWork() {
        picked += "Luna.stl" to Stl.writeBinary(Shapes.box(10f, 10f, 10f))
        cube(); scene.selected = null
        show()
        text("Settings").performClick()
        compose.runOnIdle { assertEquals(StudioScreen.Settings(), nav.current) }
        text("Add").performClick()
        compose.waitUntil(5000) { scene.objects.size == 2 }
    }

    @Test fun objectsOutsideTheBedAreFlaggedAndBlockSlicing() {
        cube(); show()
        text("Slice").performClick()
        compose.waitUntil(5000) { sliceCalls.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("0.20mm Standard @BBL X1C"), sliceCalls); assertEquals(SliceState.Failed("engine unavailable"), studio.state) }
        text("engine unavailable").assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.runOnIdle { assertEquals(SliceState.Idle, studio.state) }
        compose.runOnIdle { scene.objects.single().x = 900f; scene.touch() }
        text("1 object is outside the printable area").assertExists()
        sliceCalls.clear(); text("Slice").performClick(); compose.runOnIdle { assertTrue(sliceCalls.isEmpty()) }
    }

    @Test fun aSlicingCardShowsProgressAndCancels() {
        cube(); show()
        studio.state = SliceState.Running("Generating walls", .4f)
        text("Generating walls").assertExists(); text("Slicing").assertExists()
        text("Cancel").performClick()
        compose.runOnIdle { assertEquals(SliceState.Idle, studio.state) }
        text("Generating walls").assertDoesNotExist()
    }

    @Test fun aNoticeFromOpeningFilesCanBeDismissed() {
        cube(); StudioSession.notice = "Couldn't open broken.stl: bad file"
        show()
        text("Couldn't open broken.stl: bad file").assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.runOnIdle { assertNull(StudioSession.notice) }
    }

    private val gcode = "; total estimated time: 1h 41m 21s\n; total filament length [mm] : 3716.44\n; total filament weight [g] : 11.17\nM83\n" +
        "; CHANGE_LAYER\n; Z_HEIGHT: 0.2\n; FEATURE: Outer wall\nG1 X1 Y0 Z0.2 E0.5\n; FEATURE: Sparse infill\nG1 X2 Y0 E0.5\n; CHANGE_LAYER\n; Z_HEIGHT: 0.4\n; FEATURE: Outer wall\nG1 X3 Y0 Z0.4 E0.5\n"
    private fun sliced(warnings: List<String> = emptyList()) {
        val model = GcodeParser.parse(gcode)
        val run = SliceRun(SliceOutcome(0, "Success."), 0, byteArrayOf(1, 2, 3), 10, emptyList(), null)
        scene.preview = model; scene.layerLow = 1; scene.layerHigh = model.layers.size; scene.mode = ViewMode.PREVIEW
        studio.state = SliceState.Done(run, model, warnings)
    }

    @Test fun thePreviewPanelShowsTheResultsAndHandsThePlateToPrinting() {
        cube(); sliced(); show()
        text("1h 41m").assertExists(); text("11.2 g").assertExists(); text("3.72 m").assertExists(); text("Layers").assertExists()
        text("Outer wall").assertExists(); text("Sparse infill").assertExists(); text("Travel").assertDoesNotExist()
        text("0.4").assertExists() // the layer rail shows the top layer's height
        text("Print").performClick()
        compose.runOnIdle {
            val p = assertNotNull(StudioSession.print)
            assertEquals("Mochi.gcode.3mf", p.name); assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", p.printer)
            assertEquals(listOf(mapOf("id" to "1", "type" to "PLA", "color" to "#00AE42")), p.filaments)
        }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertEquals(listOf("Mochi.gcode.3mf"), saved) }
        text("Saved to Downloads.").assertExists()
    }

    @Test fun warningsBlockPrintingAndTreeSupportsCarryANote() {
        cube(); studio.set("enable_support", Setting.One("1")); sliced(listOf("Nozzle temperature too high"))
        show()
        text("Nozzle temperature too high").assertExists()
        assertEquals(1, count("Tree supports: the part matches Bambu Studio"))
        text("Print").performClick()
        compose.runOnIdle { assertNull(StudioSession.print) }
    }

    @Test fun theSegmentedSwitchMovesBetweenPrepareAndPreview() {
        cube(); show()
        text("Preview").performClick()
        compose.runOnIdle { assertEquals(ViewMode.PREPARE, scene.mode) } // no slice yet: disabled
        sliced(); compose.waitForIdle()
        text("Prepare").performClick(); compose.runOnIdle { assertEquals(ViewMode.PREPARE, scene.mode) }
        text("Preview").performClick(); compose.runOnIdle { assertEquals(ViewMode.PREVIEW, scene.mode) }
    }

    @Test fun formattersAndColoursFollowTheirRules() {
        assertEquals("1h 41m", duration(6081)); assertEquals("2m 5s", duration(125))
        assertEquals("1.3", 1.26f.fmt()); assertEquals("3", 2.6f.fmt(0)); assertEquals("1.25", 1.254f.fmt(2)); assertEquals("1.25", 1.254f.fmt(9))
        assertEquals(hexColor("#FF7D38"), featureColor(io.github.gccammelli.pandy.studio.gcode.Feature.entries.first()))
        assertEquals(hexColor("#E0409C"), hexColor("zzz"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00AE42), hexColor("#00AE42"))
    }
}
