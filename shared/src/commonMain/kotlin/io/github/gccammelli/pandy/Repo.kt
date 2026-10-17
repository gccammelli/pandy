package io.github.gccammelli.pandy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** [live]: the websocket stream is up; false while (re)connecting, with the last [error]. */
data class UiState(val on: Boolean = false, val printers: List<Printer>? = null, val live: Boolean = false, val error: String? = null)

object Repo {
    private lateinit var store: PlatformStorage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var ha: HaBackend? = null
        private set
    val state = MutableStateFlow(UiState())
    private var liveJob: Job? = null

    val connected get() = ha != null
    private var token: String? = null
    /** Home Assistant's token as an HTTP header, for players and downloads that fetch from it directly. */
    fun authHeaders(): Map<String, String> = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
    val haUrl get() = store.settings.getString("ha_url")
    val demo get() = store.settings.getString("demo") == "true"

    fun init(s: PlatformStorage) {
        if (::store.isInitialized) return
        store = s
        io.github.gccammelli.pandy.ui.ThemeMode.mode = s.settings.getString("theme") ?: "system"
        val url = s.settings.getString("ha_url")
        token = s.secrets.get("ha_token")
        val t = token
        if (demo) ha = FakeHomeAssistant()
        else if (url != null && t != null) ha = HaHttp(url, t)
        if (ha != null) state.value = UiState(on = true)
    }

    /** Checks the address and token by finding the printers once; saves them only if that works. */
    suspend fun connect(url: String, token: String) {
        val t = token.filterNot { it.isWhitespace() }
        val b = HaHttp(url, t)
        val found = withContext(Dispatchers.Default) { b.printers() }
        if (found.isEmpty()) throw ApiException("Connected, but Home Assistant has no Bambu Lab printers. Add the Bambu Lab integration there first.")
        store.settings.putString("ha_url", b.base)
        store.secrets.set("ha_token", t)
        this.token = t
        ha = b
        state.value = UiState(on = true)
        start()
    }

    fun startDemo() {
        store.settings.putString("demo", "true")
        ha = FakeHomeAssistant()
        state.value = UiState(on = true)
        start()
    }

    fun logout() {
        stop()
        store.secrets.set("ha_token", null)
        token = null
        store.settings.clear()
        store.settings.putString("theme", io.github.gccammelli.pandy.ui.ThemeMode.mode)
        ha = null
        state.value = UiState()
    }

    /** The printer shown on Devices (Handy shows one at a time and switches from the name). */
    var selected: String?
        get() = store.settings.getString("printer")
        set(v) = store.settings.putString("printer", v)

    fun setTheme(mode: String) { io.github.gccammelli.pandy.ui.ThemeMode.mode = mode; store.settings.putString("theme", mode) }

    fun setting(key: String) = store.settings.getString(key)
    fun setSetting(key: String, value: String?) = store.settings.putString(key, value)

    // ---------- Live updates ----------

