package io.github.gccammelli.pandy

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gccammelli.pandy.studio.ui.STUDIO_TAB
import io.github.gccammelli.pandy.studio.ui.StudioPrint
import io.github.gccammelli.pandy.studio.ui.StudioPrintSheet
import io.github.gccammelli.pandy.studio.ui.StudioSession
import io.github.gccammelli.pandy.studio.ui.StudioTestHost
import io.github.gccammelli.pandy.ui.AppTheme
import io.github.gccammelli.pandy.ui.Nav
import io.github.gccammelli.pandy.ui.Tabs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Studio tab in Pandy's bottom bar: where it shows, getting the slicer, and the print sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioTabTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var host: StudioTestHost? = StudioTestHost()
    private var pluginInstalled = false
    private var installs = 0
    private var installError: Throwable? = null

    private val platform = object : PlatformUi {
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
        override val studio get() = host
        override fun phoneSlicerAvailable() = pluginInstalled
        override suspend fun installSlicerPlugin(progress: (Float) -> Unit) { installs++; progress(.5f); installError?.let { throw it } }
    }

    private lateinit var st: UiState
    private val printer get() = st.printers!!.single()

    @Before fun demo() {
        Repo.stop()
        val values = mutableMapOf("demo" to "true")
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : SecretStore {
            private val secrets = mutableMapOf<String, String>()
            override fun get(key: String): String? = secrets[key]
            override fun set(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        st = runBlocking {
            val backend = FakeHomeAssistant()
            for (field in listOf("ha", "extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible = true }.set(Repo, backend)
            val infos = backend.printers()
            val states = infos.flatMap { Printer(it, emptyMap()).entityIds }.distinct().associateWith { id ->
                val e = json.parseToJsonElement(backend.get("/api/states/$id")).jsonObject
                Entity(id, e.s("state")!!, e["attributes"]!!.jsonObject)
            }
            UiState(on = true, printers = infos.map { Printer(it, states) }, live = true)
        }
        Repo.state.value = st
        Repo.seeModelsTip()
        StudioSession.reset(); Nav.stack.clear(); Nav.tab = 1
    }

    @After fun stop() { Repo.stop(); StudioSession.reset(); Nav.stack.clear(); Nav.tab = 1 }

    private fun show(content: @Composable () -> Unit) = compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme(content) } }

    @Test fun pandyWithoutAPhoneSlicerHasNoStudioTab() {
        host = null
        Nav.tab = STUDIO_TAB // a tab that isn't there falls back to the last one
        show { Tabs(st, printer) }
        compose.onNodeWithText("Studio").assertDoesNotExist()
        compose.onAllNodesWithText("Me").onLast().assertExists()
    }

    @Test fun theStudioTabOffersTheSlicerUntilItIsInstalledThenOpensTheEditor() {
        show { Tabs(st, printer) }
        compose.onNodeWithText("Studio").performClick()
        compose.runOnIdle { assertEquals(STUDIO_TAB, Nav.tab) }
        compose.onNodeWithText("Get the slicer").assertExists()
        compose.onNodeWithText("New plate").assertDoesNotExist()
        compose.onNodeWithText(io.github.gccammelli.pandy.studio.ui.SLICER_CONSENT).assertExists()
        compose.onNodeWithText("Download and install").assertExists() // nothing is fetched until it is tapped

        installError = ApiException("The slicer isn't published yet.")
        compose.onNodeWithText("Download and install").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("The slicer isn't published yet.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Download and install").assertIsEnabled()

        installError = null
        compose.onNodeWithText("Download and install").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Install again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The slicer isn't published yet.").assertDoesNotExist()
        compose.onNodeWithText("Confirm the installation when Android asks. Studio opens here as soon as it is done.").assertExists()
        assertEquals(2, installs)

        pluginInstalled = true // Android finished installing it: the tab notices by itself
        compose.mainClock.advanceTimeBy(1500)
        compose.waitUntil(5000) { compose.onAllNodesWithText("New plate").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Get the slicer").assertDoesNotExist()
        // The connected printer (the demo's X1 Carbon) is the one Studio starts with.
        compose.waitUntil(5000) { compose.onAllNodesWithText("X1 Carbon 0.4 nozzle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bambu Studio 02.08.02.61").assertExists()
        compose.onAllNodesWithText("Devices").onLast().assertExists() // the tab bar stays on Studio's first screen
    }

    @Test fun printingAStudioPlateAsksBeforeItStarts() {
        var closed = 0
        val print = StudioPrint(byteArrayOf(1), "Cube.gcode.3mf", "Bambu Lab X1 Carbon 0.4 nozzle", listOf(mapOf("id" to "1", "type" to "PLA", "color" to "#00AE42")))
        Nav.tab = STUDIO_TAB
        show { StudioPrintSheet(print, printer) { closed++ } }
        compose.onNodeWithText("Cube").assertExists()
        compose.onNodeWithText("${printer.name} · X1 Carbon 0.4 nozzle").assertExists()
        compose.onNodeWithText("1 · PLA").assertExists()
        compose.onNodeWithText("Start demo print?").assertDoesNotExist()
        compose.onNodeWithText("Print (demo)").performClick()
        compose.onNodeWithText("Start demo print?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Start demo print?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, closed); assertEquals(STUDIO_TAB, Nav.tab) }

        compose.onNodeWithText("Print (demo)").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertEquals(1, Nav.tab); assertNull(StudioSession.print) }
    }
}
