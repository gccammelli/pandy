package io.github.gccammelli.pandy

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryStartupTest {
    private class Values(private val values: MutableMap<String,String>) : KeyValue, SecretStore {
        override fun getString(key: String)=values[key]
        override fun putString(key: String,value: String?) { if(value==null) values.remove(key) else values[key]=value }
        override fun get(key: String)=getString(key)
        override fun set(key: String,value: String?)=putString(key,value)
        override fun clear()=values.clear()
    }
    private val storeField=Repo::class.java.getDeclaredField("store").apply { isAccessible=true }
    private fun boot(settings: Map<String,String>, secrets: Map<String,String> = emptyMap()) {
        if(storeField.get(Repo)!=null) Repo.logout()
        storeField.set(Repo,null)
        Repo.init(PlatformStorage(Values(settings.toMutableMap()),Values(secrets.toMutableMap()),object : AppFiles {
            override fun read(name: String): ByteArray?=null
            override fun write(name: String,bytes: ByteArray)=Unit
            override fun delete(name: String)=Unit
        }))
    }

    @Test fun missingCredentialsNeverStartAnUnauthenticatedConnection() {
        val lan=mapOf("lan_serial" to "TEST","lan_ip" to "192.0.2.1")
        try {
            for ((settings,secrets) in listOf(
                emptyMap<String,String>() to emptyMap<String,String>(),
                mapOf("ha_url" to "https://home.example") to emptyMap(),
                emptyMap<String,String>() to mapOf("ha_token" to "token"),
                lan to emptyMap(),
                mapOf("lan_serial" to "TEST") to mapOf("lan_code" to "code")
            )) {
                boot(settings,secrets)
                assertFalse(Repo.connected); assertNull(Repo.ha); assertNull(Repo.extras); assertNull(Repo.lan)
                assertEquals(if("ha_url" in settings) "ha" else "lan",Repo.mode)
            }
        } finally { Repo.logout() }
    }

    @Test fun savedModesSelectTheAppropriateBackendWithoutContactingAPrinter() {
        val lan=mapOf("lan_serial" to "TEST","lan_ip" to "192.0.2.1","lan_model" to "P1S","lan_name" to "Workshop")
        val remote=mapOf("ha_url" to "https://home.example")
        val tokens=mapOf("ha_token" to "token","lan_code" to "code")
        try {
            boot(lan,tokens)
            assertTrue(Repo.ha is DirectPrinter); assertSame(Repo.ha,Repo.extras)
            assertTrue(Repo.direct); assertEquals("Workshop",Repo.lan!!.name)
            assertEquals("P1S",Repo.lan!!.model)
            boot(lan+remote+mapOf("mode" to "lan"),tokens)
            assertTrue(Repo.ha is PreferPrimaryBackend); assertTrue(Repo.extras is HaHttp)
            assertTrue(Repo.state.value.on); assertEquals(mapOf("Authorization" to "Bearer token"),Repo.authHeaders())
            boot(lan+remote+mapOf("mode" to "ha"),tokens)
            assertTrue(Repo.ha is HaHttp); assertSame(Repo.ha,Repo.extras); assertFalse(Repo.direct)
            boot(remote+mapOf("mode" to "lan"),tokens)
            assertTrue(Repo.ha is HaHttp); assertSame(Repo.ha,Repo.extras)
            boot(lan-mapOf("lan_model" to "P1S","lan_name" to "Workshop").keys,tokens)
            assertEquals("",Repo.lan!!.name); assertEquals(modelName(null,"TEST"),Repo.lan!!.model)
            boot(mapOf("demo" to "true"),emptyMap())
            assertTrue(Repo.ha is FakeHomeAssistant); assertSame(Repo.ha,Repo.extras); assertFalse(Repo.direct)
        } finally { Repo.logout() }
    }

    @Test fun backgroundWatchingRequiresPermissionConnectionAndARealAccount() {
        try {
            boot(emptyMap())
            Repo.notifier=null; assertFalse(Repo.watching)
            Repo.notifier=object : Notifier { override fun show(key: String,title: String,text: String)=Unit }
            assertFalse(Repo.watching)
            Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,FakeHomeAssistant())
            assertTrue(Repo.watching)
            Repo.setNotify(false); assertFalse(Repo.watching)
            Repo.setNotify(true); Repo.setSetting("demo","true"); assertFalse(Repo.watching)
        } finally { Repo.notifier=null; Repo.logout() }
    }

    @Test fun livePrintChangesNotifyOnlyWhenBackgroundAlertsAreEnabled() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val changes=Channel<String>(Channel.UNLIMITED)
        val applied=Channel<Unit>(Channel.UNLIMITED)
        val notices=MutableStateFlow<List<Triple<String,String,String>>>(emptyList())
        try {
            boot(emptyMap())
            val demo=FakeHomeAssistant()
            val info=demo.printers().single()
            val backend=object : HaBackend by demo {
                override suspend fun stream(subscribe: JsonObject,onEvent: (JsonObject)->Unit) {
                    for(status in changes) {
                        onEvent(buildJsonObject { putJsonObject("a") {
                            putJsonObject(info.ids.getValue("sensor.print_status")) { put("s",status) }
                        } })
                        applied.send(Unit)
                    }
                }
            }
            Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,backend)
            Repo.notifier=object : Notifier {
                override fun show(key: String,title: String,text: String) { notices.value=notices.value+Triple(key,title,text) }
            }
            suspend fun emit(status: String) {
                changes.send(status)
                withContext(Dispatchers.Default) { withTimeout(5000) { applied.receive() } }
            }
            Repo.start()
            for ((visible,enabled,demoMode) in listOf(Triple(true,true,false),Triple(false,false,false),Triple(false,true,true),Triple(false,true,false))) {
                Repo.visible=visible; Repo.setNotify(enabled); Repo.setSetting("demo",demoMode.toString())
                emit("running"); emit("finish")
            }
            assertEquals(1,notices.value.size)
            assertEquals(info.serial,notices.value.single().first)
            assertEquals("Print finished",notices.value.single().second)
            assertTrue(notices.value.single().third.contains(info.name))
        } finally {
            Repo.stop(); changes.close(); applied.close(); Repo.notifier=null; Repo.visible=false
            Repo.logout(); Dispatchers.resetMain()
        }
    }
}
