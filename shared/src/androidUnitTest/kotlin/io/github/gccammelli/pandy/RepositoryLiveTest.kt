package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepositoryLiveTest {
    private fun init(backend: HaBackend) {
        Repo.stop()
        Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.set(Repo,null)
        val values = mutableMapOf<String,String>()
        val tokens = mutableMapOf<String,String>()
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String,value: String?) { if(value==null) values.remove(key) else values[key]=value }
            override fun clear() = values.clear()
        },object : SecretStore {
            override fun get(key: String)=tokens[key]
            override fun set(key: String,value: String?) { if(value==null) tokens.remove(key) else tokens[key]=value }
        },object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String,bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        Repo.stop(); Repo.state.value=UiState(on=true)
        for (field in listOf("ha","extras")) Repo::class.java.getDeclaredField(field).apply { isAccessible=true }.set(Repo,backend)
    }

    @Test fun liveDemoUpdatesStateAndStopsWithoutARealPrinter() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val demo = FakeHomeAssistant()
            val posted = mutableListOf<Pair<String,JsonObject>>()
            init(object : HaBackend by demo {
                override suspend fun post(path: String,body: String): String {
                    posted += path to json.parseToJsonElement(body).jsonObject
                    return demo.post(path,body)
                }
            })
            Repo.start(); Repo.start()
            val live = withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.live && !it.printers.isNullOrEmpty() } } }
            assertEquals("00M000000000000",live.printers!!.single().serial)
            assertNull(live.error)
            val printer = live.printers!!.single()
            printer.calibrate(3,{}).join()
            printer.setXcam("spaghetti_detector",true,true,{}).join()
            printer.setPrintOption("auto_recovery",true,{}).join()
            printer.setAmsSettings(0,true,false,true,{}).join()
            assertEquals("3",posted.first().second.s("option"))
            assertTrue(posted.any { it.second.s("print_halt")=="true" })
            assertTrue(posted.any { it.second.s("startup_read_option")=="true" })
            printer.light(true,{}).join(); printer.light(false,{}).join()
            printer.setSpeed("Silent",{}).join()
            printer.setTarget("nozzle",180,{}).join()
            printer.setFan("fan.test",0,{}).join(); printer.setFan("fan.test",75,{}).join()
            printer.button("pause_printing",{}).join()
            printer.move("X",10,{}).join(); printer.extrude(false,{}).join()
            printer.skip(listOf(2,4),{}).join()
            printer.unload({}).join()
            printer.load(Spool("sensor.test_tray",1,"Test spool","PLA",0,emptyList(),100,false,false,190,220,"test"),{}).join()
            val withCamera = Printer(printer.info.copy(ids=printer.info.ids + ("switch.camera" to "switch.test_camera")), emptyMap())
            withCamera.toggleCamera(true,{}).join(); withCamera.toggleCamera(false,{}).join()
            assertTrue(posted.any { it.first.endsWith("/set_percentage") && it.second.s("percentage")=="75" })
            assertTrue(posted.any { it.first.endsWith("/move_axis") && it.second.s("distance")=="10" })
            assertTrue(posted.any { it.first.endsWith("/skip_objects") && it.second.s("objects")=="2,4" })
            var missingControl: String? = null
            Printer(printer.info.copy(ids=emptyMap(),ams=emptyList(),externalDevice=null),emptyMap())
                .setTarget("bed",60,{ missingControl=it }).join()
            assertEquals("Home Assistant can't set the bed temperature",missingControl)
            demo.startPrint(printer,"cache/test.3mf",2,listOf(0),true,true,true,"textured_plate")
            assertEquals("textured_plate", buildPlateCode("Textured PEI Plate"))
            var error: String? = null
            Repo.act({ error=it }) { throw ApiException("Refused action") }.join()
            assertEquals("Refused action",error)
            Repo.act { post("/api/services/light/turn_on","{}") }.join()
            Repo.restart()
            assertTrue(Repo.connected)
        } finally { Repo.stop(); Dispatchers.resetMain() }
    }

    @Test fun setpointTargetsAreSentInUnitsTheirEntitiesAdvertise() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val demo = FakeHomeAssistant()
            val posted = mutableListOf<Pair<String,JsonObject>>()
            init(object : HaBackend by demo {
                override suspend fun post(path: String,body: String): String {
                    posted += path to json.parseToJsonElement(body).jsonObject
                    return demo.post(path,body)
                }
            })
            Repo.start()
            val live = withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.live && !it.printers.isNullOrEmpty() } } }
            val printer = live.printers!!.single()
            printer.setTarget("nozzle",250,{ fail(it) }).join()
            assertTrue(posted.any { it.first.endsWith("/number/set_value") && it.second.s("value")=="250.0" }) // Celsius install: sent as typed
            val key = "number.target_nozzle_temperature"
            val hot = Entity("number.hot","482",json.parseToJsonElement("""{"unit_of_measurement":"°F"}""").jsonObject)
            Printer(printer.info.copy(ids=printer.info.ids+(key to hot.id)),mapOf(hot.id to hot)).setTarget("nozzle",250,{ fail(it) }).join()
            assertEquals("482.0",posted.last().second.s("value")) // Fahrenheit install: the same 250 °C goes out as 482 °F
        } finally { Repo.stop(); Dispatchers.resetMain() }
    }

    @Test fun authenticationAndConnectionFailuresKeepStateExplicit() = runTest {
        for (failure in listOf(AuthException("Expired token"),ApiException("Printer unavailable"))) {
            val demo = FakeHomeAssistant()
            init(object : HaBackend by demo {
                override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = throw failure
            })
            try {
                Repo.start()
                val state = withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.error != null } } }
                assertFalse(state.live); assertEquals(failure.message,state.error)
            } finally { Repo.stop() }
        }
    }
    @Test fun directPrinterSetupValidatesBeforeSavingAndRefusesBadCredentials() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        init(FakeHomeAssistant())
        val store=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }.get(Repo) as PlatformStorage
        store.settings.putString("demo","false")
        val config=LanConfig("192.0.2.1","SETUP-TEST","test-code","P1S","Test printer")
        fun printer(fail: Boolean) = DirectPrinter(config,true,{ object : LanLink {
            override suspend fun run(onConnected: () -> Unit,onMessage: (String)->Unit) {
                if(fail) throw ApiException("Access code rejected")
                onConnected(); onMessage("""{"print":{"command":"push_status","gcode_state":"IDLE"}}""")
                awaitCancellation()
            }
            override fun publish(payload: String)=Unit
            override fun close()=Unit
        } })
        try {
            // A malformed address must fail through the production factory without saving setup.
            assertFailsWith<ApiException> { Repo.connectPrinter(config.copy(ip="127.0.0.1:invalid")) }
            assertNull(store.settings.getString("lan_serial"))
            assertFailsWith<ApiException> { Repo.connectPrinter(config) { printer(true) } }
            assertNull(store.settings.getString("lan_serial"))
            Repo.connectPrinter(config) { printer(false) }
            assertEquals("lan",store.settings.getString("mode"))
            assertEquals(config.serial,store.settings.getString("lan_serial"))
            assertEquals(config.ip,store.settings.getString("lan_ip"))
            assertTrue(Repo.connected)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun addingHomeAssistantToADirectConnectionKeepsTheSimulatedPrinterInControl() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val demo=FakeHomeAssistant()
        init(demo)
        val config=LanConfig("192.0.2.1","COMBINED-TEST","test-code","P1S","Workshop")
        val direct=DirectPrinter(config,true,{ object : LanLink {
            override suspend fun run(onConnected: ()->Unit,onMessage: (String)->Unit) {
                onConnected(); onMessage("""{"print":{"command":"push_status","gcode_state":"IDLE"}}""")
                awaitCancellation()
            }
            override fun publish(payload: String)=Unit
            override fun close()=Unit
        } })
        try {
            Repo.connectPrinter(config) { direct }
            assertTrue(Repo.direct); assertEquals(config,Repo.lan)
            val types=listOf("config/device_registry/list","config/entity_registry/list")
            val registry=types.zip(demo.ws(types.map { buildJsonObject { put("type",it) } }).map { it!! }).toMap()
            HaSocket(registry=registry).use { peer ->
                Repo.connect(" ${peer.url}/ "," pasted\n token ",asPrinter=false)
                assertTrue(Repo.ha is PreferPrimaryBackend); assertTrue((Repo.ha as PreferPrimaryBackend).usingPrimary)
                assertTrue(Repo.extras is HaHttp); assertEquals(peer.url,Repo.haUrl)
                assertTrue(Repo.direct); assertEquals(config,Repo.lan)
                assertEquals(mapOf("Authorization" to "Bearer pastedtoken"),Repo.authHeaders())
                assertNotNull(Repo.slicer)
                val live=withContext(Dispatchers.Default) { withTimeout(5000) { Repo.state.first { it.live && !it.printers.isNullOrEmpty() } } }
                assertEquals(config.serial,live.printers!!.single().serial)
                Repo.removeDirectPrinter()
                assertFalse(Repo.connected); assertNull(Repo.lan); assertTrue(Repo.extras is HaHttp)
                assertEquals(peer.url,Repo.haUrl); assertNotNull(Repo.slicer)
                Repo.removeHa()
                assertNull(Repo.haUrl); assertNull(Repo.ha); assertNull(Repo.extras); assertNull(Repo.slicer)
                assertFalse(Repo.state.value.on)
            }
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

}
