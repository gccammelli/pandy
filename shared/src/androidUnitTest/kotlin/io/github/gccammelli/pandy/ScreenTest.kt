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
import androidx.compose.ui.unit.sp
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
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) { savedImages += jpeg; done(saveImageError) }
    }

    private val commands = java.util.concurrent.ConcurrentLinkedQueue<Pair<String,String>>()
    private val savedImages = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val savedFiles = mutableListOf<Triple<String,String,ByteArray>>()
    private var saveImageError: Throwable?=null
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
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                if (view.width > 0 && view.height > 0) view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
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
    @Test fun devicesShowPrinter() = show(printer.name) { DevicesTab(st, printer) }
    @Test fun controlsShowTemperatures() = show("Device Control") { ControlScreen(printer) }
    @Test fun motionShowsAxes() = show("XYZ") { MotionScreen(printer) }
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

    private fun prepareRealPrint(filaments: String?, failSlice: Boolean = false, keepAms: Boolean = false): Pair<java.util.concurrent.ConcurrentLinkedQueue<String>, java.util.concurrent.atomic.AtomicBoolean> {
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
        Repo.state.value = st.copy(printers=listOf(Printer(if(keepAms) printer.info else printer.info.copy(ams=emptyList()),values)))
        val uploads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            org.junit.Assert.assertNull(request.headers["X-Printer-Code"])
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
        compose.onNodeWithText("No filament metadata was provided by the model.").performScrollTo().assertExists()
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
        val keys=listOf("demo","mode","lan_serial","lan_ip","lan_model","ha_url","slicer_url")
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

}

