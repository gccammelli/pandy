package io.github.gccammelli.pandy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import io.github.gccammelli.pandy.res.Res
import kotlin.time.Clock

/**
 * "Try the demo": a pretend Home Assistant with an X1 Carbon halfway through a print, one AMS and an external spool.
 * It answers the same API calls as the real one, and its state reacts to every control (light, temperatures, fans,
 * speed, pause, stop, skip), so every screen can be tried without a printer. Also what Pandy is developed against.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FakeHomeAssistant : HaBackend {
    override val base = "demo"

    private val dev = "demo_printer"
    private val amsDev = "demo_ams"
    private val extDev = "demo_ext"
    private val prefix = "x1c_demo"

    /** (device, domain, translation key, unique id suffix) → initial state and attributes. */
    private data class Row(val device: String, val domain: String, val key: String, val uid: String = key)

    private val rows = mutableListOf<Row>()
    private val states = mutableMapOf<String, Entity>()
    private val changes = Channel<String>(Channel.UNLIMITED)

    private fun id(r: Row) = "${r.domain}.${prefix}_${r.uid}"

    private fun add(domain: String, key: String, state: Any, attrs: JsonObject = JsonObject(emptyMap()), device: String = dev, uid: String = key) {
        val r = Row(device, domain, key, uid)
        rows += r
        states[id(r)] = Entity(id(r), state.toString(), attrs)
    }

    private fun tray(name: String, type: String, color: String, remain: Int, active: Boolean = false, empty: Boolean = false, slot: Int = 0) = buildJsonObject {
        if (slot > 0) put("slot", slot)
        put("active", active); put("color", color); putJsonArray("cols") { add(JsonPrimitive(color)) }
        put("empty", empty); put("name", name); put("type", type); put("remain", remain); put("remain_enabled", remain >= 0)
        put("nozzle_temp_min", if (type == "PETG") "230" else "190"); put("nozzle_temp_max", if (type == "PETG") "260" else "230")
        put("filament_id", "GFA00")
    }

    private var startedAt = Clock.System.now().toEpochMilliseconds()
    private val totalMinutes = 154.0
    internal var progress = 46.0

    init {
        add("binary_sensor", "online", "on")
        add("binary_sensor", "door_open", "off")
        add("binary_sensor", "timelapse", "on")
        add("binary_sensor", "hms_errors", "off", buildJsonObject { put("Count", 0) }, uid = "hms")
        add("binary_sensor", "print_error", "off")
        add("binary_sensor", "firmware_update", "off")
        add("binary_sensor", "developer_lan_mode", "on")
        add("button", "pause", "unknown"); add("button", "resume", "unknown"); add("button", "stop", "unknown")
        add("camera", "camera", "streaming")
        add("switch", "camera", "on")
        add("switch", "printing_monitor", "on")
        add("switch", "buildplate_marker_detector", "on")
        add("switch", "first_layer_inspector", "on")
        add("switch", "auto_recovery", "on")
        add("switch", "auto_switch_filament", "off")
        add("light", "chamber_light", "on")
        add("fan", "cooling_fan", "on", buildJsonObject { put("percentage", 100) })
        add("fan", "aux_fan", "on", buildJsonObject { put("percentage", 70) })
        add("fan", "chamber_fan", "off", buildJsonObject { put("percentage", 0) })
        add("number", "target_nozzle_temperature", 220, buildJsonObject { put("min", 0); put("max", 320) })
        add("number", "target_bed_temperature", 55, buildJsonObject { put("min", 0); put("max", 110) })
        add("select", "printing_speed", "standard", buildJsonObject { putJsonArray("options") { listOf("silent", "standard", "sport", "ludicrous").forEach { add(JsonPrimitive(it)) } } })
        add("sensor", "speed_profile", "standard")
        add("sensor", "nozzle_temp", 219.6); add("sensor", "target_nozzle_temp", 220)
        add("sensor", "bed_temp", 55.0); add("sensor", "target_bed_temp", 55)
        add("sensor", "chamber_temp", 34.0)
        add("sensor", "cooling_fan_speed", 100); add("sensor", "aux_fan_speed", 70); add("sensor", "chamber_fan_speed", 0); add("sensor", "heatbreak_fan_speed", 100)
        add("sensor", "print_status", "running")
        add("sensor", "stage", "printing")
        add("sensor", "print_progress", progress.toInt())
        add("sensor", "remaining_time", remaining())
        add("sensor", "current_layer", 0); add("sensor", "total_layers", 312)
        add("sensor", "subtask_name", "Articulated Panda Keychain")
        add("sensor", "gcode_file", "/data/Metadata/plate_1.gcode")
        add("sensor", "printer_name", "Demo X1C")
        add("sensor", "print_weight", 38.4); add("sensor", "print_length", 12.7)
        add("sensor", "print_bed_type", "textured_plate")
        add("sensor", "nozzle_diameter", 0.4); add("sensor", "nozzle_type", "hardened_steel")
        add("sensor", "printable_objects", 3, buildJsonObject { putJsonObject("objects") { put("101", "Panda body"); put("102", "Panda head"); put("103", "Bamboo stick") } })
        add("sensor", "skipped_objects", 0, buildJsonObject { putJsonArray("objects") {} })
        add("sensor", "wifi_signal", -52)
        add("sensor", "ip_address", "192.0.2.42"); add("sensor", "serial", "00M000000000000")
        add("sensor", "total_usage_hours", 412.6)
        add("sensor", "sdcard_status", "normal")
        add("sensor", "active_tray", "Bambu PLA Basic")
        add("image", "cover_image", "1"); add("image", "pick_image", "1")
        // AMS 1: four trays, slot 2 printing.
        listOf(tray("Bambu PLA Basic", "PLA", "#FFFFFFFF", 82, slot = 1), tray("Bambu PLA Basic", "PLA", "#161616FF", 64, active = true, slot = 2),
            tray("Bambu PETG HF", "PETG", "#00AE42FF", 25, slot = 3), tray("Empty", "", "#00000000", -1, empty = true, slot = 4))
            .forEachIndexed { i, a -> add("sensor", "tray", if (a.s("empty") == "true") "Empty" else a.s("name")!!, a, amsDev, "AMS_1_tray_${i + 1}") }
        add("sensor", "humidity_index", 2, device = amsDev, uid = "AMS_1_humidity_index")
        add("sensor", "humidity", 23, device = amsDev, uid = "AMS_1_humidity")
        add("sensor", "ams_temp", 27.0, device = amsDev, uid = "AMS_1_temperature")
        add("switch", "ams_read_on_insertion", "on", device = amsDev, uid = "AMS_1_ams_read_on_insertion")
        add("switch", "ams_read_on_startup", "on", device = amsDev, uid = "AMS_1_ams_read_on_startup")
        add("switch", "ams_remaining_estimation", "on", device = amsDev, uid = "AMS_1_ams_remaining_estimation")
        add("switch", "ams_auto_refill", "off", device = amsDev, uid = "AMS_1_ams_auto_refill")
        add("sensor", "external_spool", "Generic PLA", tray("Generic PLA", "PLA", "#F98C36FF", -1), extDev, "ExternalSpool_external_spool")
        layerFromProgress()
    }

    private fun remaining() = (totalMinutes * (100 - progress) / 100).toInt()
    private fun layerFromProgress() = set("sensor", "current_layer", (312 * progress / 100).toInt())

    private fun eid(domain: String, key: String) = rows.first { it.domain == domain && it.key == key }.let(::id)
    private fun state(domain: String, key: String) = states[eid(domain, key)]!!

    private fun set(domain: String, key: String, value: Any, attrs: JsonObject? = null) {
        val id = eid(domain, key)
        val old = states[id]!!
        states[id] = Entity(id, value.toString(), attrs ?: old.attrs)
        changes.trySend(id)
    }

    private fun setById(id: String, value: Any, attrs: JsonObject? = null) {
        val old = states[id] ?: return
        states[id] = Entity(id, value.toString(), attrs ?: old.attrs)
        changes.trySend(id)
    }

    // ---------- Registries ----------

    private fun deviceRegistry() = buildJsonArray {
        fun d(id: String, name: String, model: String, ident: String, via: String?) = add(buildJsonObject {
            put("id", id); put("name", name); put("model", model); put("sw_version", if (via == null) "01.08.02.00" else null)
            put("via_device_id", via); put("name_by_user", JsonNull)
            putJsonArray("identifiers") { add(buildJsonArray { add(JsonPrimitive("bambu_lab")); add(JsonPrimitive(ident)) }) }
        })
        d(dev, "X1C_00M000000000000", "X1C", "00M000000000000", null)
        d(amsDev, "X1C_00M000000000000_AMS_1", "AMS", "00M000000000000_AMS_1", dev)
        d(extDev, "X1C_00M000000000000_ExternalSpool", "External Spool", "00M000000000000_ExternalSpool", dev)
    }

    private fun entityRegistry() = buildJsonArray {
        rows.forEach { r ->
            add(buildJsonObject {
                put("entity_id", id(r)); put("device_id", r.device); put("platform", "bambu_lab")
                put("translation_key", r.key); put("unique_id", "X1C_00M000000000000_${r.uid}"); put("disabled_by", JsonNull)
            })
        }
    }

    private fun stateJson(e: Entity) = buildJsonObject { put("s", e.state); put("a", e.attrs) }

    // ---------- HaBackend ----------

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map { c ->
        when (c.s("type")) {
            "config/device_registry/list" -> deviceRegistry()
            "config/entity_registry/list" -> entityRegistry()
            "camera/stream" -> throw ApiException("The demo has no live video; showing a still.")
            else -> JsonNull
        }
    }

    override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) {
        val wanted = subscribe.a("entity_ids")?.map { (it as JsonPrimitive).content }?.toSet()
        onEvent(buildJsonObject { putJsonObject("a") { states.values.filter { wanted == null || it.id in wanted }.forEach { put(it.id, stateJson(it)) } } })
        var lastTick = Clock.System.now().toEpochMilliseconds()
        while (true) {
            val changed = mutableSetOf<String>()
            select {
                changes.onReceive { changed += it }
                onTimeout(2_000) {}
            }
            while (true) changed += changes.tryReceive().getOrNull() ?: break
            val now = Clock.System.now().toEpochMilliseconds()
            if (now - lastTick >= 2_000) { lastTick = now; tick(); while (true) changed += changes.tryReceive().getOrNull() ?: break }
            val out = changed.filter { wanted == null || it in wanted }.mapNotNull { states[it] }
            if (out.isNotEmpty()) onEvent(buildJsonObject { putJsonObject("c") { out.forEach { put(it.id, buildJsonObject { put("+", stateJson(it)) }) } } })
        }
    }

    /** Every two seconds: the print moves on, temperatures drift toward their targets. */
    internal fun tick() {
        val status = state("sensor", "print_status").state
        if (status == "running") {
            progress = (progress + 0.25).coerceAtMost(100.0)
            set("sensor", "print_progress", progress.toInt())
            set("sensor", "remaining_time", remaining())
            layerFromProgress()
            if (progress >= 100) finish("finish")
        }
        for ((sensor, target) in listOf("nozzle_temp" to "target_nozzle_temp", "bed_temp" to "target_bed_temp")) {
            val t = state("sensor", target).num ?: 0.0
            val now = state("sensor", sensor).num ?: 25.0
            val goal = if (t > 0) t else 26.0
            val next = now + (goal - now) * 0.3 + listOf(-.2, 0.0, .2).random()
            set("sensor", sensor, (next * 10).toInt() / 10.0)
        }
    }

    private fun finish(status: String) {
        set("sensor", "print_status", status)
        set("sensor", "stage", "idle")
        set("sensor", "remaining_time", 0)
        set("number", "target_nozzle_temperature", 0); set("sensor", "target_nozzle_temp", 0)
        set("number", "target_bed_temperature", 0); set("sensor", "target_bed_temp", 0)
    }

    override suspend fun get(path: String): String = when {
        path.startsWith("/api/states/") -> states[path.removePrefix("/api/states/")]?.let {
            buildJsonObject { put("entity_id", it.id); put("state", it.state); put("attributes", it.attrs) }.toString()
        } ?: throw ApiException("Home Assistant: HTTP 404")
        path.startsWith("/api/history/") -> "[]"
        path.startsWith("/api/bambu_lab/print_history") -> DEMO_HISTORY
        path.startsWith("/api/bambu_lab/videos") -> DEMO_VIDEOS
        path.endsWith(".slice_info.config") -> """<config><plate><metadata key="index" value="1"/><filament id="1" type="PLA" color="#FFFFFF"/><filament id="2" type="PLA" color="#000000"/></plate></config>"""
        path == "/api/config" -> """{"time_zone":"UTC","version":"2026.10.0"}"""
        else -> "[]"
    }

    override suspend fun post(path: String, body: String): String {
        if (path == "/api/bambu_lab/ensure_cache_file") { delay(600); return """{"status":"present"}""" }
        val d = json.parseToJsonElement(body).jsonObject
        val target = d.s("entity_id")
        val service = path.removePrefix("/api/services/")
        when (service) {
            "light/turn_on" -> set("light", "chamber_light", "on")
            "light/turn_off" -> set("light", "chamber_light", "off")
            "light/toggle" -> set("light", "chamber_light", if (state("light", "chamber_light").on) "off" else "on")
            "number/set_value" -> {
                val v = d.s("value")?.toDoubleOrNull() ?: 0.0
                setById(target!!, v.toInt())
                when (target) {
                    eid("number", "target_nozzle_temperature") -> set("sensor", "target_nozzle_temp", v.toInt())
                    eid("number", "target_bed_temperature") -> set("sensor", "target_bed_temp", v.toInt())
                }
            }
            "select/select_option" -> { val o = d.s("option")!!; set("select", "printing_speed", o); set("sensor", "speed_profile", o) }
            "fan/set_percentage", "fan/turn_on", "fan/turn_off" -> {
                val pct = if (service == "fan/turn_off") 0 else d.s("percentage")?.toIntOrNull() ?: 100
                setById(target!!, if (pct > 0) "on" else "off", buildJsonObject { put("percentage", pct) })
                val key = rows.first { id(it) == target }.key.removeSuffix("_fan")
                set("sensor", "${key}_fan_speed", pct)
            }
            "button/press" -> when (target) {
                eid("button", "pause") -> if (state("sensor", "print_status").state == "running") { set("sensor", "print_status", "pause"); set("sensor", "stage", "paused") }
                eid("button", "resume") -> if (state("sensor", "print_status").state == "pause") { set("sensor", "print_status", "running"); set("sensor", "stage", "printing") }
                eid("button", "stop") -> finish("failed")
            }
            "bambu_lab/skip_objects" -> {
                val ids = d.s("objects").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                val now = (state("sensor", "skipped_objects").attrs.a("objects")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty() + ids).distinct()
                set("sensor", "skipped_objects", now.size, buildJsonObject { putJsonArray("objects") { now.forEach { add(JsonPrimitive(it)) } } })
            }
            "bambu_lab/print_project_file" -> {
                progress = 0.0; startedAt = Clock.System.now().toEpochMilliseconds()
                set("sensor", "subtask_name", d.s("filepath")?.substringAfterLast('/')?.removeSuffix(".3mf") ?: "Model")
                set("sensor", "print_status", "running"); set("sensor", "stage", "printing")
                set("number", "target_nozzle_temperature", 220); set("sensor", "target_nozzle_temp", 220)
                set("number", "target_bed_temperature", 55); set("sensor", "target_bed_temp", 55)
                set("sensor", "skipped_objects", 0, buildJsonObject { putJsonArray("objects") {} })
            }
            "bambu_lab/calibration" -> set("sensor", "stage", "calibrating")
            "bambu_lab/xcam_control_set" -> d.s("module_name")?.let { module ->
                rows.firstOrNull { it.device == dev && it.key == module }?.let { setById(id(it), if (d.s("enable") == "true") "on" else "off") }
            }
            "bambu_lab/print_option" -> listOf("auto_recovery", "auto_switch_filament", "filament_tangle_detect", "nozzle_blob_detect", "air_print_detect", "sound_enable")
                .firstOrNull { d[it] != null }?.let { option -> d.s(option)?.let { value -> rows.firstOrNull { it.key == option }?.let { setById(id(it), if (value == "true") "on" else "off") } } }
            "bambu_lab/ams_user_setting" -> {
                val ams = d.s("ams_id")?.toIntOrNull() ?: 0
                listOf("ams_read_on_startup" to "startup_read_option", "ams_read_on_insertion" to "tray_read_option", "ams_remaining_estimation" to "calibrate_remain_flag")
                    .forEach { (key, field) -> rows.firstOrNull { it.device == amsDev && it.key == key }?.let { setById(id(it), if (d.s(field) == "true") "on" else "off") } }
            }
            "switch/turn_on", "switch/turn_off" -> setById(target!!, if (service.endsWith("on")) "on" else "off")
            else -> {} // move_axis, extrude_retract, load/unload: nothing to show in the demo
        }
        delay(150)
        return "[]"
    }

    override suspend fun bytes(path: String): ByteArray = when {
        "cover_image" in path -> Res.readBytes("files/demo_cover.png")
        "pick_image" in path -> Res.readBytes("files/demo_pick.png")
        "camera_proxy" in path -> Res.readBytes("files/demo_camera.jpg")
        "file_cache" in path -> Res.readBytes("files/demo_cover.png")
        else -> ByteArray(0)
    }
}

private fun demoFile(name: String, size: Long, day: String, kind: String = "prints", ext: String = ".3mf") =
    """{"filename":"$size-$name$ext","path":"00M000000000000/$kind/$size-$name$ext","size":$size,"modified":"${day}T14:32:10","thumbnail_path":"00M000000000000/$kind/$size-$name.png"}"""

private val DEMO_HISTORY = """{"files":[${listOf(
    demoFile("Articulated Panda Keychain", 2_412_331, "2026-10-16"),
    demoFile("Bamboo Desk Organizer", 5_120_004, "2026-10-12"),
    demoFile("Cable Clip x8", 312_877, "2026-10-09"),
    demoFile("Benchy", 1_903_442, "2026-10-01"),
).joinToString(",")}]}"""

private val DEMO_VIDEOS = """{"videos":[${listOf(
    demoFile("video_2026-10-16_14-32-10", 10_462_292, "2026-10-16", "timelapse", ".mp4"),
    demoFile("video_2026-10-12_09-05-44", 8_120_004, "2026-10-12", "timelapse", ".mp4"),
).joinToString(",")}]}"""