    /**
     * Keeps a websocket open to Home Assistant and applies every state change of the printers' entities. If it drops
     * (Wi-Fi to mobile data, Home Assistant restarting), it reconnects after 1, 2, 4… up to 30 seconds. The app calls
     * [start] when it comes to the foreground and [stop] when it leaves.
     */
    fun start() {
        if (liveJob?.isActive == true) return
        val b = ha ?: return
        liveJob = scope.launch(Dispatchers.Default) {
            var wait = 1_000L
            while (isActive) {
                try {
                    val infos = b.printers()
                    val states = mutableMapOf<String, Entity>()
                    val ids = infos.flatMap { Printer(it, emptyMap()).entityIds }
                    b.stream(buildJsonObject {
                        put("type", "subscribe_entities")
                        put("entity_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                    }) { ev ->
                        applyEntityEvent(states, ev)
                        val snapshot = states.toMap()
                        state.update { it.copy(printers = infos.map { i -> Printer(i, snapshot) }, live = true, error = null) }
                        wait = 1_000L
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: AuthException) {
                    state.update { it.copy(live = false, error = e.message) }
                    return@launch // a new token is needed; retrying won't help
                } catch (e: Exception) {
                    state.update { it.copy(live = false, error = e.message ?: e.toString()) }
                }
                state.update { it.copy(live = false) }
                delay(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() { liveJob?.cancel(); liveJob = null }

    fun restart() { stop(); start() }

    // ---------- Actions ----------

    /** Runs a Home Assistant action; the change comes back through the live stream. Errors go to [onError]. */
    fun act(onError: (String) -> Unit = {}, block: suspend HaBackend.() -> Unit) = scope.launch {
        val b = ha ?: return@launch
        try { withContext(Dispatchers.Default) { b.block() } } catch (e: Exception) { onError(e.message ?: e.toString()) }
    }
}

suspend fun HaBackend.printers(): List<PrinterInfo> {
    val (devs, ents) = ws(listOf("config/device_registry/list", "config/entity_registry/list").map { buildJsonObject { put("type", it) } }).map { it as JsonArray }
    return printersFrom(devs, ents)
}

// ---------- Printer controls ----------

fun Printer.light(on: Boolean, err: (String) -> Unit) = Repo.act(err) {
    call("light", if (on) "turn_on" else "turn_off", buildJsonObject { put("entity_id", info.ids["light.chamber_light"]!!) })
}

fun Printer.setSpeed(option: String, err: (String) -> Unit) = Repo.act(err) {
    call("select", "select_option", buildJsonObject { put("entity_id", info.ids["select.printing_speed"]!!); put("option", option) })
}

/** [which]: "nozzle", "bed" or "chamber". */
fun Printer.setTarget(which: String, celsius: Int, err: (String) -> Unit) = Repo.act(err) {
    val id = info.ids["number.target_${which}_temperature"] ?: throw ApiException("Home Assistant can't set the $which temperature")
    setNumber(id, celsius.toDouble())
}

fun Printer.setFan(entityId: String, percent: Int, err: (String) -> Unit) = Repo.act(err) {
    if (percent <= 0) call("fan", "turn_off", buildJsonObject { put("entity_id", entityId) })
    else call("fan", "set_percentage", buildJsonObject { put("entity_id", entityId); put("percentage", percent) })
}

fun Printer.button(key: String, err: (String) -> Unit) = Repo.act(err) { press(this@button, key) }

/** [axis] "X", "Y", "Z" or "Home"; [mm] signed: negative moves X left, Y forward, Z (the bed on X/P/H) up. */
fun Printer.move(axis: String, mm: Int, err: (String) -> Unit) = Repo.act(err) {
    bambu("move_axis", this@move, buildJsonObject { put("axis", axis); put("distance", mm) })
}

/** [extrude] true pushes filament out, false pulls it back. */
fun Printer.extrude(extrude: Boolean, err: (String) -> Unit) = Repo.act(err) {
    bambu("extrude_retract", this@extrude, buildJsonObject { put("type", if (extrude) "Extrude" else "Retract"); put("force", false) })
}

fun Printer.skip(ids: List<Int>, err: (String) -> Unit) = Repo.act(err) {
    bambu("skip_objects", this@skip, buildJsonObject { put("objects", ids.joinToString(",")) })
}

/** Unloading is addressed to the AMS (or the external spool holder) feeding the printer, not the printer itself. */
fun Printer.unload(err: (String) -> Unit) = Repo.act(err) {
    val dev = info.ams.firstOrNull()?.haId ?: info.externalDevice ?: throw ApiException("No AMS or spool holder in Home Assistant")
    call("bambu_lab", "unload_filament", buildJsonObject { put("device_id", dev) })
}

fun Printer.load(s: Spool, err: (String) -> Unit) = Repo.act(err) {
    call("bambu_lab", "load_filament", buildJsonObject { put("entity_id", s.entityId) })
}

fun Printer.toggleCamera(on: Boolean, err: (String) -> Unit) = Repo.act(err) {
    call("switch", if (on) "turn_on" else "turn_off", buildJsonObject { put("entity_id", info.ids["switch.camera"] ?: return@act) })
}

