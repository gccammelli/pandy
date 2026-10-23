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
    /** Where printer state and controls come from: the printer itself (direct mode, the default), Home Assistant, or the demo. */
    var ha: HaBackend? = null
        private set
    /**
     * Print history, files and timelapses: Home Assistant's file cache when it's added (it keeps prints the printer has
     * since deleted), else the printer's own storage in direct mode.
     */
    var extras: HaBackend? = null
        private set
    val state = MutableStateFlow(UiState())
    private var liveJob: Job? = null
    /** Shows print alerts; null where the platform can't (iOS). */
    var notifier: Notifier? = null
    /** Pandy is on screen: the user can see changes, so no alert is needed. */
    var visible = false

    val connected get() = ha != null
    private var token: String? = null
    private var directBackend: DirectPrinter? = null
    /** Home Assistant's token as an HTTP header, for players and downloads that fetch from it directly. */
    fun authHeaders(): Map<String, String> = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
    val haUrl get() = store.settings.getString("ha_url")
    val demo get() = store.settings.getString("demo") == "true"
    /** "lan" (direct) or "ha". */
    val mode get() = store.settings.getString("mode") ?: if (haUrl != null) "ha" else "lan"
    val direct get() = !demo && mode == "lan"
    val lan: LanConfig? get() = store.settings.getString("lan_serial")?.let { sn ->
        LanConfig(store.settings.getString("lan_ip") ?: return null, sn, store.secrets.get("lan_code") ?: return null, store.settings.getString("lan_model") ?: modelName(null, sn), store.settings.getString("lan_name") ?: "")
    }

    fun init(s: PlatformStorage) {
        if (::store.isInitialized) return
        store = s
        io.github.gccammelli.pandy.ui.ThemeMode.mode = s.settings.getString("theme") ?: "system"
        token = s.secrets.get("ha_token")
        val url = haUrl
        val t = token
        val haConn = if (url != null && t != null) HaHttp(url, t) else null
        directBackend = lan?.let { DirectPrinter(it, strict = haConn != null) }
        when {
            demo -> { ha = FakeHomeAssistant(); extras = ha }
            mode == "lan" -> {
                ha = when {
                    directBackend != null && haConn != null -> PreferPrimaryBackend(directBackend!!, haConn)
                    directBackend != null -> directBackend
                    else -> haConn
                }
                extras = haConn ?: directBackend ?: ha
            }
            else -> { ha = haConn; extras = haConn }
        }
        if (ha != null) state.value = UiState(on = true)
    }

    /** Direct mode: checks the printer answers with this access code, then saves it. */
    suspend fun connectPrinter(c: LanConfig, createPrinter: (LanConfig) -> DirectPrinter = { DirectPrinter(it, strict = true) }) {
        val d = createPrinter(c)
        try { withContext(Dispatchers.Default) { d.printers() } } catch (e: Exception) { d.stop(); throw e }
        val remote = extras as? HaHttp
        stop()
        directBackend?.stop()
        directBackend = d
        store.settings.putString("mode", "lan")
        store.settings.putString("lan_ip", c.ip); store.settings.putString("lan_serial", c.serial); store.settings.putString("lan_model", c.model); store.settings.putString("lan_name", c.name)
        store.secrets.set("lan_code", c.code)
        ha = remote?.let { PreferPrimaryBackend(d, it) } ?: d
        if (extras == null || extras is DirectPrinter) extras = d
        state.value = UiState(on = true)
        start()
    }

    /**
     * Adds Home Assistant. [asPrinter]: use it for the printer too (Home Assistant mode: works away from home); otherwise
     * it only adds history, files and slicing to direct mode.
     */
    suspend fun connect(url: String, token: String, asPrinter: Boolean = true) {
        val t = token.filterNot { it.isWhitespace() }
        val b = HaHttp(url, t)
        val found = withContext(Dispatchers.Default) { b.printers() }
        if (found.isEmpty()) throw ApiException("Connected, but Home Assistant has no Bambu Lab printers. Add the Bambu Lab integration there first.")
        store.settings.putString("ha_url", b.base)
        store.secrets.set("ha_token", t)
        this.token = t
        extras = b
        if (asPrinter) {
            stop(); directBackend?.stop(); directBackend = null
            store.settings.putString("mode", "ha")
            ha = b
            state.value = UiState(on = true)
            start()
        } else if (directBackend != null) {
            stop()
            ha = PreferPrimaryBackend(directBackend!!, b)
            state.value = UiState(on = true)
            start()
        }
    }

    /**
     * Pandy Slicer, for printing MakerWorld models (see Slicer.kt): at the address the user set, else its add-on through
     * Home Assistant; null without either.
     */
    val slicer: SlicerClient? get() = store.settings.getString("slicer_url")?.let { SlicerClient(it, store.secrets.get("slicer_token")) }
        ?: haApi?.let { SlicerClient(null, null, it) }
    /** The real Home Assistant connection, whether it's the printer's source or only the extras. */
    val haApi get() = (extras ?: ha) as? HaHttp
    val slicerUrl get() = store.settings.getString("slicer_url")

    fun setSlicer(url: String?, token: String?) {
        store.settings.putString("slicer_url", url?.trim()?.ifEmpty { null })
        store.secrets.set("slicer_token", token?.trim()?.ifEmpty { null })
    }

    /** The printer's LAN details without switching modes: Home Assistant mode needs them to print MakerWorld models. */
    fun saveLan(c: LanConfig) {
        store.settings.putString("lan_ip", c.ip); store.settings.putString("lan_serial", c.serial); store.settings.putString("lan_model", c.model)
        store.secrets.set("lan_code", c.code)
    }

    /** Forgets Home Assistant (direct mode keeps working). */
    fun removeHa() {
        stop()
        directBackend?.stop()
        val d = lan?.let { DirectPrinter(it) }
        directBackend = d
        store.settings.putString("ha_url", null); store.secrets.set("ha_token", null)
        token = null; ha = d; extras = d
        state.value = UiState(on = d != null)
        if (d != null) start()
    }

    /** Removes only the saved direct printer; Home Assistant and slicer settings remain available. */
    fun removeDirectPrinter() {
        stop()
        directBackend?.stop(); directBackend = null
        store.settings.putString("lan_ip", null); store.settings.putString("lan_serial", null)
        store.settings.putString("lan_model", null); store.settings.putString("lan_name", null)
        store.secrets.set("lan_code", null)
        store.settings.putString("mode", "lan")
        extras = extras?.takeUnless { it is DirectPrinter }
        ha = null
        state.value = UiState()
    }

    fun startDemo() {
        store.settings.putString("demo", "true")
        ha = FakeHomeAssistant(); extras = ha
        state.value = UiState(on = true)
        start()
    }

    fun logout() {
        stop()
        directBackend?.stop(); directBackend = null
        store.secrets.set("ha_token", null); store.secrets.set("lan_code", null)
        token = null
        store.settings.clear()
        store.settings.putString("theme", io.github.gccammelli.pandy.ui.ThemeMode.mode)
        ha = null; extras = null
        state.value = UiState()
    }

    /** The printer shown on Devices (Handy shows one at a time and switches from the name). */
    var selected: String?
        get() = store.settings.getString("printer")
        set(v) = store.settings.putString("printer", v)

    fun setTheme(mode: String) { io.github.gccammelli.pandy.ui.ThemeMode.mode = mode; store.settings.putString("theme", mode) }

    fun setting(key: String) = store.settings.getString(key)
    fun setSetting(key: String, value: String?) = store.settings.putString(key, value)

    val timelapseDefault get() = store.settings.getString("timelapse_default") == "true"
    fun setTimelapseDefault(value: Boolean) = store.settings.putString("timelapse_default", value.toString())
    /** Keep unsupported controls visible by default so users can see what Pandy does not expose. */
    val showUnavailable get() = store.settings.getString("show_unavailable") != "false"
    fun setShowUnavailable(value: Boolean) = store.settings.putString("show_unavailable", value.toString())
    /** Print alerts, and the background connection that makes them possible. On unless the user turned it off. */
    val notify get() = store.settings.getString("notify") != "false"
    fun setNotify(value: Boolean) = store.settings.putString("notify", value.toString())
    /** Pandy should stay connected with the screen off, to send alerts. */
    val watching get() = notifier != null && notify && connected && !demo

    fun filamentPreset(serial: String): Map<String, Int> = store.settings.getString("filament_preset_$serial").orEmpty().split(',').mapNotNull { item ->
        val parts = item.split(':', limit = 2)
        if (parts.size == 2 && '|' in parts[0]) parts[1].toIntOrNull()?.let { parts[0] to it } else null
    }.toMap()

    fun setFilamentPreset(serial: String, mapping: Map<String, Int>) {
        store.settings.putString("filament_preset_$serial", mapping.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { null })
    }

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
                        val before = state.value.printers
                        val now = infos.map { i -> Printer(i, snapshot) }
                        state.update { it.copy(printers = now, live = true, error = null) }
                        alert(before, now)
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

    private fun alert(before: List<Printer>?, now: List<Printer>) {
        val n = notifier ?: return
        if (visible || !notify || demo) return
        for (p in now) printEvent(before?.firstOrNull { it.serial == p.serial }, p)?.let { n.show(p.serial, it.title, it.text) }
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
