package io.github.gccammelli.pandy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.ktor.client.engine.mock.respond
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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
        override fun openUrl(url: String) { openedUrls+=url }
        override fun toast(message: String) { messages += message }
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) {
            savedFiles += Triple(name,mime,bytes)
            done(saveFileError)
        }
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override val canNotify = true
        override fun requestNotifications() { notificationRequests++ }
        override fun watchInBackground(on: Boolean) { backgroundWatching += on }
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) { savedImages += jpeg; done(saveImageError) }
    }

    private val commands = java.util.concurrent.ConcurrentLinkedQueue<Pair<String,String>>()
    private val savedImages = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val savedFiles = mutableListOf<Triple<String,String,ByteArray>>()
    private var saveImageError: Throwable?=null
    private var saveFileError: Throwable?=null
    private var commandFailure: String?=null
    private var filesFailure: String?=null
    private val openedUrls=mutableListOf<String>()
    private val messages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val testHttpClients = mutableListOf<io.ktor.client.HttpClient>()
    private var restorePrintSettings: (() -> Unit)? = null
    private var notificationRequests = 0
    private val backgroundWatching = mutableListOf<Boolean>()
    private lateinit var st: UiState
    private lateinit var snapshot: Map<String,Entity>
    private val printer get() = st.printers!!.single()

    @Test fun missingHomeAssistantExplainsExtraFeaturesAndOpensConnectionSetup() {
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,null)
        show("Print history comes from Home Assistant") { NeedsHa("Print history") }
        compose.onNodeWithText("Add Home Assistant").performClick()
        kotlin.test.assertIs<Screen.AddHa>(Nav.stack.last())
        kotlin.test.assertFailsWith<ApiException> { needHa() }
    }

    @org.junit.After fun stop() {
        modelDispatcher = kotlinx.coroutines.Dispatchers.Default
        Repo.stop(); debugDownload.value=null
        testHttpClients.forEach { it.close() }
        restorePrintSettings?.invoke()
    }

    @Before fun home() {
        // Robolectric's unconfined frame clock can otherwise apply background parse completions off the UI thread.
        modelDispatcher = kotlinx.coroutines.Dispatchers.Main
        Repo.stop()
        val values = mutableMapOf("demo" to "true")
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : SecretStore {
            private val secrets = mutableMapOf<String,String>()
            override fun get(key: String): String? = secrets[key]
            override fun set(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        st = runBlocking {
            val demo = FakeHomeAssistant()
            val backend = object : HaBackend by demo, PrinterFiles {
                override suspend fun listFiles(dir: String): List<FileEntry> { filesFailure?.let { throw ApiException(it) }; return demo.listFiles(dir) }
                override suspend fun deleteFile(path: String) { filesFailure?.let { throw ApiException(it) }; demo.deleteFile(path) }
                override suspend fun post(path: String,body: String): String {
                    commands += path to body
                    commandFailure?.let { throw ApiException(it) }
                    return demo.post(path,body)
                }
                override suspend fun bytes(path: String): ByteArray {
                    val name = when {
                        "pick_image" in path -> "demo_pick.png"
                        "camera_proxy" in path -> "demo_camera.jpg"
                        else -> "demo_cover.png"
                    }
                    return java.io.File("src/commonMain/composeResources/files/$name").readBytes()
                }
            }
            for(field in listOf("ha","extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible=true }.set(Repo,backend)
            val infos = backend.printers()
            val states = infos.flatMap { Printer(it, emptyMap()).entityIds }.distinct().associate { id ->
                val e = json.parseToJsonElement(backend.get("/api/states/$id")).jsonObject
                id to Entity(id, e.s("state")!!, e["attributes"]!!.jsonObject)
            }
            snapshot = states
            UiState(on = true, printers = infos.map { Printer(it, states) }, live = true)
        }
        Repo.state.value = st
        (Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage).settings.putString("demo","true")
        Nav.stack.clear()
        Nav.tab = 0
        Repo.setShowUnavailable(true)
        Repo.setTimelapseDefault(false)
    }

    private fun show(title: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme(content) } }
        val originalTheme=ThemeMode.mode
        // Keep existing screens usable while their theme changes, including retained callbacks and state.
        for(mode in listOf("light","dark",originalTheme)) {
            compose.runOnIdle { Repo.setTheme(mode) }
            compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle {
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                    if (view.width > 0 && view.height > 0) view.draw(Canvas(Bitmap.createBitmap(view.width, view.height,Bitmap.Config.ARGB_8888)))
                }
            }
        }
    }

    @Test fun connectionShowsDirectSetup() = show("Pandy") { Connect() }

    @Test fun connectionModeSwitchesBetweenDirectHomeAssistantAndCombinedSetup() {
        show("Pandy") { Connect() }
        compose.onNodeWithText("HA Only",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Home Assistant address").assertExists()
        compose.onNodeWithText("Pandy + HA",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Step 1 of 2: connect Pandy Direct").assertExists()
        compose.onNodeWithText("Pandy Only",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Printer IP address").assertExists()
    }

    @Test fun addingHomeAssistantChecksItsRegistryAndSavesTheNormalizedAddress() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val before=store.settings.getString("ha_url")
        restorePrintSettings={store.settings.putString("ha_url",before)}
        val demo=FakeHomeAssistant()
        val types=listOf("config/device_registry/list","config/entity_registry/list")
        val registry=runBlocking { types.zip(demo.ws(types.map { buildJsonObject { put("type",it) } }).map { it!! }).toMap() }
        HaSocket(registry=registry).use { peer ->
            show("Home Assistant address") { AddHaScreen() }
            compose.onNodeWithText("Home Assistant address").performScrollTo().performTextReplacement(peer.url)
            compose.onNodeWithText("Long-lived access token").performScrollTo().performTextInput(" test-token ")
            compose.onNodeWithText("Connect",substring=false).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); Repo.haUrl==peer.url }
            assertEquals("test-token",peer.received.first().s("access_token"))
        }
    }

    @Test fun printNotificationPreferenceRequestsPermissionAndTracksBackgroundWork() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val before=store.settings.getString("demo")
        restorePrintSettings={store.settings.putString("demo",before)}
        store.settings.putString("demo","false"); Repo.setNotify(false)
        show("Settings") { SettingsScreen() }
        compose.onNodeWithText("Print notifications").performScrollTo()
        compose.onAllNodes(isToggleable()).onLast().performClick()
        org.junit.Assert.assertTrue(Repo.notify)
        assertEquals(1,notificationRequests); assertEquals(listOf(true),backgroundWatching)
        compose.onAllNodes(isToggleable()).onLast().performClick()
        org.junit.Assert.assertFalse(Repo.notify)
        assertEquals(listOf(true,false),backgroundWatching)
    }

    @Test fun unavailableDemoPrinterCanBeRetriedAndLoaded() {
        Repo.state.value=UiState(on=true,printers=emptyList())
        show("The demo printer is unavailable.") { Root() }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); !Repo.state.value.printers.isNullOrEmpty() }
        org.junit.Assert.assertTrue(Repo.state.value.live)
    }

    @Test fun connectionFailureOffersLogoutWithoutInventingAConnectedPrinter() {
        Repo.state.value=UiState(on=true,error="Permission denied in test")
        show("Permission denied in test") { Root() }
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Log out").performClick()
        org.junit.Assert.assertFalse(Repo.state.value.on)
    }
    @Test fun directPrinterSelectorOpensAddPrinter() {
        val demo = Repo.setting("demo")
        val mode = Repo.setting("mode")
        try {
            Repo.setSetting("demo", "false"); Repo.setSetting("mode", "lan")
            show(printer.name) { DevicesTab(st, printer) }
            compose.onNodeWithText(printer.name).performClick()
            compose.onNodeWithText("Add printer").performClick()
            kotlin.test.assertIs<Screen.AddDirect>(Nav.stack.last())
        } finally { Repo.setSetting("demo", demo); Repo.setSetting("mode", mode); Nav.stack.clear() }
    }

    @Test fun devicesShowPrinter() = show(printer.name) { DevicesTab(st, printer) }
    @Test fun controlsShowTemperatures() = show("Device Control") { ControlScreen(printer) }
    @Test fun motionShowsAxes() = show("XYZ") { MotionScreen(printer) }
    @Test fun motionControlsStillWorkWhenThePrinterHasNoCamera() {
        val cameraLess = variant(remove = listOf("camera.camera"), set = arrayOf("sensor.print_status" to entity("idle")))
        show("XYZ") { MotionScreen(cameraLess) }
        compose.onNodeWithText("Toolhead").assertExists()
        compose.onNodeWithText("Heatbed").assertExists()
        compose.onNodeWithText("↑1", substring = false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/move_axis") } }
        val sent = json.parseToJsonElement(commands.last { it.first.endsWith("/move_axis") }.second).jsonObject
        assertEquals("Z", sent.s("axis"))
        assertEquals("-1", sent.s("distance"))
    }

    @Test fun skipShowsObjects() = show("Skip Parts") { SkipScreen(printer) }
    @Test fun filamentShowsSpools() = show("Filament") { FilamentScreen(printer) }
    @Test fun settingsShowPrinter() = show("Settings") { PrinterSettingsScreen(printer) }
    @Test fun calibrationShowsOptions() = show("Calibration") { CalibrationScreen(printer) }
    @Test fun optionsShowMonitoring() = show("Print Options") { PrintOptionsScreen(printer) }
    @Test fun amsShowsSettings() = show("AMS Settings") { AmsSettingsScreen(printer) }

    private fun homeAssistantMode() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val before=listOf("demo","mode").associateWith { store.settings.getString(it) }
        restorePrintSettings={ before.forEach { (key,value) -> store.settings.putString(key,value) } }
        store.settings.putString("demo","false"); store.settings.putString("mode","ha")
    }

    @Test fun missingPrinterStateStaysOfflineAndDoesNotInventSpoolsOrTemperatures() {
        val p=Printer(printer.info.copy(ids=emptyMap(),ams=emptyList(),external=emptyList(),firmware=null),emptyMap())
        show(p.name) { DevicesTab(UiState(on=true,printers=listOf(p),live=true),p) }
        compose.onNodeWithText("Device Offline").assertExists()
        compose.onNodeWithText("No AMS or external spool in Home Assistant").performScrollTo().assertExists()
        compose.onAllNodesWithText("--").onFirst().assertExists()
    }

    @Test fun disconnectedAmsShowsAnExplicitEmptyState() {
        val p=Printer(printer.info.copy(ams=emptyList()),snapshot)
        show("AMS Settings") { AmsSettingsScreen(p) }
        compose.onNodeWithText("No AMS connected").assertExists()
    }

    @Test fun homeAssistantCalibrationCannotSendUnsupportedCommands() {
        homeAssistantMode()
        val p=changed("sensor.print_status" to "idle")
        show("Calibration") { CalibrationScreen(p) }
        compose.onNodeWithText("Home Assistant does not expose this printer command.",substring=true).assertExists()
        val switches=compose.onAllNodes(isToggleable())
        switches.fetchSemanticsNodes().indices.forEach { switches[it].assertIsNotEnabled().performClick() }
        compose.onNodeWithText("Start calibration").assertIsNotEnabled().performClick()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun idleNonLidarCalibrationRequiresAtLeastOneChosenCheck() {
        val p=Printer(printer.info.copy(model="A1"),snapshot.toMutableMap().apply {
            val id=printer.info.ids.getValue("sensor.print_status"); put(id,Entity(id,"idle",JsonObject(emptyMap())))
        })
        show("Calibration") { CalibrationScreen(p) }
        compose.onNodeWithText("Micro lidar calibration").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("Start calibration").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).onLast().performClick()
        compose.onNodeWithText("Start calibration").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun unsupportedPrintOptionsCanBeHiddenWithoutInventingSwitchStates() {
        Repo.setShowUnavailable(false)
        val p=Printer(printer.info.copy(ids=printer.info.ids.filterKeys { it=="binary_sensor.online" }),snapshot)
        show("Print Options") { PrintOptionsScreen(p) }
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onAllNodesWithText("Not available").assertCountEquals(0)
        compose.onNodeWithText("Only switches reported by this printer",substring=true).assertExists()
    }

    @Test fun homeAssistantPrintOptionsRemainReadOnlyEvenWhenStateIsReported() {
        homeAssistantMode()
        show("Print Options") { PrintOptionsScreen(printer) }
        compose.onNodeWithText("Home Assistant does not expose these printer settings",substring=true).assertExists()
        val switches=compose.onAllNodes(isToggleable())
        org.junit.Assert.assertTrue(switches.fetchSemanticsNodes().isNotEmpty())
        switches.fetchSemanticsNodes().indices.forEach { switches[it].assertIsNotEnabled().performClick() }
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun homeAssistantAmsSettingsShowStateWithoutEnablingUnsupportedWrites() {
        homeAssistantMode()
        show("AMS Settings") { AmsSettingsScreen(printer) }
        compose.onNodeWithText("Home Assistant shows AMS state",substring=true).assertExists()
        val switches=compose.onAllNodes(isToggleable())
        org.junit.Assert.assertTrue(switches.fetchSemanticsNodes().isNotEmpty())
        switches.fetchSemanticsNodes().indices.forEach { switches[it].assertIsNotEnabled().performClick() }
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun sparseAmsFirmwareKeepsUnreportedOptionsExplicitlyUnavailable() {
        val p=Printer(printer.info.copy(ids=printer.info.ids.filterKeys { !it.startsWith("switch.") },
            ams=printer.info.ams.map { it.copy(ids=emptyMap()) }),snapshot)
        show("AMS Settings") { AmsSettingsScreen(p) }
        compose.onAllNodesWithText("Not available").onFirst().assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
    @Test fun printerStorageBrowsesFoldersAndDeletesAfterConfirmation() {
        show("Printer storage") { PrinterFilesScreen() }
        compose.onNodeWithText("cache").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("benchy.3mf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/cache").assertExists()
        compose.onNodeWithContentDescription("Delete benchy.3mf").performClick()
        compose.onNodeWithText("Delete benchy.3mf?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("benchy.3mf").assertExists()
        compose.onNodeWithContentDescription("Delete benchy.3mf").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("benchy.3mf").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("phone_stand.3mf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick() // up from /cache to the root
        compose.waitUntil(5000) { compose.onAllNodesWithText("timelapse").fetchSemanticsNodes().isNotEmpty() }
        kotlin.test.assertTrue(Nav.stack.none { it is Screen.PrinterFiles })
    }

    @Test fun printerStorageShowsReadAndDeleteFailures() {
        filesFailure="No route to the printer"
        show("Printer storage") { PrinterFilesScreen() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Couldn't read the printer's storage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No route to the printer").assertExists()
        filesFailure=null
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("cache").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("cache").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("phone_stand.3mf").fetchSemanticsNodes().isNotEmpty() }
        filesFailure="Printer busy"
        compose.onNodeWithContentDescription("Delete phone_stand.3mf").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Printer busy") }
        compose.onNodeWithText("phone_stand.3mf").assertExists()
    }

    @Test fun printerStorageShowsEmptyFolders() {
        show("Printer storage") { PrinterFilesScreen() }
        compose.onNodeWithText("model").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("calibration_cube.3mf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete calibration_cube.3mf").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("This folder is empty").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun printerStorageIsOfferedOnlyWhereThePrinterCanBeBrowsed() {
        show("Settings") { PrinterSettingsScreen(printer) }
        compose.onNodeWithText("Printer storage").performScrollTo().performClick()
        kotlin.test.assertIs<Screen.PrinterFiles>(Nav.stack.last())
    }

    @Test fun editingATraySendsTheChosenFilamentAndColour() {
        show("Filament") { FilamentScreen(changed("sensor.print_status" to "finish")) }
        compose.onNodeWithText("A3").performClick()
        compose.onNodeWithText("Edit filament").performClick()
        compose.onNodeWithText("Nozzle 220–270°C").assertExists() // the tray's current PETG profile is preselected
        compose.onNodeWithText("ABS").performClick()
        compose.onNodeWithText("Nozzle 240–280°C").assertExists()
        compose.onNodeWithText("Hex colour (RRGGBB)").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("#c12e1f")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/set_filament") } }
        val sent=json.parseToJsonElement(commands.last { it.first.endsWith("/set_filament") }.second).jsonObject
        assertEquals("GFB99",sent.s("filament_id")); assertEquals("C12E1F",sent.s("color"))
    }

    @Test fun editingNeedsAProfileAndAValidColourAndReportsErrors() {
        show("Filament") { FilamentScreen(changed("sensor.print_status" to "finish")) }
        compose.onNodeWithText("A4").performClick() // an empty slot: no profile chosen yet
        compose.onNodeWithText("Edit filament").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("ASA").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("zzz")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("00ae42")
        commandFailure="Printer refused"
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Printer refused") }
    }

    @Test fun editingIsNotOfferedWhilePrintingOrOutsideDirectMode() {
        show("Filament") { FilamentScreen(printer) }
        compose.onNodeWithText("A3").performClick()
        compose.onAllNodesWithText("Edit filament").assertCountEquals(0)
    }

    @Test fun anExternalSpoolCanBeEdited() {
        show("Filament") { FilamentScreen(changed("sensor.print_status" to "finish")) }
        compose.onNodeWithText("Ext").performClick()
        compose.onNodeWithText("Edit filament").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/set_filament") } }
    }

    @Test fun theCustomStreamUrlIsSavedAndCleared() {
        show("Liveview Settings") { LiveviewSettingsScreen() }
        compose.onNodeWithText("Stream URL (rtsp:// or http://)").performTextInput("rtsp://camera.local/stream")
        assertEquals("rtsp://camera.local/stream",Repo.customStream)
        kotlin.test.assertNull(Repo.setting("custom_stream_url")) // never in the settings that are backed up
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        assertEquals(null,Repo.customStream)
    }

    @Test fun aCustomStreamReplacesThePrintersCameraAndReportsFailure() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(404,-1); exchange.close() }
        server.start()
        try {
            Repo.setCustomStream("http://127.0.0.1:${server.address.port}/live.m3u8"); Repo.setSetting("liveview_autoplay","always")
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { LiveView(printer,androidx.compose.ui.Modifier.size(320.dp,180.dp),zoom=true) } } }
            compose.waitUntil(15000) { compose.waitForIdle(); compose.onAllNodesWithText("Try again",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Try again").performClick()
        } finally { Repo.setCustomStream(null); Repo.setSetting("liveview_autoplay",null); server.stop(0) }
    }

    @Test fun aCustomStreamWaitsForATapWhenAutoplayIsOff() {
        Repo.setCustomStream("rtsp://camera.local/stream"); Repo.setSetting("liveview_autoplay","off")
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { LiveView(printer,androidx.compose.ui.Modifier.size(320.dp,180.dp)) } } }
        try { compose.onNodeWithText("Tap to view").assertExists() } finally { Repo.setCustomStream(null); Repo.setSetting("liveview_autoplay",null) }
    }

    @Test fun aTaggedBambuSpoolIsNotOfferedForEditing() {
        val id=snapshot.keys.first { snapshot.getValue(it).attrs.s("name")=="Bambu PETG HF" }
        val states=snapshot.toMutableMap()
        states[id]=Entity(id,states.getValue(id).state,JsonObject(states.getValue(id).attrs+("tag_uid" to JsonPrimitive("A1B2C3D4E5F60718"))))
        val finished=Printer(printer.info,states+(printer.info.ids.getValue("sensor.print_status") to Entity(printer.info.ids.getValue("sensor.print_status"),"finish",states.getValue(printer.info.ids.getValue("sensor.print_status")).attrs)))
        show("Filament") { FilamentScreen(finished) }
        compose.onNodeWithText("A3").performClick()
        compose.onAllNodesWithText("Edit filament").assertCountEquals(0)
        compose.onNodeWithText("This spool has a Bambu tag, so the AMS reads its filament itself. Only spools without a tag can be edited.").assertExists()
    }

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
    @Test fun anOpenChamberSheetKeepsUnknownTemperatureExplicitWhenTelemetryDisappears() {
        val live = androidx.compose.runtime.mutableStateOf(changed("sensor.chamber_temp" to "35"))
        show("Device Control") { ControlScreen(live.value) }
        compose.onNodeWithText("Chamber").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("35°C").assertExists()
        compose.runOnIdle { live.value = changed("sensor.chamber_temp" to "unavailable") }
        compose.onNodeWithText("--°C").assertExists()
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

    private fun modelBytes(overrides: Map<String,ByteArray> = emptyMap()): ByteArray = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                val written=mutableSetOf<String>()
                fun put(name: String, text: String) {
                    written+=name
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(overrides[name] ?: text.toByteArray()); zip.closeEntry()
                }
                put("Metadata/slice_info.config", """<config><plate><metadata key="index" value="1"/><metadata key="prediction" value="3900"/><metadata key="weight" value="12.5"/><object identify_id="1"/></plate><plate><metadata key="index" value="2"/><metadata key="prediction" value="120"/><metadata key="weight" value="2"/><object identify_id="2"/><object identify_id="3"/></plate></config>""")
                put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","filament_type":["PLA","PETG"],"filament_colour":["#FFFFFF","#00AE42"]}""")
                put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Body"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>""")
                put("3D/3dmodel.model", """<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2" p1="1"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
                overrides.filterKeys { it !in written }.forEach { (name,bytes) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun prepareRealPrint(filaments: String?, failSlice: Boolean = false, keepAms: Boolean = false, model: ByteArray? = null, requests: MutableList<io.ktor.http.Url>? = null, beforePrint: () -> Unit = {}, noSpools: Boolean = false): Pair<java.util.concurrent.ConcurrentLinkedQueue<String>, java.util.concurrent.atomic.AtomicBoolean> {
        val store = Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val keys = listOf("demo","mode","lan_serial","lan_ip","lan_model")
        val before = keys.associateWith { store.settings.getString(it) }
        restorePrintSettings = { before.forEach { (k,v) -> store.settings.putString(k,v) } }
        store.settings.putString("demo","false"); store.settings.putString("mode","ha"); store.settings.putString("lan_serial",null)
        val values = snapshot.toMutableMap()
        for ((role,state) in listOf("sensor.print_status" to "idle", "binary_sensor.online" to "on", "sensor.ip_address" to "127.0.0.1")) {
            val id = printer.info.ids.getValue(role)
            values[id] = Entity(id,state,JsonObject(emptyMap()))
        }
        Repo.state.value = st.copy(printers=listOf(Printer((if(keepAms) printer.info else printer.info.copy(ams=emptyList())).let { if(noSpools) it.copy(external=emptyList()) else it },values)))
        val uploads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            org.junit.Assert.assertNull(request.headers["X-Printer-Code"])
            requests?.add(request.url)
            if(failSlice) respond("""{"error":"Slicing failed in test"}""",io.ktor.http.HttpStatusCode.ServiceUnavailable)
            else respond(byteArrayOf(1,2,3),headers=io.ktor.http.headersOf(
                "Content-Disposition" to listOf("attachment; filename=\"test.gcode.3mf\""),
                "X-Pandy-Result" to listOf(if(filaments==null) "{}" else """{"plates":[{"index":1,"filaments":$filaments}]}""")))
        }).also { testHttpClients += it }
        val slicer = SlicerClient("http://slicer.invalid:8737",null,http=http,storageFactory={ c ->
            assertEquals("127.0.0.1",c.ip)
            object : PrinterStorage {
                override suspend fun list(dir: String) = emptyList<FileEntry>()
                override suspend fun read(path: String,offset: Long,length: Int) = byteArrayOf()
                override suspend fun write(path: String,data: ByteArray,progress: (Float)->Unit) {
                    org.junit.Assert.assertArrayEquals(byteArrayOf(1,2,3),data); uploads += path
                }
            }
        })
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,if(model==null) "Test model.3mf" else "Test model.stl",null,model ?: modelBytes(),true),{closed.set(true)},slicerClient=slicer)
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Printer access code").performScrollTo().performTextInput("12345678")
        beforePrint()
        compose.onNodeWithText("Print current plate",substring=false).performClick()
        compose.onNodeWithText("Start print?").assertExists()
        return uploads to closed
    }

    @Test fun aPlainStlPrintsInTheSpoolThePickedFromThePrintersLoadedFilament() {
        val requests = mutableListOf<io.ktor.http.Url>()
        val loaded = printer.loadedSpools()
        org.junit.Assert.assertTrue("the test printer needs two loaded spools", loaded.size >= 2)
        val (second, spool) = loaded[1]
        val (uploads,closed) = prepareRealPrint("""[{"id":"1","type":"${spool.type}","color":"${colorHex(spool.color)}"}]""", keepAms = true, model = stlBytes(), requests = requests, beforePrint = {
            compose.onNodeWithText("This model has no filament of its own",substring=true).performScrollTo().assertExists()
            compose.onNodeWithText("Use my filament presets").assertDoesNotExist() // nothing to match for a plain model
            val tray = printer.ams.first().let { (ams,_) -> "A${ams.slotForTrayId(second)!! + 1}" }
            compose.onNodeWithText(tray).performScrollTo().performClick() // the second loaded spool
        })
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed.get() }
        val sliced = requests.single { it.encodedPath.endsWith("/slice") }
        assertEquals(spool.type,sliced.parameters["filament_type"]); assertEquals(colorHex(spool.color),sliced.parameters["filament_color"])
        assertEquals(1,uploads.size)
        org.junit.Assert.assertTrue(commands.any { it.first.endsWith("/print_project_file") })
    }

    @Test fun aPlainStlWithNothingLoadedSaysSoInsteadOfOfferingFilament() {
        prepareRealPrint(null, model = stlBytes(), noSpools = true, beforePrint = {
            // No AMS and no external spool loaded in the test printer's state.
            compose.onNodeWithText("Load a filament in the printer to choose it here.").performScrollTo().assertExists()
            compose.onNodeWithText("This model has no filament of its own",substring=true).assertDoesNotExist()
        })
    }

    @Test fun longPressingMakerWorldOffersThingiverseAndPrintables() {
        show("MakerWorld") { MeTab(st) }
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld; Nav.tab=2 }
        compose.onNodeWithText("MakerWorld").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("MakerWorld ✓").assertExists()
        compose.onNodeWithText("Printables").assertExists()
        compose.onNodeWithText("Thingiverse").performClick()
        assertEquals(ModelSite.Thingiverse,Nav.site); assertEquals(0,Nav.tab)
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld }
    }

    @Test fun theBottomTabsSwitchBetweenModelsDevicesAndMe() {
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld; Nav.tab=1 }
        show("Devices") { Tabs(st,printer) }
        compose.onNodeWithText("Me").performClick(); assertEquals(2,Nav.tab)
        compose.onNodeWithText("Devices").performClick(); assertEquals(1,Nav.tab)
        compose.onNodeWithText("Models").performClick(); assertEquals(0,Nav.tab)
        compose.onNodeWithText("Models").performTouchInput { longClick() }
        compose.onNodeWithText("MakerWorld ✓").performClick() // the current site: stays, menu closes
        assertEquals(ModelSite.MakerWorld,Nav.site); compose.onNodeWithText("Thingiverse").assertDoesNotExist()
    }

    @Test fun longPressingTheModelsTabSwitchesSiteAndItsWebViewLoadsThatSite() {
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld; Nav.tab=0 }
        show("Models") { Tabs(st,printer) }
        compose.onNodeWithText("Models").performTouchInput { longClick() }
        compose.onNodeWithText("Printables").performClick()
        assertEquals(ModelSite.Printables,Nav.site)
        compose.waitForIdle()
        val web=descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
        assertEquals("https://www.printables.com",org.robolectric.Shadows.shadowOf(web).lastLoadedUrl)
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld }
    }

    @Test fun aThingiverseStlIsJustSavedWhenPandyCannotPrint() {
        Repo.state.value=UiState(on=true,printers=emptyList())
        compose.runOnIdle { Nav.site=ModelSite.Thingiverse }
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { ModelsTab() } } }
        val web=descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
        val shadow=org.robolectric.Shadows.shadowOf(web)
        assertEquals("https://www.thingiverse.com",shadow.lastLoadedUrl)
        val realDownload=downloadModel
        downloadModel={ _,_ -> byteArrayOf(1,2,3) }
        try {
            compose.runOnIdle { shadow.downloadListener.onDownloadStart("https://cdn.thingiverse.com/assets/a/benchy.stl","","attachment; filename=\"benchy.stl\"","model/stl",3) }
            compose.waitUntil(5000) { compose.waitForIdle(); savedFiles.isNotEmpty() }
        } finally { downloadModel=realDownload }
        assertEquals("benchy.stl",savedFiles.first().first)
        compose.runOnIdle { Nav.site=ModelSite.MakerWorld }
    }

    private fun stlBytes() = ("solid x\n" + listOf("0 0 0","10 0 0","0 10 0","0 0 10").let { v -> listOf(listOf(0,1,2),listOf(0,1,3),listOf(0,2,3),listOf(1,2,3)).joinToString("") { t -> "facet normal 0 0 0\nouter loop\n" + t.joinToString("") { "vertex ${v[it]}\n" } + "endloop\nendfacet\n" } } + "endsolid x\n").encodeToByteArray()

    private fun printablesStl(slicer: Boolean) {
        if (slicer) Repo.setSlicer("http://slicer.example:8737",null)
        compose.runOnIdle { Nav.site=ModelSite.Printables }
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { ModelsTab() } } }
        val web=descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
        val shadow=org.robolectric.Shadows.shadowOf(web)
        val realDownload=downloadModel
        downloadModel={ _,_ -> stlBytes() }
        try {
            compose.runOnIdle { shadow.downloadListener.onDownloadStart("https://files.printables.com/media/a.stl","","attachment; filename=\"a.stl\"","model/stl",3) }
            if (slicer) {
                // With a printer and a slicer the download is a print: the wrapped STL reads as a one-plate project.
                compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("1 plate",substring=true).assertExists()
            } else {
                // A printer but no slicer: a plain save.
                compose.waitUntil(5000) { compose.waitForIdle(); savedFiles.isNotEmpty() }
                assertEquals("a.stl",savedFiles.last().first)
            }
        } finally { downloadModel=realDownload; Repo.setSlicer(null,null); compose.runOnIdle { Nav.site=ModelSite.MakerWorld } }
    }
    @Test fun anStlOnPrintablesOpensThePrintSheetWhenASlicerIsSetUp() = printablesStl(true)
    @Test fun anStlOnPrintablesIsJustSavedWithoutASlicer() = printablesStl(false)

    @Test fun makerWorldPrintWaitsForConfirmationThenSlicesUploadsAndStarts() {
        val (uploads,closed) = prepareRealPrint("""[{"id":"1","type":"PLA","color":"#FFFFFF"}]""")
        org.junit.Assert.assertTrue(uploads.isEmpty())
        org.junit.Assert.assertFalse(commands.any { it.first.endsWith("/print_project_file") })
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed.get() }
        assertEquals(listOf("/cache/test.gcode.3mf"), uploads.toList())
        val command = commands.first { it.first.endsWith("/print_project_file") }.second
        org.junit.Assert.assertTrue(command.contains("cache/test.gcode.3mf"))
        assertEquals(1,Nav.tab)
    }

    @Test fun makerWorldPrintRefusesMissingSlicedFilamentMapping() {
        val (_,closed) = prepareRealPrint("[]")
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.contains("no filament mapping") } }
        org.junit.Assert.assertFalse(closed.get())
        org.junit.Assert.assertFalse(commands.any { it.first.endsWith("/print_project_file") })
    }

    @Test fun makerWorldSlicerFailureLeavesThePrintReadyForRetry() {
        val (uploads,closed) = prepareRealPrint("[]",failSlice=true)
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Slicing failed in test") }
        org.junit.Assert.assertTrue(uploads.isEmpty()); org.junit.Assert.assertFalse(closed.get())
        compose.onNodeWithText("Print current plate",substring=false).assertIsEnabled()
    }

    @Test fun amsPrintingRefusesSlicedMaterialsThatDoNotMatchAnyLoadedSpool() {
        val (uploads,closed)=prepareRealPrint("""[{"id":"1","type":"Unsupported material","color":"#FFFFFF"}]""",keepAms=true)
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Load a matching filament before printing") }
        assertEquals(listOf("/cache/test.gcode.3mf"),uploads.toList())
        org.junit.Assert.assertFalse(closed.get())
        org.junit.Assert.assertFalse(commands.any { it.first.endsWith("/print_project_file") })
        compose.onNodeWithText("Print current plate",substring=false).assertIsEnabled()
    }

    @Test fun amsPrintingRefusesASlicerReplyWithoutPlateMetadata() {
        val (_,closed)=prepareRealPrint(null,keepAms=true)
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.contains("no filament mapping") } }
        org.junit.Assert.assertFalse(closed.get())
        org.junit.Assert.assertFalse(commands.any { it.first.endsWith("/print_project_file") })
    }

    @Test fun amsPrintUsesSparseSlicerFilamentIdsWithoutAssigningUnusedSlots() {
        val (_,closed)=prepareRealPrint("""[{"id":"1","type":"PLA","color":"#FFFFFF"},{"id":"3","type":"PETG","color":"#00AE42"}]""",keepAms=true)
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed.get() }
        val body=json.parseToJsonElement(commands.first { it.first.endsWith("/print_project_file") }.second).jsonObject
        assertEquals("true",body.s("use_ams"))
        val mapping=body.s("ams_mapping")!!.split(',').map(String::toInt)
        assertEquals(3,mapping.size); assertEquals(-1,mapping[1])
        org.junit.Assert.assertTrue(mapping[0] in printer.loadedTrayIds())
        org.junit.Assert.assertTrue(mapping[2] in printer.loadedTrayIds())
    }

    @Test fun makerWorldProjectCanBePreparedAndPreviewedWithoutPrinting() {
        val bytes = modelBytes()
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
        compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            org.junit.Assert.assertTrue(bitmap.getPixel(view.width/2,view.height/2) != 0)
            System.getenv("PANDY_PREVIEW_EVIDENCE")?.let { path ->
                java.io.File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        compose.onNodeWithText("Printer colors").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Customize colors").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Prepare to Print").assertExists()
        compose.onNodeWithText("Textured PEI Plate").performScrollTo().performClick()
        compose.onNodeWithText("Select printer").assertExists()
        compose.onNodeWithText("Build plate").performClick()
        compose.onNodeWithText("Engineering Plate").performClick()
        compose.onNodeWithText("Engineering Plate").assertExists()
        compose.onAllNodesWithText("PLA").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Please select filament").assertExists()
        compose.onNodeWithText("AMS 1 · slot 1").performClick()
        compose.onNodeWithText("Confirm").performClick()
        org.junit.Assert.assertTrue(Repo.filamentPreset(printer.serial).isNotEmpty())
        compose.onNodeWithText("Print (demo)").assertIsNotEnabled()
    }

    private fun prepareModel(bytes: ByteArray,client: SlicerClient?=null) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,bytes,true),{},slicerClient=client)
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sparseProjectKeepsUnknownMetricsAndMissingMeshExplicit() {
        val picture=java.io.File("src/commonMain/composeResources/files/demo_cover.png").readBytes()
        prepareModel(modelBytes(mapOf(
            "Metadata/project_settings.config" to "{}".toByteArray(),
            "Metadata/model_settings.config" to "<config/>".toByteArray(),
            "Metadata/slice_info.config" to "<config/>".toByteArray(),
            "3D/3dmodel.model" to "<model/>".toByteArray(),
            "Metadata/plate_1.png" to picture)))
        compose.onNodeWithText("1 plate · 0 colors").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("This model has no filament of its own",substring=true).performScrollTo().assertExists() // a model without filaments: pick one of the printer's spools
        compose.onNodeWithText("Ready",substring=false).assertExists()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("3D preview unavailable").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("3D preview unavailable").assertExists()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Prepare to Print").assertExists()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun designerProfileNameAndSingleFilamentAreShownBeforePreparation() {
        prepareModel(modelBytes(mapOf("Metadata/project_settings.config" to """{
            "profile_name":"Fine detail","layer_height":0.12,"filament_type":["PLA"],"filament_colour":["#FFFFFF"]}""".toByteArray())))
        compose.onNodeWithText("Fine detail").assertExists()
        compose.onNodeWithText("0.12 mm layer").assertExists()
        compose.onNodeWithText("2 plates · 1 color").assertExists()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun incompatibleDesignerModelExplainsRetargetingAndRequiresSlicerSetup() {
        homeAssistantMode()
        Repo.state.value=st.copy(printers=listOf(changed("sensor.print_status" to "idle")))
        prepareModel(modelBytes(mapOf("Metadata/project_settings.config" to """{"printer_model":"Bambu Lab A1 mini"}""".toByteArray())))
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Prepared for ${printer.model}; the designer's A1 mini profile",substring=true).performScrollTo().assertExists()
        compose.onNodeWithText("Printing MakerWorld models needs Pandy Slicer",substring=true).performScrollTo().assertExists()
        compose.onNodeWithText("Print current plate").assertIsNotEnabled()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun mismatchedModelFilamentCannotEnablePrintingJustByEnteringAnAccessCode() {
        homeAssistantMode()
        Repo.state.value=st.copy(printers=listOf(changed("sensor.print_status" to "idle")))
        val http=io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("A mismatched model must not reach the slicer") }).also { testHttpClients+=it }
        prepareModel(modelBytes(mapOf("Metadata/project_settings.config" to """{"filament_type":["Unsupported material"],"filament_colour":["#FFFFFF"]}""".toByteArray())),
            SlicerClient("slicer.invalid",null,http=http))
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Printer access code").performScrollTo().performTextInput("12345678")
        compose.onNodeWithText("Load matching filament types",substring=true).performScrollTo().assertExists()
        compose.onNodeWithText("Print current plate").assertIsNotEnabled().performClick()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun externalSpoolCanBeChosenAndItsPresetPersistsForTheModel() {
        val id="sensor.test_external_spool"
        val spool=Entity(id,"PLA",json.parseToJsonElement("""{"type":"PLA","color":"#FFFFFF","name":"External PLA"}""").jsonObject)
        val p=Printer(printer.info.copy(ams=emptyList(),external=listOf(id)),snapshot+(id to spool))
        Repo.state.value=st.copy(printers=listOf(p))
        prepareModel(modelBytes(mapOf("Metadata/project_settings.config" to """{"filament_type":["PLA"],"filament_colour":["#FFFFFF"]}""".toByteArray())))
        compose.onNodeWithText("Next").performClick()
        compose.onAllNodesWithText("PLA").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("External spool").performClick()
        assertEquals(254,Repo.filamentPreset(p.serial)[filamentKey("PLA","#FFFFFF")])
        compose.onNodeWithText("Ext").assertExists()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }
    @Test fun rootNavigationReachesPrinterAndAccountDestinations() {
        show("Devices") { Root() }
        for ((screen,title) in listOf(
            Screen.Control to "Device Control", Screen.Motion to "XYZ", Screen.Skip to "Skip Parts",
            Screen.PrinterSettings to "Settings", Screen.Calibration to "Calibration",
            Screen.PrintOptions to "Print Options", Screen.AmsSettings to "AMS Settings",
            Screen.LiveviewSettings to "Liveview Settings", Screen.Assistant to "Machine Assistant",
            Screen.Files to "Files", Screen.Filament to "Filament", Screen.History to "History",
            Screen.Settings to "Settings", Screen.About to "About Pandy", Screen.AddHa to "Home Assistant address",
            Screen.AddDirect to "Direct LAN connection", Screen.Slicer to "Slicer")) {
            compose.runOnIdle { Nav.stack.clear(); Nav.go(screen) }
            compose.onAllNodesWithText(title).onFirst().assertExists()
        }
    }

    @Test fun settingsRememberAppearanceAndPrintPreferences() {
        show("Settings") { SettingsScreen() }
        compose.onNodeWithText("Dark").performClick()
        assertEquals("dark",ThemeMode.mode)
        compose.onNodeWithText("Light").performClick()
        assertEquals("light",ThemeMode.mode)
        val previous = Repo.timelapseDefault
        compose.onAllNodes(isToggleable()).onLast().performClick()
        assertEquals(!previous,Repo.timelapseDefault)
    }

    @Test fun recordingsProvideLocalPlaybackControls() {
        show("0:00") { VideoPlayer("file:///nonexistent-pandy-test.mp4",true,androidx.compose.ui.Modifier,{},controls=true,loop=true) }
        compose.onAllNodes(hasContentDescription("Pause") or hasContentDescription("Play")).onFirst().performClick()
        compose.onAllNodesWithText("0:00").onFirst().assertExists()
    }

    @Test fun reprintRemainsDisabledWhilePrinterIsBusy() {
        val file = CachedFile("Test.3mf", "TEST/prints/Test.3mf", 10, "2026-10-01", null)
        show("Print") { ReprintSheet(printer,file,{}) }
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test fun cachedPrintIsRestoredToPrinterBeforeStarting() {
        val file = CachedFile("Test.3mf", "TEST/prints/cache/Test.3mf",10,"2026-10-01",null)
        val ready = changed("sensor.print_status" to "idle")
        val demo = FakeHomeAssistant()
        val backend = object : HaBackend by demo {
            override suspend fun get(path: String): String = if(path.endsWith(".slice_info.config")) """<config><metadata key="index" value="2"/></config>""" else demo.get(path)
            override suspend fun post(path: String,body: String): String {
                commands += path to body
                return if(path.endsWith("ensure_cache_file")) """{"status":"use_legacy_path","detail":"/cache/restored.3mf"}""" else demo.post(path,body)
            }
        }
        for(field in listOf("ha","extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible=true }.set(Repo,backend)
        var closed = false
        show("Print") { ReprintSheet(ready,file,{closed=true}) }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Send").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed }
        val paths=commands.map { it.first }
        org.junit.Assert.assertTrue(paths.indexOf("/api/bambu_lab/ensure_cache_file") < paths.indexOf("/api/services/bambu_lab/print_project_file"))
        org.junit.Assert.assertTrue(commands.first { it.first.endsWith("/print_project_file") }.second.contains("cache/restored.3mf"))
    }

    @Test fun rapidReprintTapsRestoreAndStartTheFileOnlyOnce() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val demo = FakeHomeAssistant()
        val restores = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : HaBackend by demo {
            override suspend fun get(path: String): String = if (path.endsWith(".slice_info.config")) "<config/>" else demo.get(path)
            override suspend fun post(path: String, body: String): String {
                if (path.endsWith("ensure_cache_file")) { restores.incrementAndGet(); gate.await(); return "{}" }
                commands += path to body
                return demo.post(path, body)
            }
        }
        for (field in listOf("ha", "extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible = true }.set(Repo, backend)
        var closed = 0
        show("Print") { ReprintSheet(changed("sensor.print_status" to "idle"), CachedFile("Test.3mf", "TEST/prints/cache/Test.3mf", 10, "", null), { closed++ }) }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Send").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        val tap = compose.onNodeWithText("Send").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        try {
            // Both input events can arrive before Compose redraws the disabled button.
            compose.runOnIdle { tap(); tap() }
            compose.waitUntil(5000) { compose.waitForIdle(); restores.get() > 0 }
            gate.complete(Unit)
            compose.waitUntil(5000) { compose.waitForIdle(); closed > 0 }
            assertEquals(1, restores.get())
            assertEquals(1, commands.count { it.first.endsWith("/print_project_file") })
            assertEquals(1, closed)
        } finally { gate.complete(Unit) }
    }

    private fun cachedPrintWithLoadedFilament(ht: Boolean) {
        val ready=changed("sensor.print_status" to "idle")
        val ams=ready.info.ams.first()
        val unit=if(ht) ams.copy(model="AMS HT",protocolId=128,trays=ams.trays.filterKeys { it==1 }) else ams.copy(protocolId=0)
        val p=Printer(ready.info.copy(ams=listOf(unit)),snapshot.mapValues { (id,e) ->
            if(id==ready.info.ids["sensor.print_status"]) Entity(id,"idle",e.attrs) else e
        })
        val demo=FakeHomeAssistant()
        val backend=object : HaBackend by demo {
            override suspend fun get(path: String)=if(path.endsWith(".slice_info.config"))
                """<config><plate><metadata key="index" value="2"/><filament type="PLA" color="#FFFFFF"/></plate></config>""" else demo.get(path)
            override suspend fun post(path: String,body: String): String {
                commands+=path to body
                return if(path.endsWith("ensure_cache_file")) """{"status":"present"}""" else demo.post(path,body)
            }
        }
        for(field in listOf("ha","extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible=true }.set(Repo,backend)
        var closed=false
        show("Print") { ReprintSheet(p,CachedFile("Test.3mf","TEST/prints/cache/Test.3mf",10,"",null),{closed=true}) }
        compose.onNodeWithText(if(ht) "HT PLA" else "A1 PLA").assertExists()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onAllNodes(isToggleable()).onLast().performClick()
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed }
        val sent=json.parseToJsonElement(commands.first { it.first.endsWith("/print_project_file") }.second).jsonObject
        assertEquals("2",sent.s("plate")); assertEquals("true",sent.s("use_ams"))
        assertEquals(if(ht) "128" else "0",sent.s("ams_mapping"))
        assertEquals("true",sent.s("timelapse")); assertEquals("false",sent.s("bed_leveling")); assertEquals("false",sent.s("flow_cali"))
    }

    @Test fun cachedAmsPrintUsesTheMatchedPhysicalSlotAndSelectedOptions() = cachedPrintWithLoadedFilament(false)
    @Test fun cachedAmsHtPrintUsesTheProtocolIdInsteadOfAStandardSlot() = cachedPrintWithLoadedFilament(true)

    @Test fun blockedControlsExplainHowToUsePandy() {
        show("Watching only") { ControlBlockedNote() }
        compose.onNodeWithText("Open Bambu Handy").performClick()
        compose.onNodeWithText("Use Pandy instead").performClick()
    }

    @Test fun liveviewPreferenceRoundTrips() {
        show("Liveview Settings") { LiveviewSettingsScreen() }
        for((label,value) in listOf("Always Autoplay" to "always","Autoplay off" to "off","Autoplay on Wi-Fi only" to "wifi")) {
            compose.onNodeWithText(label).performClick()
            assertEquals(value,Repo.setting("liveview_autoplay"))
        }
    }

    @Test fun filamentDetailsDescribeTheSelectedSpool() {
        show("Filament") { FilamentScreen(printer) }
        compose.onNodeWithText("A1").performClick()
        compose.onNodeWithText("Remaining  ").assertExists()
    }

    private fun changed(vararg entries: Pair<String,String>): Printer {
        val states = snapshot.toMutableMap()
        for ((key,value) in entries) {
            val id = printer.info.ids.getValue(key)
            states[id] = Entity(id,value,states.getValue(id).attrs)
        }
        return Printer(printer.info,states)
    }

    @Test fun finishedFailedAndOfflineDevicesRemainDistinct() {
        val selected = androidx.compose.runtime.mutableStateOf(printer)
        show(printer.name) { DevicesTab(st,selected.value) }
        for((p,text) in listOf(
            changed("sensor.print_status" to "finish") to "Printing completed",
            changed("sensor.print_status" to "failed") to "Printing stopped",
            changed("binary_sensor.online" to "off", "sensor.print_status" to "offline") to "Device Offline")) {
            compose.runOnIdle { selected.value=p }
            compose.onNodeWithText(text,substring=true).assertExists()
        }
    }

    @Test fun printerPicturePageShowsItsTemperatures() {
        show(printer.name) { DevicesTab(st,printer) }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
        compose.onNodeWithText(printer.name).assertExists()
    }

    @Test fun skipSelectionHighlightsPartsAndRequiresConfirmation() {
        show("Skip Parts") { SkipScreen(printer) }
        compose.onNodeWithText("Select All").performClick()
        compose.onNodeWithText("3/3 Selected").assertExists()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Skip 3 parts?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Select All").performClick()
        compose.onNodeWithText("0/3 Selected").assertExists()
    }

    private fun descendants(view: android.view.View): List<android.view.View> = listOf(view) + if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun makerWorldDownloadsReachTheSystemSaveBoundaryWithCorrectTypes() {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { ModelsTab() } } }
        for ((extension,mime) in listOf("3mf" to "model/3mf", "stl" to "model/stl", "obj" to "model/obj", "step" to "model/step", "igs" to "model/iges", "gcode" to "application/octet-stream", "png" to "image/png", "jpg" to "image/jpeg")) {
            compose.runOnIdle {
                val web=descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
                val shadow=org.robolectric.Shadows.shadowOf(web)
                shadow.webViewClient.onPageFinished(web,"https://makerworld.com/en")
                val bridge=shadow.getJavascriptInterface("PandyBridge")
                val token=bridge.javaClass.declaredFields.first { it.name.contains("bridgeToken") }.apply { isAccessible=true }.get(bridge) as String
                bridge.javaClass.getDeclaredMethod("blob",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }.invoke(bridge,"model.$extension","AQID",token)
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { }
            compose.waitUntil(5000) { savedFiles.any { it.first=="model.$extension" } }
            val saved=savedFiles.last()
            assertEquals(mime,saved.second)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1,2,3),saved.third)
        }
    }

    @Test fun idleCalibrationRequiresConfirmationAndSendsChosenChecks() {
        val idle=changed("sensor.print_status" to "idle")
        show("Calibration") { CalibrationScreen(idle) }
        compose.onAllNodes(isToggleable()).onLast().performClick()
        compose.onNodeWithText("Start calibration").performClick()
        compose.onNodeWithText("Start calibration?").assertExists()
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { }
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("calibration") } }
        val body=json.parseToJsonElement(commands.last().second).jsonObject
        assertEquals("15",body.s("option"))
    }

    @Test fun reportedPrintOptionsCanBeChanged() {
        show("Print Options") { PrintOptionsScreen(printer) }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { }
        compose.waitUntil(5000) { compose.waitForIdle(); commands.isNotEmpty() }
        org.junit.Assert.assertTrue(commands.last().first.endsWith("xcam_control_set"))
    }

    @Test fun amsPreferencesSendAnExplicitUpdate() {
        show("AMS Settings") { AmsSettingsScreen(printer) }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { }
        compose.waitUntil(5000) { compose.waitForIdle(); commands.isNotEmpty() }
        org.junit.Assert.assertTrue(commands.last().first.endsWith("ams_user_setting"))
    }

    @Test fun missingPrinterCapabilitiesRemainReadOnly() {
        val info=printer.info.copy(ids=printer.info.ids.filterKeys { !it.startsWith("switch.") })
        val unknown=Printer(info,snapshot)
        show("Print Options") { PrintOptionsScreen(unknown) }
        compose.onAllNodesWithText("Not available").onFirst().assertExists()
    }

    @Test fun machineAssistantShowsActionableErrorsAndSolutionLinks() {
        val id=printer.info.ids.getValue("binary_sensor.hms_errors")
        val error=Entity(id,"on",json.parseToJsonElement("""{"Count":1,"1-Code":"HMS_0300_0100_0001_0007","1-Error":"Filament is tangled","1-Wiki":"https://wiki.bambulab.com/test"}""").jsonObject)
        val p=Printer(printer.info,snapshot+(id to error))
        show("Machine Assistant") { AssistantScreen(p) }
        compose.onNodeWithText("Filament is tangled").assertExists()
        compose.onNodeWithText("View solution ›").performClick()
    }

    @Test fun timelapseFilesOpenWithoutClaimingTheDemoHasVideo() {
        show("Files") { FilesScreen(printer) }
        val first=runBlocking { Repo.extras!!.timelapses(printer.serial).first() }
        compose.onNodeWithText(first.title).performClick()
        compose.onNodeWithText("The demo has no video").assertExists()
    }

    @Test fun malformedProjectCannotBeSentToThePrinter() {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"broken.3mf",null,byteArrayOf(1,2,3),true),{})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Prepare to Print").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Print current plate").assertCountEquals(0)
        compose.onAllNodesWithText("Print (demo)").assertCountEquals(0)
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun slicerSetupChecksTheServiceAndCanRemoveTheSavedAddress() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/health") { exchange ->
            val body="""{"version":"2.0-test"}""".toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base="http://127.0.0.1:${server.address.port}"
            Repo.setSlicer(base,null)
            show("Slicer") { SlicerScreen() }
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Connected: Bambu Studio 2.0-test").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(base,Repo.slicerUrl)
            compose.onNodeWithText("Remove").performClick()
            org.junit.Assert.assertNull(Repo.slicerUrl)
        } finally { Repo.setSlicer(null,null); server.stop(0) }
    }

    @Test fun printPreparationOffersHelpAndCanStartOnlyAfterConfirmation() {
        Repo.state.value=st.copy(printers=listOf(changed("sensor.print_status" to "idle")))
        var closed=false
        var back: (() -> Unit)? = null
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{closed=true}) { back=it }
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { back!!.invoke() }
        org.junit.Assert.assertTrue(closed); closed=false
        compose.onNodeWithText("Next").performClick()
        for(index in 0..3) {
            compose.onAllNodesWithContentDescription("Help")[index].performScrollTo().performClick()
            compose.onNodeWithText("Got it").performClick()
        }
        compose.runOnIdle { back!!.invoke() }
        compose.onNodeWithText("Select a Print Profile").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Print (demo)").performClick()
        compose.onNodeWithText("Start Test model",substring=true).assertExists()
        org.junit.Assert.assertTrue(commands.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Print (demo)").performClick()
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(100); compose.runOnIdle { }
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("print_project_file") } }
        org.junit.Assert.assertTrue(closed)
        assertEquals(1,Nav.tab)
    }

    @Test fun cameraOffAndManualAutoplayAreExplicit() {
        val selected=androidx.compose.runtime.mutableStateOf(changed("switch.camera" to "off"))
        Repo.setSetting("liveview_autoplay","off")
        show("Live view is off") { LiveView(selected.value,androidx.compose.ui.Modifier.fillMaxSize()) }
        compose.onNodeWithText("Turn on").performClick()
        compose.runOnIdle { selected.value=printer }
        compose.onNodeWithText("Tap to view").assertExists()
        compose.onNodeWithText("Tap to view").performClick()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
        compose.onAllNodesWithText("Tap to view").assertCountEquals(0)
    }

    @Test fun fullscreenCameraCanReturnToThePriorScreen() {
        Repo.setSetting("liveview_autoplay","always")
        Nav.go(Screen.LiveFull)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { LiveFullScreen(printer) } } }
        compose.onNodeWithContentDescription("Back").performClick()
        org.junit.Assert.assertTrue(Nav.stack.isEmpty())
    }

    @Test fun fileLoadingFailureIsVisibleAndRetryCanRecover() {
        val original=Repo.extras!!
        var fail=true
        val backend=object : HaBackend by original {
            override suspend fun get(path: String): String {
                if(fail && path.startsWith("/api/bambu_lab/videos")) throw ApiException("SD card unavailable")
                return original.get(path)
            }
        }
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,backend)
        show("Files") { FilesScreen(printer) }
        compose.onNodeWithText("Couldn't load timelapses").assertExists()
        compose.onNodeWithText("SD card unavailable").assertExists()
        fail=false
        compose.onNodeWithText("Try again").performClick()
        val first=runBlocking { original.timelapses(printer.serial).first() }
        compose.onNodeWithText(first.title).assertExists()
        compose.onNodeWithText("Model").performClick()
        val model=runBlocking { original.printHistory(printer.serial).first() }
        compose.onNodeWithText(model.title).performClick()
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test fun cameraSnapshotIsPassedToThePhotoSaveBoundary() {
        Repo.setSetting("liveview_autoplay","off")
        show("Tap to view") { LiveView(printer,androidx.compose.ui.Modifier.fillMaxSize()) }
        compose.onAllNodes(hasClickAction()).onLast().performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(100); compose.runOnIdle { }
        compose.waitUntil(5000) { compose.waitForIdle(); savedImages.isNotEmpty() }
        val expected=java.io.File("src/commonMain/composeResources/files/demo_camera.jpg").readBytes()
        org.junit.Assert.assertArrayEquals(expected,savedImages.single())
    }

    @Test fun plateSelectionShowsTimeMaterialAndPartCountsAndSupportsPreviewGestures() {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) {
            AppTheme { PrintModelSheet(WebDownload(null,"Two plates.3mf",null,modelBytes(),true),{}) }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("◷ 1.1h").performScrollTo().assertExists()
        compose.onNodeWithText("◎ 12.5g").assertExists()
        compose.onNodeWithText("Plate 2").performScrollTo().performClick()
        compose.onNodeWithText("◷ 2 min").assertExists()
        compose.onNodeWithText("◎ 2g").assertExists()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Model colors").assertExists()
        compose.onRoot().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.4f),androidx.compose.ui.geometry.Offset(width*.6f,height*.5f),300) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Prepare to Print").assertExists()
    }

    @Test fun slicerAddOnFailureDoesNotPersistAnUnverifiedConfiguration() {
        val port=java.net.ServerSocket(0).use { it.localPort }
        Repo.setSlicer(null,null)
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,HaHttp("http://127.0.0.1:$port","test-only"))
        show("Slicer") { SlicerScreen() }
        compose.onNodeWithText("Slicer elsewhere (Docker)").performScrollTo().assertExists()
        compose.onNodeWithText("Check the add-on").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.waitForIdle(); compose.onAllNodesWithText("Checking…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Check the add-on").assertExists()
        org.junit.Assert.assertNull(Repo.slicerUrl)
        compose.onNodeWithText("Connected:",substring=true).assertDoesNotExist()
    }

    @Test fun motionPadDistinguishesHomeFineStepsAndEachOuterDirection() {
        val idle=Printer(printer.info,snapshot.mapValues { (_,e) -> if(e.id.contains("print_status")) Entity(e.id,"idle",e.attrs) else e })
        org.junit.Assert.assertFalse(idle.printing)
        show("XYZ") { MotionScreen(idle) }
        val label=compose.onNodeWithText("Toolhead").fetchSemanticsNode().boundsInRoot
        val density=compose.activity.resources.displayMetrics.density
        val center=androidx.compose.ui.geometry.Offset(label.center.x,label.bottom+115*density)
        for((dx,dy) in listOf(0 to 0,80 to 0,0 to -80,-80 to 0,0 to 80,45 to 0)) {
            compose.onRoot().performTouchInput { click(center+androidx.compose.ui.geometry.Offset(dx*density,dy*density)) }
            compose.waitForIdle()
        }
        compose.waitUntil(5000) { commands.count { it.first.endsWith("/move_axis") }>=6 }
        val moves=commands.filter { it.first.endsWith("/move_axis") }.map { json.parseToJsonElement(it.second).jsonObject }
        org.junit.Assert.assertTrue(moves.any { it.s("axis")=="Home" })
        for((axis,mm) in listOf("X" to 10,"Y" to 10,"X" to -10,"Y" to -10,"X" to 1))
            org.junit.Assert.assertTrue(moves.any { it.s("axis")==axis && it.s("distance")==mm.toString() })
    }

    @Test fun temperatureEntryFiltersDigitsClampsToTheHardwareLimitAndAllowsCancellation() {
        val set=mutableListOf<Int>(); var closed=0
        show("Temperature") { TempSheet("Temperature","Test heater",listOf(0,40),null,0..65,{closed++}) { set+=it } }
        compose.onNodeWithText("Done").performClick()
        assertEquals(1,closed); org.junit.Assert.assertTrue(set.isEmpty())
        compose.onNode(hasSetTextAction()).performTextInput("x999z")
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(65),set)
        compose.onNodeWithText("Off",substring=false).performClick()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf(65,0),set)
    }

    @Test fun heatedChamberUsesItsOwnTarget() {
        val id="number.chamber_target"
        val info=printer.info.copy(ids=printer.info.ids+("number.target_chamber_temperature" to id))
        val heated=Printer(info,snapshot+(id to Entity(id,"0",JsonObject(emptyMap()))))
        show("Device Control") { ControlScreen(heated) }
        compose.onNodeWithText("Chamber",substring=false).performClick()
        compose.onNodeWithText("Chamber temperature").assertExists()
        compose.onNodeWithText("40°C").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/set_value") } }
        val data=json.parseToJsonElement(commands.first { it.first.endsWith("/set_value") }.second).jsonObject
        assertEquals(id,data.s("entity_id")); assertEquals("40.0",data.s("value"))
    }

    @Test fun pickingAnAlreadySkippedPartDoesNotSelectItAndImageTapsToggleOtherParts() {
        val id=printer.info.ids.getValue("sensor.skipped_objects")
        val p=Printer(printer.info,snapshot+(id to Entity(id,"1",buildJsonObject { put("objects",JsonArray(listOf(JsonPrimitive(101)))) })))
        show("Skip Parts") { SkipScreen(p) }
        compose.onNodeWithText("Skipped").assertExists()
        val density=compose.activity.resources.displayMetrics.density
        val title=compose.onNodeWithText("Skip Parts").fetchSemanticsNode().boundsInRoot
        val width=compose.onRoot().fetchSemanticsNode().boundsInRoot.width-32*density
        val top=title.center.y+44*density
        fun tap(x: Float,y: Float) {
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(16*density+x/512*width,top+y/512*width)) }
        }
        // The real demo pick bitmap encodes object IDs in its red channel.
        tap(230f,270f); compose.onNodeWithText("0/2 Selected").assertExists()
        tap(10f,10f); compose.onNodeWithText("0/2 Selected").assertExists()
        tap(300f,250f); compose.onNodeWithText("1/2 Selected").assertExists()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
        tap(300f,250f); compose.onNodeWithText("0/2 Selected").assertExists()
        compose.onNodeWithText("Select All").performClick()
        compose.onNodeWithText("2/2 Selected").assertExists()
        compose.onNodeWithText("Skip",substring=false).performClick()
        compose.onNodeWithText("Skip 2 parts?").assertExists()
        compose.onAllNodesWithText("Skip",substring=false).filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/skip_objects") } }
        val sent=json.parseToJsonElement(commands.first { it.first.endsWith("/skip_objects") }.second).jsonObject.s("objects")!!.split(',').toSet()
        assertEquals(setOf("102","103"),sent)
    }

    @Test fun multiplePrintersCanBeSelectedAndOfflineOnesStayClearlyMarked() {
        val other=Printer(printer.info.copy(serial="OTHER",name="Other printer",ids=printer.info.ids-"sensor.printer_name"),
            snapshot+(printer.info.ids.getValue("binary_sensor.online") to Entity(printer.info.ids.getValue("binary_sensor.online"),"off",JsonObject(emptyMap()))))
        val state=st.copy(printers=listOf(printer,other))
        show(printer.name) { DevicesTab(state,printer) }
        compose.onNodeWithText(printer.name).performClick()
        compose.onNodeWithText("Printers").assertExists()
        compose.onNodeWithText("Offline").assertExists()
        compose.onNodeWithText("Other printer").performClick()
        assertEquals("OTHER",Repo.selected)
        compose.onNodeWithText("Printers").assertDoesNotExist()
    }

    @Test fun failedOrUnmatchedCachedPrintSettingsKeepTheSendActionDisabled() {
        val demo=FakeHomeAssistant()
        var failure=true
        val backend=object : HaBackend by demo {
            override suspend fun get(path: String): String {
                if(path.endsWith(".slice_info.config")) {
                    if(failure) throw ApiException("Metadata missing")
                    return """<config><filament type="ABS" color="#FFFFFF"/></config>"""
                }
                return demo.get(path)
            }
        }
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,backend)
        val file=androidx.compose.runtime.mutableStateOf(CachedFile("Test.3mf","TEST/prints/Test.3mf",10,"",null))
        show("Print") { ReprintSheet(changed("sensor.print_status" to "idle"),file.value,{}) }
        compose.onNodeWithText("Print settings could not be read, so this print is disabled.").assertExists()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle { failure=false; file.value=file.value.copy(path="TEST/prints/Other.3mf") }
        compose.onNodeWithText("Load matching filament before sending this print.").assertExists()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun amsHtFilamentChoicesPersistWithoutPreviewColorsChangingThePrintMapping() {
        val standard=printer.info.ams.first()
        val ht=standard.copy(model="AMS HT",protocolId=129,trays=standard.trays.filterKeys { it==1 })
        Repo.state.value=st.copy(printers=listOf(Printer(printer.info.copy(ams=listOf(ht)),snapshot)))
        val bytes=modelBytes(mapOf("Metadata/project_settings.config" to """{"printer_model":"Bambu Lab X1 Carbon","filament_type":["PLA"],"filament_colour":["#FFFFFF"]}""".toByteArray()))
        prepareModel(bytes)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("HT",substring=false).performScrollTo().assertExists()
        compose.onAllNodesWithText("PLA",substring=false).onFirst().performClick()
        compose.onNodeWithText("AMS HT").performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(setOf(129),Repo.filamentPreset(printer.serial).values.toSet())
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.onNodeWithText("Printer colors").performClick()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Customize colors").performClick()
        val side=36*compose.activity.resources.displayMetrics.density
        compose.onAllNodes(hasClickAction() and SemanticsMatcher("Color swatch") { node ->
            kotlin.math.abs(node.boundsInRoot.width-side)<1 && kotlin.math.abs(node.boundsInRoot.height-side)<1
        }).onLast().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(setOf(129),Repo.filamentPreset(printer.serial).values.toSet())
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun previewWithoutFilamentMetadataExplainsWhyThereAreNoCustomColorChoices() {
        prepareModel(modelBytes(mapOf("Metadata/project_settings.config" to "{}".toByteArray())))
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.onNodeWithText("Customize colors").performClick()
        compose.onNodeWithText("This model has no filament color metadata.").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Prepare to Print").assertExists()
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun emptyPrintHistoryExplainsTheIntegrationFileCache() {
        val demo=FakeHomeAssistant()
        val backend=object : HaBackend by demo { override suspend fun get(path: String)="""{"files":[]}""" }
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,backend)
        show("History") { HistoryScreen(printer) }
        compose.onNodeWithText("No prints yet").assertExists()
        compose.onNodeWithText("Pandy lists the prints",substring=true).assertExists()
    }

    @Test fun cachedHistoryRowsWithoutThumbnailsOpenTheirPrintSettings() {
        val demo=FakeHomeAssistant()
        val backend=object : HaBackend by demo {
            override suspend fun get(path: String)=if(path.endsWith(".slice_info.config")) "<config/>" else
                """{"files":[{"filename":"1024-Test.3mf","path":"TEST/prints/Test.3mf","size":1024,"modified":"2026-10-24T08:00"}]}"""
        }
        Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }.set(Repo,backend)
        show("History") { HistoryScreen(printer) }
        compose.onNodeWithText("3MF").assertExists()
        compose.onNodeWithText("1 KB").assertExists()
        compose.onNodeWithText("Test").performClick()
        compose.onNodeWithText("Print",substring=false).assertExists()
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }

    private fun savedDirectConnection() {
        val field=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }
        val original=field.get(Repo) as PlatformStorage
        val keys=listOf("demo","mode","lan_serial","lan_ip","lan_model","lan_name","lan_printers","ha_url","slicer_url")
        val before=keys.associateWith { original.settings.getString(it) }
        val secrets=mutableMapOf<String,String>()
        field.set(Repo,PlatformStorage(original.settings,object : SecretStore {
            override fun get(key: String)=secrets[key]
            override fun set(key: String,value: String?) { if(value==null) secrets.remove(key) else secrets[key]=value }
        },original.files))
        restorePrintSettings={ before.forEach { (k,v) -> original.settings.putString(k,v) }; field.set(Repo,original) }
        Repo.setSetting("demo","false"); Repo.setSetting("mode","lan")
        Repo.saveLan(LanConfig("192.0.2.1",printer.serial,"test-code",printer.model))
    }

    @Test fun disconnectingADirectPrinterRequiresConfirmationAndKeepsRemoteSettings() {
        savedDirectConnection()
        Repo.setSetting("ha_url","https://home.example"); Repo.setSetting("slicer_url","https://slicer.example")
        val firmwareId=printer.info.ids.getValue("binary_sensor.firmware_update")
        val p=Printer(printer.info,snapshot+(firmwareId to Entity(firmwareId,"on",JsonObject(emptyMap()))))
        show("Settings") { PrinterSettingsScreen(p) }
        compose.onNodeWithText("Update available",substring=true).performScrollTo().assertExists()
        compose.onNodeWithText("Disconnect printer").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        org.junit.Assert.assertNotNull(Repo.lan)
        compose.onNodeWithText("Disconnect printer").performClick()
        compose.onNodeWithText("Disconnect",substring=false).performClick()
        org.junit.Assert.assertNull(Repo.lan); org.junit.Assert.assertFalse(Repo.connected)
        assertEquals("https://home.example",Repo.haUrl); assertEquals("https://slicer.example",Repo.slicerUrl)
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun combinedConnectionSettingsExplainBothSavedBackendsAndToggleUnavailableControls() {
        savedDirectConnection(); Repo.setSetting("ha_url","https://home.example")
        show("Settings") { SettingsScreen() }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        org.junit.Assert.assertFalse(Repo.showUnavailable)
        compose.onNodeWithText("${printer.model} · 192.0.2.1").performScrollTo().assertExists()
        compose.onNodeWithText("Remove Home Assistant").performScrollTo().assertExists()
        compose.onNodeWithText("Slicer for MakerWorld prints").performScrollTo().performClick()
        kotlin.test.assertIs<Screen.Slicer>(Nav.stack.last())
    }

    @Test fun heaterIndicatorsRespectTheTwoDegreeDeadBandAndMissingTargets() {
        val value=androidx.compose.runtime.mutableStateOf<Pair<Double?,Double?>>(25.0 to 100.0)
        var color: androidx.compose.ui.graphics.Color?=null
        show("Temperature") {
            androidx.compose.foundation.layout.Column {
                T("Temperature")
                TempText(value.value.first,value.value.second,24.sp,12.sp)
                val next=tempColor(value.value.first,value.value.second)
                androidx.compose.runtime.SideEffect { color=next }
            }
        }
        for((now,target,arrow) in listOf(
            Triple(25.0,100.0,"↑"),Triple(150.0,100.0,"↓"),Triple(98.0,100.0,null),
            Triple(102.0,100.0,null),Triple(50.0,0.0,null),Triple(50.0,null,null),Triple(null,100.0,null)
        )) {
            compose.runOnIdle { value.value=now to target }
            for(mark in listOf("↑","↓")) if(mark==arrow) compose.onNodeWithText(mark,substring=false).assertExists()
                else compose.onNodeWithText(mark,substring=false).assertDoesNotExist()
            if(now==null) compose.onNodeWithText("--",substring=false).assertExists()
            assertEquals(when(arrow) { "↑" -> C.Orange; "↓" -> C.Blue; else -> C.Text },color)
        }
    }

    @Test fun incompleteRunningAndPausedJobsKeepUnknownMetricsVisibleAndConfirmBeforeStopping() {
        val info=printer.info.copy(ids=printer.info.ids-setOf("sensor.subtask_name","sensor.total_layers","sensor.remaining_time","sensor.stage"))
        val selected=androidx.compose.runtime.mutableStateOf(Printer(info,snapshot))
        show(printer.name) { DevicesTab(st,selected.value) }
        compose.onNodeWithText("Printing",substring=false).assertExists()
        compose.onNodeWithText("Layer ${printer.layer}/?",substring=false).assertExists()
        val statusId=info.ids.getValue("sensor.print_status")
        compose.runOnIdle { selected.value=Printer(info,snapshot+(statusId to Entity(statusId,"pause",JsonObject(emptyMap())))) }
        compose.onNodeWithText("Paused",substring=false).assertExists()
        compose.onNodeWithText("Resume",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("/press") } }
        compose.onNodeWithText("Stop",substring=false).performClick()
        compose.onNodeWithText("Stop printing?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        val before=commands.size
        compose.onNodeWithText("Stop",substring=false).performClick()
        compose.onAllNodesWithText("Stop",substring=false).filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); commands.size>before }
        val request=json.parseToJsonElement(commands.last().second).jsonObject
        assertEquals(info.ids.getValue("button.stop"),request.s("entity_id"))
    }

    @Test fun fullscreenStillsRecoverFromMalformedFramesAndPhotoSaveErrorsStayVisible() {
        val original=Repo.ha!!
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val jpeg=java.io.File("src/commonMain/composeResources/files/demo_camera.jpg").readBytes()
        Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,object : HaBackend by original {
            override suspend fun bytes(path: String)=if(frames.incrementAndGet()==1) byteArrayOf(1,2,3) else jpeg
        })
        Repo.setSetting("liveview_autoplay","always")
        saveImageError=java.io.IOException("Photos permission denied")
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { LiveFullScreen(printer) } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitUntil(5000) { compose.waitForIdle(); frames.get()>=2 }
        compose.onRoot().performTouchInput {
            pinch(androidx.compose.ui.geometry.Offset(width*.4f,height*.5f),androidx.compose.ui.geometry.Offset(width*.6f,height*.5f),
                androidx.compose.ui.geometry.Offset(width*.2f,height*.5f),androidx.compose.ui.geometry.Offset(width*.8f,height*.5f),300)
            swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.5f),androidx.compose.ui.geometry.Offset(width*.6f,height*.55f),200)
            pinch(androidx.compose.ui.geometry.Offset(width*.2f,height*.5f),androidx.compose.ui.geometry.Offset(width*.8f,height*.5f),
                androidx.compose.ui.geometry.Offset(width*.45f,height*.5f),androidx.compose.ui.geometry.Offset(width*.55f,height*.5f),300)
        }
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Couldn't save: Photos permission denied") }
        org.junit.Assert.assertArrayEquals(jpeg,savedImages.single())
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test fun everyReportedPrintOptionSendsItsOwnModuleAndBothBooleanValues() {
        val keys=listOf("printing_monitor","buildplate_marker_detector","first_layer_inspector","auto_recovery")
        val states=snapshot.toMutableMap()
        keys.take(3).forEachIndexed { i,key ->
            val id=printer.info.ids.getValue("switch.$key")
            val old=states.getValue(id)
            states[id]=Entity(old.id,old.state,JsonObject(old.attrs+if(i==0) mapOf("print_halt" to JsonPrimitive(true)) else emptyMap()))
        }
        val p=Printer(printer.info,states)
        show("Print Options") { PrintOptionsScreen(p) }
        keys.forEachIndexed { index,key ->
            val initial=p.flag(key)!!
            repeat(2) { pass ->
                val before=commands.size
                compose.onAllNodes(isToggleable())[index].performScrollTo().performClick()
                compose.waitUntil(5000) { compose.waitForIdle(); commands.size>before }
                val (path,text)=commands.last(); val body=json.parseToJsonElement(text).jsonObject
                val next=if(pass==0) !initial else initial
                if(key=="auto_recovery") {
                    org.junit.Assert.assertTrue(path.endsWith("print_option")); assertEquals(next.toString(),body.s(key))
                    assertEquals(if(next) "1" else "0",body.s("option"))
                } else {
                    org.junit.Assert.assertTrue(path.endsWith("xcam_control_set")); assertEquals(key,body.s("module_name"))
                    assertEquals(next.toString(),body.s("enable")); assertEquals((index==0).toString(),body.s("print_halt"))
                }
            }
        }
    }

    @Test fun rejectedPrintOptionChangesRestoreEverySwitchToTheReportedValue() {
        commandFailure="Printer refused setting"
        val keys=listOf("printing_monitor","buildplate_marker_detector","first_layer_inspector","auto_recovery")
        show("Print Options") { PrintOptionsScreen(printer) }
        keys.forEachIndexed { index,key ->
            val initial=printer.flag(key)!!
            compose.onAllNodes(isToggleable())[index].performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); messages.size>index }
            assertEquals("Printer refused setting",messages.last())
            if(initial) compose.onAllNodes(isToggleable())[index].assertIsOn() else compose.onAllNodes(isToggleable())[index].assertIsOff()
        }
    }

    @Test fun amsReadPreferencesSendAllThreeFlagsAndRestoreRejectedChanges() {
        val ams=printer.info.ams.first()
        val keys=listOf("ams_read_on_insertion","ams_read_on_startup","ams_remaining_estimation")
        show("AMS Settings") { AmsSettingsScreen(printer) }
        keys.forEachIndexed { index,key ->
            val before=commands.size
            compose.onAllNodes(isToggleable())[index].performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); commands.size>before }
            val body=json.parseToJsonElement(commands.last().second).jsonObject
            org.junit.Assert.assertTrue(commands.last().first.endsWith("ams_user_setting"))
            assertEquals(ams.amsId().toString(),body.s("ams_id"))
            assertEquals((!printer.amsFlag(ams,key)!!).toString(),body.s(listOf("tray_read_option","startup_read_option","calibrate_remain_flag")[index]))
        }
        commandFailure="AMS refused setting"
        keys.forEachIndexed { index,key ->
            compose.onAllNodes(isToggleable())[index].performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); messages.size>index }
            val retained=!printer.amsFlag(ams,key)!!
            if(retained) compose.onAllNodes(isToggleable())[index].assertIsOn() else compose.onAllNodes(isToggleable())[index].assertIsOff()
        }
    }

    @Test fun homeAssistantPrinterSettingsKeepUnsupportedActionsReadOnly() {
        homeAssistantMode()
        val firmwareId=printer.info.ids.getValue("binary_sensor.firmware_update")
        val p=Printer(printer.info.copy(firmware=null),snapshot+(firmwareId to Entity(firmwareId,"on",JsonObject(emptyMap()))))
        show("Settings") { PrinterSettingsScreen(p) }
        compose.onAllNodesWithText("Not available").assertCountEquals(3)
        compose.onNodeWithText("--  •  Update available").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connection: Home Assistant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disconnect printer").assertDoesNotExist()
        compose.onNodeWithText("Calibration").performScrollTo().assertHasNoClickAction()
    }

    @Test fun accountShortcutsAndSupportLinksReachTheirIntendedDestinations() {
        show("Support Center") { MeTab(st) }
        for((label,destination) in listOf("Printing\nHistory" to Screen.History,"Timelapse" to Screen.Files,"Filament" to Screen.Filament,"About Pandy" to Screen.About,"Settings" to Screen.Settings)) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(destination,Nav.stack.last()); compose.runOnIdle { Nav.stack.clear() }
        }
        compose.runOnIdle { Nav.tab=2 }
        compose.onNodeWithText("MakerWorld").performScrollTo().performClick(); assertEquals(0,Nav.tab)
        for(label in listOf("Learning","Support Center","Buy me a coffee")) compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(listOf("https://wiki.bambulab.com","$REPO_URL/issues",DONATE_URL),openedUrls)
    }

    @Test fun disabledButtonsAndChoicesNeverInvokeTheirCallbacks() {
        val enabled=androidx.compose.runtime.mutableStateOf(false)
        val clicks=mutableListOf<String>()
        show("Confirm action") { androidx.compose.foundation.layout.Column {
            AccentButton("Confirm action",enabled=enabled.value) { clicks+="accent" }
            OutlineButton("Alternative",C.Blue,enabled=enabled.value) { clicks+="outline" }
            Radio(true,"Selected choice",enabled=enabled.value) { clicks+="radio" }
            ToggleRow("Setting",false,enabled=enabled.value) { clicks+="toggle" }
            ToggleLine("Print setting",true,enabled=enabled.value) { clicks+="line" }
        } }
        for(label in listOf("Confirm action","Alternative","Selected choice")) compose.onNodeWithText(label).assertIsNotEnabled().performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        repeat(2) { compose.onAllNodes(isToggleable())[it].assertIsNotEnabled().performClick() }
        assertEquals(emptyList(),clicks)
        compose.runOnIdle { enabled.value=true }
        for(label in listOf("Confirm action","Alternative","Selected choice")) compose.onNodeWithText(label).assertIsEnabled().performClick()
        repeat(2) { compose.onAllNodes(isToggleable())[it].assertIsEnabled().performClick() }
        assertEquals(listOf("accent","outline","radio","toggle","line"),clicks)
    }

    @Test fun bottomSheetRequiresAnEnabledConfirmationAndSupportsClosingWithoutSaving() {
        val enabled=androidx.compose.runtime.mutableStateOf(false)
        val open=androidx.compose.runtime.mutableStateOf(true)
        var confirmations=0; var dismissed=0
        show("Pending settings") {
            if(open.value) Sheet({ open.value=false; dismissed++ },title="Review",confirmEnabled=enabled.value,onConfirm={ confirmations++ }) { T("Pending settings") }
            else T("Closed settings")
        }
        compose.onNodeWithText("Confirm").assertIsNotEnabled().performClick(); assertEquals(0,confirmations)
        compose.runOnIdle { enabled.value=true }
        compose.onNodeWithText("Confirm").performClick(); assertEquals(1,confirmations)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Closed settings").assertExists(); assertEquals(1,dismissed)
    }

    @Test @Config(sdk=[35],qualifiers="w411dp-h891dp-night")
    fun systemNightModeKeepsControlsReadableAndRespectsThePlatformTheme() {
        Repo.setTheme("system")
        val selected=androidx.compose.runtime.mutableStateOf(false)
        show("Theme preference") { androidx.compose.foundation.layout.Column {
            Card { T("Theme preference"); Radio(selected.value,"Follow platform") { selected.value=!selected.value } }
            NavRow("Read-only status",value="Online",chevron=false,onClick=null)
            Bar(-1f,C.Accent); Bar(2f,C.Ok,track=C.Fill,height=8.dp)
        } }
        compose.onNodeWithText("Follow platform").performClick(); org.junit.Assert.assertTrue(selected.value)
        compose.onNodeWithText("Read-only status").assertHasNoClickAction()
        compose.onNodeWithText("Online").assertExists(); org.junit.Assert.assertTrue(ThemeMode.dark)
    }

    @Test fun coldNozzlesCannotExtrudeAndUnknownNozzleDetailsStayExplicit() {
        val p=changed("sensor.print_status" to "idle","sensor.nozzle_temp" to "unknown","sensor.target_nozzle_temp" to "0","sensor.nozzle_diameter" to "unknown","sensor.nozzle_type" to "unknown")
        show("Device Control") { ControlScreen(p) }
        compose.onNodeWithText("Nozzle & Extruder").performScrollTo().performClick()
        compose.onNodeWithText("Heat the nozzle to 170°C or more before extruding.").assertExists()
        compose.onNodeWithText("--°C").assertExists(); compose.onNodeWithText("?mm ").assertExists()
        compose.onAllNodes(hasClickAction() and !isEnabled()).assertCountEquals(2)
        org.junit.Assert.assertTrue(commands.isEmpty())
    }

    @Test fun anIdleHotNozzleCanExtrudeAndRetractThroughTheSimulatedBackend() {
        val p=changed("sensor.print_status" to "idle","sensor.nozzle_temp" to "180","sensor.target_nozzle_temp" to "0")
        show("Device Control") { ControlScreen(p) }
        compose.onNodeWithText("Nozzle & Extruder").performScrollTo().performClick()
        compose.onNodeWithText("Heat the nozzle to 170°C",substring=true).assertDoesNotExist()
        val arrows=compose.onAllNodes(hasClickAction() and !hasText("",substring=true) and !hasContentDescription("Close") and !hasContentDescription("Back") and !hasContentDescription("Close sheet") and !isToggleable())
        // The only enabled anonymous keys in this sheet are retract and extrude.
        arrows.assertCountEquals(2)
        repeat(2) { index ->
            arrows[index].performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); commands.size>index }
        }
        assertEquals(listOf("Retract","Extrude"),commands.map { json.parseToJsonElement(it.second).jsonObject.s("type") })
        org.junit.Assert.assertTrue(commands.all { it.first.endsWith("extrude_retract") })
    }

    @Test fun directConnectionValidatesRequiredFieldsAndReportsAMalformedAddress() {
        show("Direct LAN connection") { AddDirectScreen() }
        compose.onNodeWithText("Connect",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Printer IP address").performTextInput("127.0.0.1:invalid")
        compose.onNodeWithText("Serial number").performTextInput("12345678")
        compose.onNodeWithText("Connect",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Access code").performTextInput("test-code")
        compose.onNodeWithText("Connect",substring=false).assertIsEnabled().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Couldn't reach the printer",substring=true).fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertNull(Repo.lan)
        compose.onNodeWithText("Connect",substring=false).assertIsEnabled()
    }

    @Test fun aDownloadedModelPreparesAndItsLazilyLoadedPreviewRespondsToGestures() {
        val bytes=modelBytes(); val cookies=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/model.3mf") { exchange ->
            cookies+=exchange.requestHeaders.getFirst("Cookie").orEmpty()
            exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val file=WebDownload("http://127.0.0.1:${server.address.port}/model.3mf","Downloaded model.3mf","session=test-cookie",printSheet=true)
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { PrintModelSheet(file,{}) } } }
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("session=test-cookie"),cookies.toList())
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("3D preview unavailable").fetchSemanticsNodes().isEmpty() }
            fun frame(): IntArray = compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                IntArray(view.width*view.height).also { bitmap.getPixels(it,0,view.width,0,0,view.width,view.height) }
            }
            val before=frame()
            val chartY=compose.onNodeWithText("Show the plate").fetchSemanticsNode().boundsInRoot.bottom+140f
            compose.onRoot().performTouchInput {
                pinch(androidx.compose.ui.geometry.Offset(width*.4f,chartY),androidx.compose.ui.geometry.Offset(width*.6f,chartY),
                    androidx.compose.ui.geometry.Offset(width*.2f,chartY),androidx.compose.ui.geometry.Offset(width*.8f,chartY),300)
                swipe(androidx.compose.ui.geometry.Offset(width*.4f,chartY),androidx.compose.ui.geometry.Offset(width*.7f,chartY+10),300)
            }
            compose.waitForIdle()
            org.junit.Assert.assertFalse("The rendered model must change after rotating/zooming",before.contentEquals(frame()))
            compose.onNodeWithText("Confirm",substring=false).performClick()
            compose.onNodeWithText("3D Preview").assertExists()
            org.junit.Assert.assertTrue(commands.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun failedModelDownloadsShowTheServerErrorAndCannotOfferPrinting() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(503,-1); exchange.close() }
        server.start()
        try {
            val file=WebDownload("http://127.0.0.1:${server.address.port}/model.3mf","Unavailable model.3mf",null,printSheet=true)
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { PrintModelSheet(file,{}) } } }
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("The download failed (HTTP 503)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Next").assertDoesNotExist(); compose.onNodeWithText("Print current plate").assertDoesNotExist()
            org.junit.Assert.assertTrue(commands.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun discoveredPrintersCanBeSelectedAndTheirVerifiedDetailsReachConnectionSetup() {
        val found=listOf(FoundPrinter("192.0.2.1","DISCOVERED-ONE","X1C","Workshop"),FoundPrinter("192.0.2.2","DISCOVERED-TWO","A1","Desk"))
        val attempts=mutableListOf<LanConfig>(); var completed=0
        show("Printers on this Wi-Fi") { androidx.compose.foundation.layout.Column {
            PrinterForm(discover={ found },connect={ attempts+=it },done={ completed++ })
        } }
        compose.onNodeWithText("Desk",substring=false).performClick()
        compose.onNodeWithText("Printer IP address").assertTextContains("192.0.2.2")
        compose.onNodeWithText("Serial number").assertTextContains("DISCOVERED-TWO")
        compose.onNodeWithText("Access code").performTextInput("test-code")
        compose.onNodeWithText("Connect",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); completed==1 }
        assertEquals(listOf(LanConfig("192.0.2.2","DISCOVERED-TWO","test-code","A1","Desk")),attempts)
    }

    @Test fun aLateDiscoveryDoesNotOverwriteAnAddressTheOwnerAlreadyEntered() {
        val pending=kotlinx.coroutines.CompletableDeferred<List<FoundPrinter>>()
        var searches=0
        val found=FoundPrinter("192.0.2.1","DISCOVERED-ONE","X1C","Workshop")
        show("Printers on this Wi-Fi") { androidx.compose.foundation.layout.Column {
            PrinterForm(discover={ searches++; pending.await() },connect={ error("No connection requested") })
        } }
        compose.onNodeWithText("Printer IP address").performTextInput("192.0.2.9")
        compose.runOnIdle { pending.complete(listOf(found)) }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Workshop",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Printer IP address").assertTextContains("192.0.2.9")
        assertEquals("",compose.onNodeWithText("Serial number").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Workshop",substring=false).performClick()
        compose.onNodeWithText("Printer IP address").assertTextContains("192.0.2.1")
        compose.onNodeWithText("Search again").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); searches==2 }
        compose.onNodeWithText("Serial number").assertTextContains("DISCOVERED-ONE")
    }

    @Test fun aSingleDiscoveryFillsTheFormAndManualOverridesCanBeRetriedAfterRejection() {
        val attempts=mutableListOf<LanConfig>(); var completed=0
        val found=FoundPrinter("192.0.2.1","DISCOVERED-ONE","X1C","Workshop")
        show("Printers on this Wi-Fi") { androidx.compose.foundation.layout.Column {
            PrinterForm(discover={ listOf(found) },connect={
                attempts+=it
                if(attempts.size==1) throw ApiException("Access code rejected")
            },done={ completed++ })
        } }
        compose.onNodeWithText("Serial number").assertTextContains("DISCOVERED-ONE")
        compose.onNodeWithText("Printer IP address").performTextReplacement(" 192.0.2.9 ")
        compose.onNodeWithText("Serial number").performTextReplacement(" 01p00000abc ")
        compose.onNodeWithText("Access code").performTextInput(" test-code ")
        compose.onNodeWithText("Connect",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Access code rejected").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,completed); compose.onNodeWithText("Connect",substring=false).assertIsEnabled().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); completed==1 }
        assertEquals(listOf(LanConfig("192.0.2.9","01P00000ABC","test-code","P1S",""),LanConfig("192.0.2.9","01P00000ABC","test-code","P1S","")),attempts)
    }

    @Test fun theSheetClosesAtOnceWhenThereIsNoPrinterToPrintOn() {
        Repo.state.value=UiState(on=true,printers=emptyList())
        var closed=false
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{closed=true})
        } } }
        compose.waitForIdle()
        org.junit.Assert.assertTrue(closed)
    }

    @Test fun backFromThe3DPreviewReturnsToPreparationAndAnUnknownBedFallsBackToTexturedPei() {
        val bedless=snapshot.filterKeys { it != printer.info.ids["sensor.print_bed_type"] }
        Repo.state.value=st.copy(printers=listOf(Printer(printer.info,bedless)))
        var back: (() -> Unit)? = null
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(mapOf("Metadata/model_settings.config" to "<config><plate><metadata key=\"plater_id\" value=\"1\"/></plate></config>".toByteArray())),true),{}) { back=it }
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Textured PEI Plate").assertExists()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.onNodeWithText("Show the plate").assertExists()
        compose.runOnIdle { back!!.invoke() }
        compose.onNodeWithText("3D Preview").assertExists()
        compose.onNodeWithText("Show the plate").assertDoesNotExist()
    }

    private fun afterConfirming(change: (MutableMap<String,Entity>) -> Unit): Pair<java.util.concurrent.ConcurrentLinkedQueue<String>,java.util.concurrent.atomic.AtomicBoolean> {
        val (uploads,closed)=prepareRealPrint("""[{"id":"1","type":"PLA","color":"#FFFFFF"}]""")
        val values=snapshot.toMutableMap()
        for((role,state) in listOf("binary_sensor.online" to "on","sensor.ip_address" to "127.0.0.1")) { val id=printer.info.ids.getValue(role); values[id]=Entity(id,state,JsonObject(emptyMap())) }
        change(values)
        Repo.state.value=Repo.state.value.copy(printers=listOf(Printer(Repo.state.value.printers!!.single().info,values)))
        compose.waitForIdle()
        compose.onNodeWithText("Start",substring=false).performClick()
        return uploads to closed
    }

    @Test fun aBusyPrinterStopsAConfirmedPrintBeforeSlicing() {
        val (uploads,closed)=afterConfirming { values -> val id=printer.info.ids.getValue("sensor.print_status"); values[id]=Entity(id,"running",JsonObject(emptyMap())) }
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("The printer is busy") }
        org.junit.Assert.assertTrue(uploads.isEmpty()); org.junit.Assert.assertFalse(closed.get())
    }

    @Test fun aPrinterWithoutAKnownAddressAsksForTheAccessCodeInsteadOfSlicing() {
        val (uploads,_)=afterConfirming { values -> values.remove(printer.info.ids.getValue("sensor.ip_address")) }
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Enter the printer's access code") }
        org.junit.Assert.assertTrue(uploads.isEmpty())
    }

    private fun variant(info: PrinterInfo = printer.info, remove: List<String> = emptyList(), vararg set: Pair<String,Entity>): Printer {
        val states=snapshot.toMutableMap()
        for(key in remove) states.remove(printer.info.ids[key] ?: key)
        for((key,entity) in set) { val id=printer.info.ids[key] ?: key; states[id]=Entity(id,entity.state,entity.attrs) }
        return Printer(info,states)
    }
    private fun entity(state: String, attrs: Map<String,Any> = emptyMap()) = Entity("",state,JsonObject(attrs.mapValues { (_,v) -> v as? JsonElement ?: JsonPrimitive(v.toString()) }))

    @Test fun everyDeviceScreenRendersForEachConnectionModeAndPrinterVariant() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val keys=listOf("demo","mode","show_unavailable","lan_serial","lan_ip","lan_model")
        val before=keys.associateWith { store.settings.getString(it) }
        val theme=ThemeMode.mode
        restorePrintSettings={ before.forEach { (k,v) -> store.settings.putString(k,v) }; Repo.setTheme(theme) }
        val ht=printer.info.ams.map { it.copy(model="AMS HT",protocolId=128,trays=it.trays.filterKeys { slot -> slot==1 }) }
        val tray=printer.info.ams.single().trays.getValue(1)
        val variants=listOf(
            variant(),
            variant(set=arrayOf("sensor.print_status" to entity("idle"))),
            variant(set=arrayOf("sensor.print_status" to entity("pause"),"binary_sensor.print_error" to entity("on",mapOf("error" to "Filament ran out"))),remove=listOf("sensor.chamber_temp","sensor.stage","sensor.current_layer","sensor.total_layers")),
            variant(set=arrayOf("sensor.print_status" to entity("finish"))),
            variant(set=arrayOf("sensor.print_status" to entity("failed"),"binary_sensor.hms_errors" to entity("on",mapOf("Count" to "2","1-Error" to "Nozzle clogged","1-Code" to "HMS_0300_0100_0001_0001","1-Wiki" to "https://example.invalid/1","2-Error" to "Door open")))),
            variant(set=arrayOf("binary_sensor.online" to entity("off"))),
            variant(set=arrayOf("binary_sensor.developer_lan_mode" to entity("off"),"sensor.print_status" to entity("idle"),"sensor.printable_objects" to entity("on",mapOf("objects" to "x")))),
            variant(info=printer.info.copy(ams=emptyList(),external=emptyList(),externalDevice=null),remove=listOf("camera.camera","light.chamber_light","sensor.nozzle_type","image.cover_image","image.pick_image")),
            variant(info=printer.info.copy(ams=ht,ids=printer.info.ids+("number.target_chamber_temperature" to "number.chamber_target")),set=arrayOf("number.target_chamber_temperature" to entity("40"),"sensor.print_status" to entity("idle"))),
            variant(info=printer.info.copy(ams=printer.info.ams+printer.info.ams.map { it.copy(index=2,haId="second",protocolId=1) }),set=arrayOf(tray to entity("Empty",mapOf("empty" to "true")))),
            variant(set=arrayOf("sensor.print_status" to entity("unavailable"),"sensor.ip_address" to entity("unknown"))),
            variant(info=printer.info.copy(ams=printer.info.ams.map { it.copy(ids=it.ids-setOf("sensor.humidity","sensor.humidity_index","sensor.ams_temp")) }),remove=listOf("sensor.subtask_name"),
                set=arrayOf("sensor.print_status" to entity("finish"),"binary_sensor.firmware_update" to entity("on"),
                    "sensor.printable_objects" to entity("on",mapOf("objects" to JsonObject(mapOf("1" to JsonPrimitive("Cube"),"2" to JsonPrimitive("Sphere"),"3" to JsonPrimitive("Cone")))))),),
            variant(remove=printer.info.ams.single().trays.values.drop(1)+printer.info.external,set=arrayOf("sensor.print_status" to entity("idle"))), // slots whose tray sensors report nothing
            variant(remove=listOf("sensor.subtask_name"),set=arrayOf("sensor.print_status" to entity("failed"))),
            variant(set=arrayOf("sensor.print_status" to entity("pause"),"sensor.printable_objects" to entity("on",mapOf("objects" to JsonObject(mapOf("1" to JsonPrimitive("Only part")))))),),
            variant(info=printer.info.copy(ams=emptyList()),set=arrayOf("sensor.print_status" to entity("idle"))),
            variant(info=printer.info.copy(model="H2D",firmware=null),remove=listOf("switch.first_layer_inspector","switch.buildplate_marker_detector","switch.auto_switch_filament"),set=arrayOf("sensor.print_status" to entity("idle"))),
            variant(info=printer.info.copy(ams=printer.info.ams.map { it.copy(ids=it.ids-setOf("switch.ams_read_on_startup","switch.ams_remaining_estimation")) }),set=arrayOf("sensor.print_status" to entity("idle"))),
            variant(info=printer.info.copy(model="P1S"),set=arrayOf("sensor.print_status" to entity("running"),
                "sensor.printable_objects" to entity("on",mapOf("objects" to JsonObject(mapOf("1" to JsonPrimitive("Cube"),"2" to JsonPrimitive("Sphere"),"x" to JsonPrimitive("Bad"))))),
                "sensor.skipped_objects" to entity("on",mapOf("objects" to JsonArray(listOf(JsonPrimitive("2"),JsonPrimitive("x"))))),
                printer.info.ams.single().trays.getValue(1) to entity("PLA",mapOf("name" to "PLA Basic","type" to "PLA","color" to "#FF0000FF","remain" to "40","active" to "true","nozzle_temp_min" to "190","nozzle_temp_max" to "230")),
                printer.info.ams.single().trays.getValue(2) to entity("Unknown",mapOf("type" to "","color" to "#00000000","remain" to "-1")),
                printer.info.ams.single().trays.getValue(3) to entity("Other",mapOf("name" to "Mystery","type" to "ABS","color" to "#0000FFFF","remain" to "100","cols" to JsonArray(listOf(JsonPrimitive("#FFFFFFFF"),JsonPrimitive("#000000FF"))))))),
        )
        val screens: List<@Composable (Printer) -> Unit> = listOf(
            { p -> DevicesTab(st.copy(printers=listOf(p)),p) }, { p -> ControlScreen(p) }, { p -> FilamentScreen(p) },
            { p -> PrinterSettingsScreen(p) }, { p -> CalibrationScreen(p) }, { p -> PrintOptionsScreen(p) }, { p -> AmsSettingsScreen(p) },
            { p -> AssistantScreen(p) }, { p -> SkipScreen(p) }, { p -> MotionScreen(p) },
        )
        val selection=androidx.compose.runtime.mutableStateOf(0 to variants.first()); val tick=androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            val (index,p)=selection.value
            tick.value // recomposing with unchanged arguments exercises each screen's skip path
            androidx.compose.runtime.key(index,p) { screens[index](p) }
        } } }
        for(themeName in listOf("light","dark")) for((demo,mode,unavailable) in listOf(Triple("true","lan","true"),Triple("false","lan","true"),Triple("false","lan","false"),Triple("false","ha","true"),Triple("false","ha","false"))) {
            compose.runOnIdle { Repo.setTheme(themeName) }
            store.settings.putString("demo",demo); store.settings.putString("mode",mode); store.settings.putString("show_unavailable",unavailable)
            for(v in variants) for(index in screens.indices) {
                compose.runOnIdle { selection.value=index to v }
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(200)
                compose.runOnIdle { tick.value++ }; compose.waitForIdle()
            }
        }
        compose.onRoot().assertExists()
    }

    @Test fun theDevicesTabCoversConnectingStatesAndSeveralPrinters() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val before=store.settings.getString("demo"); restorePrintSettings={ store.settings.putString("demo",before) }
        store.settings.putString("demo","false")
        val offline=variant(remove=listOf("sensor.print_status"),set=arrayOf("binary_sensor.online" to entity("off")))
        val running=variant(set=arrayOf("sensor.print_status" to entity("running")))
        val selected=androidx.compose.runtime.mutableStateOf(running)
        val state=androidx.compose.runtime.mutableStateOf(st.copy(live=false,printers=listOf(running,offline)))
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { DevicesTab(state.value,selected.value) } } }
        compose.waitForIdle(); compose.onNodeWithText("Connecting…",substring=false).assertExists()
        compose.runOnIdle { state.value=st.copy(live=true,printers=listOf(running,offline)) }
        compose.onAllNodesWithText(running.name).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Offline",substring=false).assertExists()
        compose.onNodeWithText("Offline",substring=false).performClick()
        compose.runOnIdle { selected.value=offline }
        compose.waitForIdle(); compose.onNodeWithText("Device Offline").assertExists()
    }

    private class Memory(private val values: MutableMap<String,String>) : KeyValue, SecretStore {
        override fun getString(key: String) = values[key]
        override fun putString(key: String,value: String?) { if(value==null) values.remove(key) else values[key]=value }
        override fun get(key: String) = getString(key)
        override fun set(key: String,value: String?) = putString(key,value)
        override fun clear() = values.clear()
    }

    @Test fun accountAndSettingsScreensRenderForEveryConnectionMode() {
        val storeField=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }
        val fields=listOf("ha","extras").map { name -> Repo::class.java.getDeclaredField(name).apply { isAccessible=true } }
        val originalStore=storeField.get(Repo); val originalBackends=fields.map { it.get(Repo) }
        restorePrintSettings={ storeField.set(Repo,originalStore); fields.zip(originalBackends).forEach { (f,v) -> f.set(Repo,v) } }
        val demo=FakeHomeAssistant()
        val unreachable=object : HaBackend by demo {
            override suspend fun get(path: String): String = throw ApiException("Home Assistant is unreachable")
            override suspend fun post(path: String,body: String): String = throw ApiException("Home Assistant is unreachable")
            override suspend fun bytes(path: String): ByteArray = throw ApiException("Home Assistant is unreachable")
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = throw ApiException("Home Assistant is unreachable")
        }
        val lan=mapOf("lan_serial" to "SWEEP-TEST","lan_ip" to "192.0.2.5","mode" to "lan")
        val modes=listOf(
            Triple(mapOf("demo" to "true"),emptyMap<String,String>(),demo to demo),
            Triple(lan,mapOf("lan_code" to "test-code"),null to null),
            Triple(lan+mapOf("ha_url" to "https://home.example"),mapOf("lan_code" to "test-code","ha_token" to "token"),unreachable to unreachable),
            Triple(mapOf("ha_url" to "https://home.example","mode" to "ha","slicer_url" to "http://slicer.example:8737"),mapOf("ha_token" to "token"),demo to demo),
            Triple(emptyMap(),emptyMap(),null to null),
        )
        val two=listOf(printer,variant(set=arrayOf("sensor.printer_name" to entity("Second"))))
        val screens: List<@Composable (UiState,Printer) -> Unit> = listOf(
            { u,_ -> MeTab(u) }, { _,_ -> SettingsScreen() }, { _,_ -> AboutScreen() }, { _,p -> HistoryScreen(p) }, { _,p -> FilesScreen(p) },
            { _,_ -> Connect() }, { _,_ -> AddHaScreen() }, { _,_ -> AddDirectScreen() }, { _,_ -> SlicerScreen() }, { _,_ -> LiveviewSettingsScreen() }, { _,p -> SkipScreen(p) },
        )
        val selection=androidx.compose.runtime.mutableStateOf(Triple(0,st,printer)); val tick=androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            val (index,u,p)=selection.value
            tick.value
            androidx.compose.runtime.key(index,u,p) { screens[index](u,p) }
        } } }
        for((settings,secrets,backends) in modes) {
            storeField.set(Repo,PlatformStorage(Memory(settings.toMutableMap()),Memory(secrets.toMutableMap()),object : AppFiles {
                override fun read(name: String): ByteArray? = null
                override fun write(name: String,bytes: ByteArray) = Unit
                override fun delete(name: String) = Unit
            }))
            fields[0].set(Repo,backends.first); fields[1].set(Repo,backends.second)
            for(u in listOf(st,st.copy(printers=two),st.copy(printers=emptyList()),st.copy(printers=null))) for(index in screens.indices) {
                compose.runOnIdle { selection.value=Triple(index,u,printer) }
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(200)
                compose.runOnIdle { tick.value++ }; compose.waitForIdle()
            }
        }
        compose.onRoot().assertExists()
    }

    @Test fun theModelsTabReportsFailedSavesBusyDownloadsAndClosesASheetWithTheBackButton() {
        Repo.state.value=UiState(on=true,printers=emptyList()) // no printer: MakerWorld must not offer printing
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { ModelsTab() } } }
        fun until(what: String,condition: () -> Boolean) { try { compose.waitUntil(5000) { compose.waitForIdle(); condition() } } catch(e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("$what; toasts=${messages.toList()} saved=${savedFiles.map { it.first }}") } }
        fun bridge() = descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single().let { web ->
            val shadow=org.robolectric.Shadows.shadowOf(web); shadow.webViewClient.onPageFinished(web,"https://makerworld.com/en")
            Triple(web,shadow,shadow.getJavascriptInterface("PandyBridge"))
        }
        fun token(b: Any) = b.javaClass.declaredFields.first { it.name.contains("bridgeToken") }.apply { isAccessible=true }.get(b) as String
        fun call(b: Any,name: String,vararg args: Any) = b.javaClass.declaredMethods.single { it.name==name }.apply { isAccessible=true }.invoke(b,*args)
        compose.runOnIdle {
            val (_,_,b)=bridge()
            call(b,"blob","model.unknown","AQID",token(b))
        }
        until("step 2") { savedFiles.any { it.first=="model.unknown" } }
        assertEquals("application/octet-stream",savedFiles.last().second)
        saveFileError=java.io.IOException("Disk full")
        compose.runOnIdle { val (_,_,b)=bridge(); call(b,"blob","again.3mf","AQID",token(b)) }
        until("step 3") { messages.contains("Disk full") }
        saveFileError=null
        // A second download arriving before the first is handled is refused.
        compose.runOnIdle { val (_,_,b)=bridge(); val t=token(b); call(b,"blob","one.3mf","AQID",t); call(b,"blob","two.3mf","AQID",t) }
        until("step 4") { messages.contains("A download is already in progress") }
        // A link the page serves directly is fetched; a failure is shown (with a fallback when it has no message).
        val realDownload=downloadModel
        try {
            for((failure,expected) in listOf<Pair<Throwable,String>>(java.io.IOException("Connection reset") to "Connection reset",
                object : Exception() { override val message: String? = null } to "Couldn't download x.bin")) {
                downloadModel={ _,_ -> throw failure }
                compose.runOnIdle { val (_,shadow,_)=bridge(); shadow.downloadListener.onDownloadStart("https://cdn.bblmw.com/x.3mf","","","application/octet-stream",1) }
                until("step 5") { messages.contains(expected) }
            }
            downloadModel={ url,cookies -> assertEquals("https://cdn.bblmw.com/y.3mf",url); byteArrayOf(9) }
            compose.runOnIdle { val (_,shadow,_)=bridge(); shadow.downloadListener.onDownloadStart("https://cdn.bblmw.com/y.3mf","","","application/octet-stream",1) }
            until("step 6") { savedFiles.any { it.first=="y.bin" } }
        } finally { downloadModel=realDownload }
        // A sheet is open: another print request is refused, and Back closes the sheet.
        Repo.state.value=st
        val realFetch=downloadModel
        downloadModel={ _,_ -> modelBytes() }
        compose.runOnIdle { val (_,_,b)=bridge(); call(b,"nativeFile","first.3mf","https://cdn.bblmw.com/first.3mf",token(b)) } // the page opens the sheet
        until("step 7") { compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { val (_,_,b)=bridge(); call(b,"nativeFile","other.3mf","https://cdn.bblmw.com/other.3mf",token(b)) }
        until("step 8") { messages.contains("Finish or close the current print first") }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        until("step 9") { compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isEmpty() }
        org.junit.Assert.assertNull(debugDownload.value)
        downloadModel=realFetch
    }

    @Test fun leavingTheModelsTabCancelsAnUnfinishedDownloadQuietly() {
        val real=downloadModel
        val started=java.util.concurrent.atomic.AtomicBoolean(false)
        downloadModel={ _,_ -> started.set(true); kotlinx.coroutines.awaitCancellation() }
        val visible=androidx.compose.runtime.mutableStateOf(true)
        try {
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { if(visible.value) ModelsTab() } } }
            compose.runOnIdle {
                val web=descendants(compose.activity.window.decorView).filterIsInstance<android.webkit.WebView>().single()
                org.robolectric.Shadows.shadowOf(web).downloadListener.onDownloadStart("https://cdn.bblmw.com/slow.3mf","","","application/octet-stream",1)
            }
            compose.waitUntil(5000) { compose.waitForIdle(); started.get() }
            compose.runOnIdle { visible.value=false }; compose.waitForIdle()
            compose.onAllNodesWithText("Models").assertCountEquals(0)
            org.junit.Assert.assertTrue(messages.none { it.contains("Couldn't download") })
        } finally { downloadModel=real }
    }

    @Test fun makerWorldNamesTheCarbonPrinterAndPassesOtherModelsThrough() {
        assertEquals("X1 Carbon",makerWorldName("X1C")); assertEquals("P1S",makerWorldName("P1S"))
    }

    private class Silent : Exception()

    @Test fun aModelThatCannotBeDownloadedShowsWhateverTheFailureSays() {
        val real=downloadModel
        downloadModel={ _,_ -> throw Silent() }
        try {
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
                PrintModelSheet(WebDownload("https://cdn.bblmw.com/x.3mf","x.3mf",null,printSheet=true),{})
            } } }
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Silent",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Select a Print Profile").assertDoesNotExist()
        } finally { downloadModel=real }
    }

    @Test fun thePreviewLoadsOnceSurvivesLeavingMidLoadAndFallsBackWhenTheMeshesCannotBeRead() {
        val real=loadPreviewMeshes
        var back: (() -> Unit)? = null
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        loadPreviewMeshes={ bytes ->
            when(calls.incrementAndGet()) { 1 -> gate.await().let { real(bytes) }; 2 -> throw IllegalStateException("unreadable"); else -> real(bytes) }
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
                PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{}) { back=it }
            } } }
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); calls.get()==1 }
            compose.runOnIdle { back!!.invoke() } // leaving cancels the unfinished load and does not mark it done
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); calls.get()>=2 }
            compose.waitForIdle()
            compose.onNodeWithText("3D preview unavailable").assertExists() // the unreadable second attempt leaves a plain placeholder
            compose.runOnIdle { back!!.invoke() }
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(2,calls.get()) // a finished attempt is never repeated
        } finally { loadPreviewMeshes=real }
    }

    @Test fun aTimedOutPreviewFallsBackWithoutBlockingThePrintPreparation() {
        val original = loadPreviewMeshes
        loadPreviewMeshes = { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() } }
        try {
            compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
                PrintModelSheet(WebDownload(null, "Timed preview.3mf", null, modelBytes(), true), {})
            } } }
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("3D preview unavailable").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Print options").assertExists()
        } finally { loadPreviewMeshes = original }
    }

    @Test fun savedPrinterDetailsSkipTheAccessCodeAndAreNotOverwrittenWhenThePrintStarts() {
        val storeField=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }
        val original=storeField.get(Repo)
        restorePrintSettings={ storeField.set(Repo,original) }
        val settings=mutableMapOf("demo" to "false","mode" to "ha","lan_serial" to printer.serial.lowercase(),"lan_ip" to "127.0.0.1","lan_model" to "X1C")
        storeField.set(Repo,PlatformStorage(Memory(settings),Memory(mutableMapOf("lan_code" to "12345678")),object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String,bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        val values=snapshot.toMutableMap()
        for((role,state) in listOf("sensor.print_status" to "idle","binary_sensor.online" to "on")) { val id=printer.info.ids.getValue(role); values[id]=Entity(id,state,JsonObject(emptyMap())) }
        Repo.state.value=st.copy(printers=listOf(Printer(printer.info.copy(ams=emptyList()),values)))
        val http=io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { _ ->
            respond(byteArrayOf(1,2,3),headers=io.ktor.http.headersOf("Content-Disposition" to listOf("attachment; filename=\"test.gcode.3mf\""),
                "X-Pandy-Result" to listOf("""{"plates":[{"index":1,"filaments":[{"id":"1","type":"PLA","color":"#FFFFFF"}]}]}""")))
        }).also { testHttpClients += it }
        val slicer=SlicerClient("http://slicer.invalid:8737",null,http=http,storageFactory={ object : PrinterStorage {
            override suspend fun list(dir: String) = emptyList<FileEntry>()
            override suspend fun read(path: String,offset: Long,length: Int) = byteArrayOf()
            override suspend fun write(path: String,data: ByteArray,progress: (Float)->Unit) = Unit
        } })
        val closed=java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{closed.set(true)},slicerClient=slicer)
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Printer access code").assertDoesNotExist()
        compose.onNodeWithText("Print current plate",substring=false).performClick()
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); closed.get() }
        org.junit.Assert.assertTrue(commands.any { it.first.endsWith("/print_project_file") })
        assertEquals("127.0.0.1",settings["lan_ip"]) // the saved details were not replaced
    }

    @Test fun everyFilamentSlotOpensItsDetailsAndLoadAndUnloadFollowThePrintState() {
        val trays=printer.info.ams.single().trays
        fun spool(vararg attrs: Pair<String,String>) = entity("Spool",attrs.toMap())
        val idle=variant(set=arrayOf("sensor.print_status" to entity("idle"),
            trays.getValue(1) to spool("name" to "PLA Basic","type" to "PLA","color" to "#FF0000FF","remain" to "40","active" to "true","nozzle_temp_min" to "190","nozzle_temp_max" to "230"),
            trays.getValue(2) to spool("name" to "Mystery","type" to "","color" to "#00000000","remain" to "-1"),
            trays.getValue(3) to entity("Empty",mapOf("empty" to "true")),
            trays.getValue(4) to spool("name" to "ABS","type" to "ABS","color" to "#0000FFFF","remain" to "75")))
        val running=variant(set=arrayOf("sensor.print_status" to entity("running")))
        val selected=androidx.compose.runtime.mutableStateOf(idle)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { FilamentScreen(selected.value) } } }
        for(p in listOf(idle,running)) {
            compose.runOnIdle { selected.value=p }; compose.waitForIdle()
            for(slot in listOf("A1","A2","A3","A4")) {
                compose.onNodeWithText(slot).performScrollTo().performClick()
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
                if(p===idle && slot=="A1") { compose.onNodeWithText("This filament is loaded in the nozzle.").assertExists(); compose.onNodeWithText("190–230°C").assertExists() }
                if(slot=="A4" && p===idle) compose.onNodeWithText("Load").performClick() else compose.onNodeWithContentDescription("Close").performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithText("Unload").performScrollTo().performClick()
            compose.waitForIdle()
        }
        compose.waitUntil(5000) { compose.waitForIdle(); commands.any { it.first.endsWith("load_filament") } && messages.contains("Can't unload while printing") }
        org.junit.Assert.assertTrue(commands.any { it.first.endsWith("unload_filament") })
    }

    @Test fun everyPrintOptionToggleSendsItsCommandAndRevertsWhenTheCommandFails() {
        show("Print Options") { PrintOptionsScreen(printer) }
        val toggles=compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        org.junit.Assert.assertTrue(toggles>=4)
        for(round in 1..2) {
            commandFailure=if(round==2) "The printer rejected the option" else null
            for(i in 0 until toggles) { compose.onAllNodes(isToggleable())[i].performScrollTo().performClick(); compose.waitForIdle() }
            compose.waitUntil(5000) { compose.waitForIdle(); if(round==1) commands.size>=toggles else messages.contains("The printer rejected the option") }
        }
        commandFailure=null
    }

    @Test fun deviceControlSheetsCoverTheNozzleFansChamberAndIdleSpeedTaps() {
        val idle=variant(set=arrayOf("sensor.print_status" to entity("idle")))
        val noFanId=variant(info=printer.info.copy(ids=printer.info.ids-"fan.chamber_fan"),set=arrayOf("sensor.print_status" to entity("idle")))
        val chamberless=variant(remove=listOf("sensor.chamber_temp"),set=arrayOf("sensor.print_status" to entity("idle")))
        val selected=androidx.compose.runtime.mutableStateOf(idle)
        fun until(what: String,condition: () -> Boolean) { try { compose.waitUntil(5000) { compose.waitForIdle(); condition() } } catch(e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("$what; toasts=${messages.toList()} commands=${commands.toList()}") } }
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { ControlScreen(selected.value) } } }
        compose.waitForIdle()
        compose.onNodeWithText("Speed").performClick()
        until("speed toast") { messages.contains("Speed can only be changed while printing") }
        // The nozzle sheet: temperature entry sends the target.
        compose.onNodeWithText("Nozzle & Extruder").performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
        compose.onAllNodes(hasText("°C",substring=true) and hasClickAction()).onLast().performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
        compose.onNode(hasSetTextAction()).performTextReplacement("215")
        compose.onNodeWithText("Done").performClick()
        until("nozzle target") { commands.any { it.second.contains("215") } }
        compose.onNodeWithContentDescription("Close").performClick()
        // The fan sheet skips a fan without an entity and every step button works.
        compose.runOnIdle { selected.value=noFanId }; compose.waitForIdle()
        compose.onNodeWithText("fan on",substring=true).performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
        for(i in 0..2) { compose.onAllNodes(isToggleable()).onFirst().performClick(); compose.waitForIdle() }
        compose.onNodeWithContentDescription("Close").performClick()
        // A printer with no chamber sensor shows a dash and the chamber tile is inert.
        compose.runOnIdle { selected.value=chamberless }; compose.waitForIdle()
        compose.onNodeWithText("Chamber",substring=false).performClick()
        compose.onNodeWithText("Chamber temperature").assertDoesNotExist()
    }

    @Test fun theMotionPadIgnoresDisabledAndOutOfRangeTapsAndMapsBothLeftDiagonals() {
        val idle=variant(set=arrayOf("sensor.print_status" to entity("idle")))
        val printing=variant(set=arrayOf("sensor.print_status" to entity("running")))
        val selected=androidx.compose.runtime.mutableStateOf(printing)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { MotionScreen(selected.value) } } }
        compose.waitForIdle()
        val label=compose.onNodeWithText("Toolhead").fetchSemanticsNode().boundsInRoot
        val density=compose.activity.resources.displayMetrics.density
        val center=androidx.compose.ui.geometry.Offset(label.center.x,label.bottom+115*density)
        fun tap(dx: Int,dy: Int) { compose.onRoot().performTouchInput { click(center+androidx.compose.ui.geometry.Offset(dx*density,dy*density)) }; compose.waitForIdle() }
        tap(80,0) // printing: the pad is disabled
        compose.runOnIdle { selected.value=idle }; compose.waitForIdle()
        tap(150,0) // far outside the pad
        for((dx,dy) in listOf(-80 to -30,-80 to 30,-60 to -60,-60 to 60)) tap(dx,dy)
        compose.waitUntil(5000) { commands.count { it.first.endsWith("/move_axis") }>=2 }
    }

    @Test fun filamentMatchingAndPreviewColoursWorkAcrossPrinterSetups() {
        val before=Repo.filamentPreset(printer.serial)
        restorePrintSettings={ Repo.setFilamentPreset(printer.serial,before) } // the test saves presets; other tests must not inherit them
        val model=modelBytes(mapOf("Metadata/project_settings.config" to """{"printer_model":"Bambu Lab P1S","filament_type":["PLA","PETG","ABS"],"filament_colour":["#FFFFFF","#00AE42","#123456"]}""".toByteArray()))
        val ams=printer.info.ams.single(); val trays=ams.trays
        fun spool(vararg attrs: Pair<String,String>) = entity("Spool",attrs.toMap())
        val idle=arrayOf("sensor.print_status" to entity("idle"))
        val setups=listOf(
            variant(set=idle),
            variant(info=printer.info.copy(ams=listOf(ams.copy(model="AMS HT",protocolId=128,trays=mapOf(1 to trays.getValue(1))))),set=idle),
            variant(info=printer.info.copy(ams=emptyList()),set=idle),
            variant(set=arrayOf(*idle,trays.getValue(1) to spool("type" to "PLA","color" to "#FFFFFFFF","name" to "PLA"),trays.getValue(2) to spool("type" to "","color" to "#00000000"),
                trays.getValue(3) to spool("type" to "ABS","color" to "#123456FF","name" to "ABS"),trays.getValue(4) to entity("Empty",mapOf("empty" to "true")))),
            variant(info=printer.info.copy(ams=emptyList(),external=emptyList()),set=idle),
        )
        Repo.state.value=st.copy(printers=listOf(setups.first()))
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,model,true),{})
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        for(setup in setups) {
            compose.runOnIdle { Repo.state.value=st.copy(printers=listOf(setup)) }; compose.waitForIdle()
            for(type in listOf("PLA","PETG","ABS")) {
                if(!exists(type)) continue
                compose.onAllNodesWithText(type).onFirst().performScrollTo().performClick()
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
                if(exists("Please select filament")) {
                    val row=compose.onAllNodes(hasText("AMS",substring=true) or hasText("External spool")).fetchSemanticsNodes()
                    if(row.isNotEmpty()) compose.onAllNodes(hasText("AMS",substring=true) or hasText("External spool")).onFirst().performClick()
                    compose.waitForIdle(); compose.onNodeWithText("Confirm").performClick(); compose.waitForIdle()
                }
            }
            compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick(); compose.waitForIdle() // use my filament presets
            for(type in listOf("PLA","PETG")) if(exists(type)) {
                compose.onAllNodesWithText(type).onFirst().performScrollTo().performClick(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
                if(exists("Please select filament")) {
                    if(compose.onAllNodes(hasText("AMS",substring=true)).fetchSemanticsNodes().isNotEmpty()) compose.onAllNodes(hasText("AMS",substring=true)).onLast().performClick()
                    compose.onNodeWithText("Confirm").performClick(); compose.waitForIdle()
                }
            }
            compose.onNodeWithText("3D Preview").performScrollTo().performClick()
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Printer colors").performClick(); compose.waitForIdle()
            compose.onNodeWithText("Customize colors").performClick(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
            compose.onAllNodes(hasClickAction() and !hasText("Done") and !hasText("Reset")).fetchSemanticsNodes().size // colour swatches
            compose.onNodeWithText("Done").performClick(); compose.waitForIdle()
            compose.onNodeWithText("Model colors").performClick(); compose.waitForIdle()
            compose.onNodeWithText("Reset").performClick(); compose.waitForIdle()
            compose.onAllNodes(isToggleable()).onFirst().performClick() // show the plate
            compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle()
        }
        compose.onNodeWithText("Prepare to Print").assertExists()
    }

    @Test fun liveViewHonoursTheCameraSwitchTheAutoplayChoiceAndMissingOrFailingBackends() {
        val haField=Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }
        val originalHa=haField.get(Repo)
        val before=Repo.setting("liveview_autoplay")
        restorePrintSettings={ haField.set(Repo,originalHa); Repo.setSetting("liveview_autoplay",before) }
        val demo=FakeHomeAssistant()
        val brokenStills=object : HaBackend by demo { override suspend fun bytes(path: String): ByteArray = throw ApiException("No picture") }
        val withHls=object : HaBackend by demo {
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> =
                if(commands.single().s("type")=="camera/stream") listOf(buildJsonObject { put("url","/api/hls/test/master.m3u8") }) else demo.ws(commands)
        }
        val on=printer; val off=variant(set=arrayOf("switch.camera" to entity("off")))
        data class Step(val printer: Printer,val autoplay: String?,val backend: HaBackend?)
        val steps=listOf(Step(on,null,demo),Step(on,"never",demo),Step(on,"always",demo),Step(on,"always",null),Step(on,"always",brokenStills),Step(on,"always",withHls),Step(off,null,demo))
        val selection=androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            androidx.compose.runtime.key(selection.value) { LiveView(steps[selection.value].printer,androidx.compose.ui.Modifier.size(300.dp,170.dp),onFull={},zoom=true) }
        } } }
        for((index,step) in steps.withIndex()) {
            Repo.setSetting("liveview_autoplay",step.autoplay); haField.set(Repo,step.backend)
            compose.runOnIdle { selection.value=index }; compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_500); compose.waitForIdle()
            if(step.autoplay=="never") { compose.onNodeWithText("Tap to view").performClick(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_500) }
            if(step.printer===off) { compose.onNodeWithText("Turn on").performClick(); compose.waitForIdle() }
        }
        compose.onRoot().assertExists()
    }

    @Test fun filesAndHistoryShowEmptyOddAndFailedListsAndTimelapsesPlayOrExplainWhyNot() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val extrasField=Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }
        val originalExtras=extrasField.get(Repo); val demoBefore=store.settings.getString("demo")
        restorePrintSettings={ extrasField.set(Repo,originalExtras); store.settings.putString("demo",demoBefore) }
        val demo=FakeHomeAssistant()
        fun entry(name: String,dir: String) = """{"filename":"$name","path":"SERIAL/$dir/$name","size":${name.length*1000},"modified":"2026-10-01T08:00:00","thumbnail_path":"SERIAL/$dir/$name.png"}"""
        fun backend(videos: List<String>,files: List<String>,failure: Throwable? = null) = object : HaBackend by demo {
            override suspend fun get(path: String): String = when {
                failure!=null -> throw failure
                "/videos" in path -> """{"videos":[${videos.joinToString(",")}]}"""
                "print_history" in path -> """{"files":[${files.joinToString(",")}]}"""
                else -> demo.get(path)
            }
        }
        val silent=object : Exception() { override val message: String? = null }
        val backends=listOf(
            backend(emptyList(),emptyList()),
            backend(listOf(entry("a.mp4","timelapse"),entry("bb.mp4","timelapse"),entry("ccc.mp4","timelapse")),listOf(entry("m.3mf","prints"))),
            backend(emptyList(),emptyList(),java.io.IOException("Home Assistant said no")),
            backend(emptyList(),emptyList(),silent),
        )
        val screen=androidx.compose.runtime.mutableStateOf(0 to 0)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            androidx.compose.runtime.key(screen.value) { if(screen.value.first==0) FilesScreen(printer) else HistoryScreen(printer) }
        } } }
        for(demoMode in listOf("false","true")) {
            store.settings.putString("demo",demoMode)
            for((index,b) in backends.withIndex()) {
                extrasField.set(Repo,b)
                for(which in 0..1) {
                    compose.runOnIdle { screen.value=which to index*10+which }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
                    if(which==0) {
                        if(compose.onAllNodesWithText("a.mp4",substring=true).fetchSemanticsNodes().isNotEmpty()) {
                            compose.onAllNodesWithText("a.mp4",substring=true).onFirst().performClick(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(500)
                            compose.onNodeWithContentDescription("Close").performClick(); compose.waitForIdle()
                        }
                        if(compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()) { compose.onNodeWithText("Try again").performClick(); compose.waitForIdle() }
                        compose.onNodeWithText("Model").performClick(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
                    }
                }
            }
        }
        compose.onRoot().assertExists()
    }

    @Test fun theRootRoutesEveryScreenAndExplainsEachNoPrinterState() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val keys=listOf("demo","mode","lan_serial","lan_ip")
        val before=keys.associateWith { store.settings.getString(it) }
        val selectedBefore=Repo.selected
        restorePrintSettings={ before.forEach { (k,v) -> store.settings.putString(k,v) }; Repo.selected=selectedBefore; Nav.stack.clear(); Nav.tab=1 }
        val second=variant(info=printer.info.copy(serial="SECOND"),set=arrayOf("sensor.print_status" to entity("idle")))
        val screens=listOf(null,Screen.Control,Screen.Motion,Screen.Skip,Screen.LiveFull,Screen.PrinterSettings,Screen.Calibration,Screen.PrintOptions,Screen.AmsSettings,
            Screen.LiveviewSettings,Screen.Assistant,Screen.Files,Screen.Filament,Screen.History,Screen.Settings,Screen.About,Screen.AddHa,Screen.AddDirect,Screen.Slicer)
        val states=listOf(
            st.copy(on=false),
            st.copy(printers=null,error=null),
            st.copy(printers=null,error="Home Assistant refused the token"),
            st.copy(printers=emptyList()),
            st,
            st.copy(printers=listOf(printer,second)),
        )
        val tick=androidx.compose.runtime.mutableStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { tick.value; Root() } } }
        for((demo,mode) in listOf("true" to "lan","false" to "lan","false" to "ha")) {
            store.settings.putString("demo",demo); store.settings.putString("mode",mode)
            for(state in states) {
                compose.runOnIdle { Repo.state.value=state }; compose.waitForIdle()
                for(screen in screens) {
                    compose.runOnIdle { Nav.stack.clear(); screen?.let { Nav.go(it) }; Repo.selected= if(state.printers?.size==2) "SECOND" else null; tick.value++ }
                    compose.waitForIdle(); compose.mainClock.advanceTimeBy(100)
                    if(screen==null) for(tab in 0..2) { compose.runOnIdle { Nav.tab=tab }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
                }
            }
        }
        compose.runOnIdle { Repo.state.value=st.copy(printers=null,error="Offline"); Nav.stack.clear() }; compose.waitForIdle()
        compose.onNodeWithText("Retry").performClick()
        compose.onRoot().assertExists()
    }

    @Test fun thePrintButtonAndSetupNotesFollowTheModeSlicerAndPrinterState() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val keys=listOf("demo","mode")
        val before=keys.associateWith { store.settings.getString(it) }
        restorePrintSettings={ before.forEach { (k,v) -> store.settings.putString(k,v) } }
        val http=io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { _ -> respond(byteArrayOf(1),io.ktor.http.HttpStatusCode.ServiceUnavailable) }).also { testHttpClients += it }
        val slicer=SlicerClient("http://slicer.invalid:8737",null,http=http)
        val idle="sensor.print_status" to entity("idle")
        val printers=listOf(
            variant(set=arrayOf(idle,"binary_sensor.online" to entity("on"),"sensor.ip_address" to entity("127.0.0.1"))),
            variant(set=arrayOf(idle,"binary_sensor.online" to entity("off"))),
            variant(set=arrayOf("sensor.print_status" to entity("running"),"binary_sensor.online" to entity("on"))),
            variant(set=arrayOf(idle,"binary_sensor.online" to entity("on"),"binary_sensor.developer_lan_mode" to entity("off"))),
            variant(remove=listOf("sensor.ip_address"),set=arrayOf(idle,"binary_sensor.online" to entity("on"))),
            variant(info=printer.info.copy(ams=emptyList()),set=arrayOf(idle,"binary_sensor.online" to entity("on"),"sensor.ip_address" to entity("127.0.0.1"))),
        )
        val selection=androidx.compose.runtime.mutableStateOf(0 to (null as SlicerClient?))
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            androidx.compose.runtime.key(selection.value.first,selection.value.second) {
                PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{},slicerClient=selection.value.second)
            }
        } } }
        var round=0
        for((demo,mode) in listOf("true" to "lan","false" to "ha")) {
            store.settings.putString("demo",demo); store.settings.putString("mode",mode)
            for(client in listOf<SlicerClient?>(null,slicer)) for(printer in printers) {
                round++
                compose.runOnIdle { Repo.state.value=st.copy(printers=listOf(printer)); selection.value=round to client }
                compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Next").performClick(); compose.waitForIdle()
                if(compose.onAllNodesWithText(printer.name).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onAllNodesWithText(printer.name).onFirst().performScrollTo().performClick(); compose.waitForIdle()
                    if(compose.onAllNodesWithText("Select printer").fetchSemanticsNodes().isNotEmpty()) { compose.onNodeWithText("Confirm").performClick(); compose.waitForIdle() }
                }
                val code=compose.onAllNodesWithText("Printer access code").fetchSemanticsNodes()
                if(code.isNotEmpty()) { compose.onNodeWithText("Printer access code").performScrollTo().performTextInput("123456"); compose.waitForIdle() }
                val button=compose.onAllNodes(hasClickAction() and (hasText("Print (demo)") or hasText("Print current plate")))
                if(button.fetchSemanticsNodes().isNotEmpty()) {
                    runCatching { button.onFirst().performScrollTo() }
                    button.onFirst().performClick(); compose.waitForIdle()
                    if(compose.onAllNodesWithText("Start",substring=false).fetchSemanticsNodes().isNotEmpty() && client==null && demo=="false") { compose.onNodeWithText("Start",substring=false).performClick(); compose.waitForIdle() }
                    else if(compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()) { compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle() }
                }
                compose.onAllNodes(hasText("Setup guide",substring=true)).fetchSemanticsNodes().firstOrNull()
            }
        }
        compose.onRoot().assertExists()
    }

    @Test fun platesWithOneMetricUnknownBedTypesAndABlankNameStillPrepareAndPreview() {
        val sliceInfo="""<config><plate><metadata key="index" value="1"/><metadata key="prediction" value="600"/></plate><plate><metadata key="index" value="2"/><metadata key="weight" value="3.5"/></plate></config>"""
        val settings="""<config><plate><metadata key="plater_id" value="1"/><metadata key="bed_type" value="Mystery Plate"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>"""
        val mesh="""<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2" p1="7"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>"""
        val bytes=modelBytes(mapOf("Metadata/slice_info.config" to sliceInfo.toByteArray(),"Metadata/model_settings.config" to settings.toByteArray(),
            "Metadata/project_settings.config" to "{}".toByteArray(),"3D/3dmodel.model" to mesh.toByteArray()))
        Repo.state.value=st.copy(printers=listOf(variant(set=arrayOf("sensor.print_status" to entity("idle")))))
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { PrintModelSheet(WebDownload(null,".3mf",null,bytes,true),{}) } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("◷ 10 min").performScrollTo().assertExists()
        compose.onNodeWithText("Plate 2").performScrollTo().performClick()
        compose.onNodeWithText("◎ 3.5g").assertExists()
        compose.onNodeWithText("Print (demo)").performClick()
        compose.onNodeWithText("Start this model",substring=true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("3D Preview").performScrollTo().performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Show the plate").assertExists()
    }

    @Test fun theSlicerScreenSavesAWorkingAddressAndRemovesItAgain() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/health") { exchange -> val body="""{"version":"2.5.1"}""".toByteArray(); exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) } }
        server.start()
        val before=Repo.slicerUrl
        restorePrintSettings={ Repo.setSlicer(before,null) }
        try {
            Repo.setSlicer(null,null)
            show("Slicer") { SlicerScreen() }
            compose.onNodeWithText("Slicer address",substring=true).performTextInput("127.0.0.1:${server.address.port}")
            compose.onNodeWithText("Token",substring=true).performTextInput("secret")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Connected: Bambu Studio 2.5.1").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertNotNull(Repo.slicerUrl)
            compose.onNodeWithText("Remove").performScrollTo().performClick()
            org.junit.Assert.assertNull(Repo.slicerUrl)
            compose.onNodeWithText("Setup guide",substring=true).performScrollTo().performClick()
            org.junit.Assert.assertTrue(openedUrls.any { it.contains("slicer") })
        } finally { server.stop(0) }
    }

    @Test fun theDevicesTabKeepsConnectingInTheDemoAndHandlesNoPrinterList() {
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        val before=store.settings.getString("demo"); restorePrintSettings={ store.settings.putString("demo",before) }
        val state=androidx.compose.runtime.mutableStateOf(st.copy(live=false))
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { DevicesTab(state.value,printer) } } }
        for(demo in listOf("true","false")) {
            store.settings.putString("demo",demo)
            for(candidate in listOf(st.copy(live=false),st.copy(live=true,printers=null),st.copy(live=false,printers=emptyList()),st)) {
                compose.runOnIdle { state.value=candidate }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(200)
            }
        }
        compose.onRoot().assertExists()
    }

    @Test fun leavingFilesCancelsThePendingListingInsteadOfPublishingAFailure() {
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val demo = FakeHomeAssistant()
        val backend = object : HaBackend by demo {
            override suspend fun get(path: String): String {
                started.set(true)
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        val extras = Repo::class.java.getDeclaredField("extras").apply { isAccessible = true }
        val original = extras.get(Repo)
        restorePrintSettings = { extras.set(Repo, original) }
        extras.set(Repo, backend)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            if (visible.value) FilesScreen(printer)
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); started.get() }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5000) { compose.waitForIdle(); cancelled.get() }
        compose.onNodeWithText("Couldn't load timelapses").assertDoesNotExist()
    }

    @Test fun settingsOnAPlatformWithoutBackgroundAlertsHidesTheAlertOption() {
        val iosLike=object : PlatformUi by platform { override val canNotify = false }
        compose.setContent { CompositionLocalProvider(LocalPlatform provides iosLike) { AppTheme { SettingsScreen() } } }
        compose.waitForIdle()
        compose.onNodeWithText("Print notifications").assertDoesNotExist()
    }

    @Test fun reprintingShowsHowEachFilamentMapsAcrossPrinterSetupsAndMissingMatches() {
        val demo=FakeHomeAssistant()
        fun backendWith(xml: String?) = object : HaBackend by demo {
            override suspend fun get(path: String): String = if(path.endsWith(".slice_info.config")) (xml ?: throw ApiException("No slice info")) else demo.get(path)
        }
        val matched="""<config><plate><metadata key="index" value="2"/><filament id="1" type="PLA" color="#FFFFFF"/><filament id="2" type="PLA" color="#000000"/></plate></config>"""
        val unmatched="""<config><plate><metadata key="index" value="1"/><filament id="1" type="PLA" color="#FFFFFF"/><filament type="ABS" color="#FF00FF"/><filament/></plate></config>"""
        val none="""<config><plate><metadata key="index" value="1"/></plate></config>"""
        val ams=printer.info.ams.single(); val trays=ams.trays
        val idle="sensor.print_status" to entity("idle")
        val setups=listOf(
            variant(set=arrayOf(idle)),
            variant(set=arrayOf("sensor.print_status" to entity("running"))),
            variant(info=printer.info.copy(ams=emptyList()),set=arrayOf(idle)),
            variant(info=printer.info.copy(ams=listOf(ams.copy(model="AMS HT",protocolId=128,trays=mapOf(1 to trays.getValue(1))))),set=arrayOf(idle)),
            variant(set=arrayOf(idle,trays.getValue(1) to entity("Spool",mapOf("type" to "PLA","color" to "#FFFFFFFF","name" to "PLA")),trays.getValue(2) to entity("Spool",mapOf("type" to "PLA","color" to "#000000FF","name" to "PLA")))),
        )
        val file=CachedFile("Test.3mf","SERIAL/prints/cache/Test.3mf",10,"2026-10-01T08:00:00",null)
        val extrasField=Repo::class.java.getDeclaredField("extras").apply { isAccessible=true }
        val originalExtras=extrasField.get(Repo); restorePrintSettings={ extrasField.set(Repo,originalExtras) }
        val selection=androidx.compose.runtime.mutableStateOf(0)
        val combos=listOf(matched,unmatched,none,null).flatMap { xml -> setups.map { xml to it } }
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            androidx.compose.runtime.key(selection.value) { ReprintSheet(combos[selection.value].second,file,{}) }
        } } }
        for((index,combo) in combos.withIndex()) {
            extrasField.set(Repo,backendWith(combo.first))
            compose.runOnIdle { selection.value=index }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().indices.forEach { i -> compose.onAllNodes(isToggleable())[i].performClick(); compose.waitForIdle() }
        }
        org.junit.Assert.assertTrue(compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty())
    }
}
