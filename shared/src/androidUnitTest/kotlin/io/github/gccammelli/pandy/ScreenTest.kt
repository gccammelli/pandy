package io.github.gccammelli.pandy

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.github.gccammelli.pandy.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val platform = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = done(null)
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }

    private lateinit var st: UiState
    private val printer get() = st.printers!!.single()

    @Before fun home() {
        val values = mutableMapOf("demo" to "true")
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : SecretStore {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) = Unit
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        st = runBlocking {
            val backend = FakeHomeAssistant()
            val infos = backend.printers()
            val states = infos.flatMap { Printer(it, emptyMap()).entityIds }.distinct().associate { id ->
                val e = json.parseToJsonElement(backend.get("/api/states/$id")).jsonObject
                id to Entity(id, e.s("state")!!, e["attributes"]!!.jsonObject)
            }
            UiState(on = true, printers = infos.map { Printer(it, states) }, live = true)
        }
        Repo.state.value = st
        Nav.stack.clear()
        Nav.tab = 0
    }

    private fun show(title: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme(content) } }
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun connectionShowsDirectSetup() = show("Pandy") { Connect() }
    @Test fun devicesShowPrinter() = show(printer.name) { DevicesTab(st, printer) }
    @Test fun controlsShowTemperatures() = show("Device Control") { ControlScreen(printer) }
    @Test fun motionShowsAxes() = show("XYZ") { MotionScreen(printer) }
    @Test fun skipShowsObjects() = show("Skip Parts") { SkipScreen(printer) }
    @Test fun filamentShowsSpools() = show("Filament") { FilamentScreen(printer) }
    @Test fun settingsShowPrinter() = show("Settings") { PrinterSettingsScreen(printer) }
    @Test fun calibrationShowsOptions() = show("Calibration") { CalibrationScreen(printer) }
    @Test fun optionsShowMonitoring() = show("Print Options") { PrintOptionsScreen(printer) }
    @Test fun amsShowsSettings() = show("AMS Settings") { AmsSettingsScreen(printer) }
    @Test fun liveViewShowsSettings() = show("Liveview Settings") { LiveviewSettingsScreen() }
    @Test fun assistantShowsProblems() = show("Machine Assistant") { AssistantScreen(printer) }
    @Test fun historyShowsPreviousPrints() = show("History") { HistoryScreen(printer) }
    @Test fun filesShowStorage() = show("Files") { FilesScreen(printer) }
    @Test fun preferencesShowSettings() = show("Settings") { SettingsScreen() }
    @Test fun aboutShowsVersion() = show("About Pandy") { AboutScreen() }
    @Test fun slicerShowsConfiguration() = show("Slicer") { SlicerScreen() }

    @Test fun speedShowsAllSupportedOptions() {
        show("Device Control") { ControlScreen(printer) }
        compose.onNodeWithText("Speed").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Silent (50%)").assertExists()
        compose.onNodeWithText("Ludicrous (166%)").assertExists()
    }
    @Test fun nozzleShowsTemperatureAndExtruder() {
        show("Device Control") { ControlScreen(printer) }
        compose.onNodeWithText("Nozzle & Extruder").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Nozzle & Extruder").onLast().assertIsDisplayed()
    }
    @Test fun bedShowsTemperaturePresets() {
        show("Device Control") { ControlScreen(printer) }
        compose.onNodeWithText("Heatbed").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Heatbed temperature").assertIsDisplayed()
    }
    @Test fun chamberExplainsReadOnlyTemperature() {
        show("Device Control") { ControlScreen(printer) }
        compose.onNodeWithText("Chamber").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Chamber temperature").assertExists()
    }
    @Test fun fanShowsSpeedControls() {
        show("Device Control") { ControlScreen(printer) }
        compose.onNodeWithText("Fan").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Fan").onLast().assertIsDisplayed()
    }
    @Test fun haConnectionShowsTokenField() = show("Home Assistant address") { AddHaScreen() }
    @Test fun directConnectionShowsLanForm() = show("Direct LAN connection") { AddDirectScreen() }
    @Test fun meShowsAccountActions() = show("Settings") { MeTab(st) }
    @Test fun rootShowsSelectedPrinter() = show("Devices") { Root() }

    @Test fun makerWorldProjectCanBePreparedAndPreviewedWithoutPrinting() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","filament_type":["PLA","PETG"],"filament_colour":["#FFFFFF","#00AE42"]}""")
                put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Body"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>""")
                put("3D/3dmodel.model", """<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2" p1="1"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
            }
        }.toByteArray()
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) {
            AppTheme { PrintModelSheet(WebDownload(null, "Test model.3mf", null, bytes, true), {}) }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Prepare to Print").assertIsDisplayed()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Model colors").assertIsDisplayed()
        compose.onNodeWithText("Printer colors").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Customize colors").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Done").assertExists()
    }
}

