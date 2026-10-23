package io.github.gccammelli.pandy

import androidx.compose.foundation.layout.fillMaxSize
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
        override fun toast(message: String) { messages += message }
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) {
            savedFiles += Triple(name,mime,bytes)
            done(null)
        }
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override val canNotify = true
        override fun requestNotifications() { notificationRequests++ }
        override fun watchInBackground(on: Boolean) { backgroundWatching += on }
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) { savedImages += jpeg; done(null) }
    }

    private val commands = java.util.concurrent.ConcurrentLinkedQueue<Pair<String,String>>()
    private val savedImages = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val savedFiles = mutableListOf<Triple<String,String,ByteArray>>()
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
        Repo.stop(); debugDownload.value=null
        testHttpClients.forEach { it.close() }
        restorePrintSettings?.invoke()
    }

    @Before fun home() {
        Repo.stop()
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
            val demo = FakeHomeAssistant()
            val backend = object : HaBackend by demo {
                override suspend fun post(path: String,body: String): String {
                    commands += path to body
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
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
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

    private fun modelBytes(): ByteArray = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                put("Metadata/project_settings.config", """{"printer_model":"Bambu Lab X1 Carbon","filament_type":["PLA","PETG"],"filament_colour":["#FFFFFF","#00AE42"]}""")
                put("Metadata/model_settings.config", """<config><plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Body"/></plate><plate><metadata key="plater_id" value="2"/></plate></config>""")
                put("3D/3dmodel.model", """<model><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices><triangles><triangle v1="0" v2="1" v3="2" p1="1"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
            }
        }.toByteArray()

    private fun prepareRealPrint(filaments: String, failSlice: Boolean = false): Pair<java.util.concurrent.ConcurrentLinkedQueue<String>, java.util.concurrent.atomic.AtomicBoolean> {
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
        Repo.state.value = st.copy(printers=listOf(Printer(printer.info.copy(ams=emptyList()),values)))
        val uploads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            org.junit.Assert.assertNull(request.headers["X-Printer-Code"])
            if(failSlice) respond("""{"error":"Slicing failed in test"}""",io.ktor.http.HttpStatusCode.ServiceUnavailable)
            else respond(byteArrayOf(1,2,3),headers=io.ktor.http.headersOf(
                "Content-Disposition" to listOf("attachment; filename=\"test.gcode.3mf\""),
                "X-Pandy-Result" to listOf("""{"plates":[{"index":1,"filaments":$filaments}]}""")))
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
            PrintModelSheet(WebDownload(null,"Test model.3mf",null,modelBytes(),true),{closed.set(true)},slicerClient=slicer)
        } } }
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Select a Print Profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Printer access code").performScrollTo().performTextInput("12345678")
        compose.onNodeWithText("Print current plate",substring=false).performClick()
        compose.onNodeWithText("Start print?").assertExists()
        return uploads to closed
    }

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
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            org.junit.Assert.assertTrue(bitmap.getPixel(view.width/2,view.height/2) != 0)
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

}

