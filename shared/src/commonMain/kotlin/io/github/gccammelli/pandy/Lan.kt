package io.github.gccammelli.pandy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt
import kotlin.time.Clock

/*
 * Direct mode: Pandy talks to the printer itself on the local network, the way Bambu Studio does in LAN mode.
 *  - MQTT over TLS on port 8883 (user "bblp", password the printer's access code): the printer reports its state on
 *    device/<serial>/report and takes commands on device/<serial>/request.
 *  - Camera: RTSP over TLS on port 322 (X1, H2) or a JPEG stream over TLS on port 6000 (P1, A1).
 * The printer's certificate is checked against Bambu's device CAs (bundled) and must name the printer's serial.
 *
 * [DirectPrinter] presents all this as a small pretend Home Assistant (same entities as the Bambu Lab integration),
 * so every screen works the same in both modes.
 */

/** A printer on the local network: [model] is Bambu's short name ("X1C", "P1S", "A1"). */
/** [name] is what the printer calls itself (set on its screen; it announces it), shown like Handy shows it. */
data class LanConfig(val ip: String, val serial: String, val code: String, val model: String, val name: String = "")

/** One MQTT session with the printer. [run] connects, subscribes, hands each report to [onMessage] and returns or throws when the connection drops. */
interface LanLink {
    suspend fun run(onConnected: () -> Unit, onMessage: (String) -> Unit)
    fun publish(payload: String)
    fun close()
}

expect fun lanLink(c: LanConfig): LanLink

/** Streams the P1/A1 camera's JPEG frames until cancelled or the connection drops. */
expect suspend fun lanJpegStream(c: LanConfig, onFrame: (ByteArray) -> Unit)

/** A printer that announced itself on the network (Bambu printers broadcast SSDP on port 2021). */
data class FoundPrinter(val ip: String, val serial: String, val model: String, val name: String)

/** Listens for printers' announcements for [millis]. */
expect suspend fun discoverPrinters(millis: Long): List<FoundPrinter>

/** Bambu's model codes (SSDP and serial prefixes) → the names Handy shows. */
fun modelName(code: String?, serial: String = ""): String = when {
    code == null || code.isBlank() -> when (serial.take(3)) {
        "00M" -> "X1C"; "00W" -> "X1"; "03W" -> "X1E"; "01S" -> "P1P"; "01P" -> "P1S"; "22E" -> "P2S"
        "039" -> "A1"; "030" -> "A1 mini"; "094" -> "H2D"; "239" -> "H2S"; else -> "Bambu printer"
    }
    code.contains("X1-Carbon", true) || code == "BL-P001" -> "X1C"
    code.contains("X1E", true) || code == "C13" -> "X1E"
    code == "BL-P002" -> "X1"
    code == "C11" -> "P1P"
    code == "C12" -> "P1S"
    code == "N7" -> "P2S"
    code == "N2S" -> "A1"
    code == "N1" -> "A1 mini"
    code == "O1D" -> "H2D"
    code == "O1S" -> "H2S"
    else -> code
}

/** X1 and H2 cameras stream RTSP; P1 and A1 send JPEG frames. */
fun rtspCamera(model: String) = model.startsWith("X1") || model.startsWith("H2") || model.startsWith("P2")

/** Merges a report into the last one: printers (P1, A1) send only what changed. Objects merge; arrays and values replace. */
fun deepMerge(old: JsonObject, new: JsonObject): JsonObject = JsonObject(old + new.mapValues { (k, v) ->
    val o = old[k]
    if (o is JsonObject && v is JsonObject) deepMerge(o, v) else v
})

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(k: String) = str(k)?.toDoubleOrNull()?.toInt()
private fun JsonObject.dbl(k: String) = str(k)?.toDoubleOrNull()
private fun JsonElement?.flagValue(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.let {
    when (it.lowercase()) {
        "on", "true", "1", "enable", "enabled" -> true
        "off", "false", "0", "disable", "disabled" -> false
        else -> null
    }
}
private fun JsonObject.flag(k: String): Boolean? = this[k].flagValue() ?: (this[k] as? JsonObject)?.get("enable").flagValue()
private fun JsonObject.bit(k: String, bit: Int): Boolean? = str(k)?.toLongOrNull()?.let { (it and (1L shl bit)) != 0L }
private fun JsonObject.hexBit(k: String, bit: Int): Boolean? = str(k)?.toLongOrNull(16)?.let { (it and (1L shl bit)) != 0L }
private fun JsonObject.layerInt(k: String, live: Boolean, progress: Int): Int? {
    val top = int(k)
    val nested = o("3D")?.int(k)
    // Some firmware leaves the legacy top-level field at zero while updating the 3D block.
    return when {
        top != null && !(live && progress > 0 && top == 0 && nested != null && nested > 0) -> top
        live && progress > 0 -> nested
        else -> null // Do not expose a stale nested value after a print has finished.
    }
}

/** Fan speed 0–15 → percent in tens, as the Home Assistant integration rounds it. */
fun fanPercent(raw: String?): Int = raw?.toIntOrNull()?.let { (it / 15.0 * 100 / 10).roundToInt() * 10 } ?: 0

/** The printer's gcode_state → the integration's print_status. */
fun printStatus(gcodeState: String?): String = when (gcodeState?.uppercase()) {
    null, "" -> "idle"
    "RUNNING" -> "running"; "PAUSE" -> "pause"; "FINISH" -> "finish"; "FAILED" -> "failed"; "PREPARE" -> "prepare"; "SLICING" -> "slicing"
    else -> gcodeState.lowercase()
}

/** The printer's stage numbers (stg_cur) → the integration's stage keys (the common ones). */
fun stageName(n: Int?): String = when (n) {
    null, -1, 255 -> "idle"
    0 -> "printing"; 1 -> "auto_bed_leveling"; 2 -> "heatbed_preheating"; 3 -> "sweeping_xy_mech_mode"; 4 -> "changing_filament"
    5 -> "m400_pause"; 6 -> "paused_filament_runout"; 7 -> "heating_hotend"; 8 -> "calibrating_extrusion"; 9 -> "scanning_bed_surface"
    10 -> "inspecting_first_layer"; 11 -> "identifying_build_plate_type"; 12, 18 -> "calibrating_micro_lidar"; 13 -> "homing_toolhead"
    14 -> "cleaning_nozzle_tip"; 15 -> "checking_extruder_temperature"; 16 -> "paused_user"; 17 -> "paused_front_cover_falling"
    19 -> "calibrating_extrusion_flow"; 20 -> "paused_nozzle_temperature_malfunction"; 21 -> "paused_heat_bed_temperature_malfunction"
    22 -> "filament_unloading"; 23 -> "paused_skipped_step"; 24 -> "filament_loading"; 25 -> "calibrating_motor_noise"
    26 -> "paused_ams_lost"; 29 -> "cooling_chamber"; 30 -> "paused_user_gcode"; 34 -> "paused_first_layer_error"; 35 -> "paused_nozzle_clog"
    49 -> "heating_chamber"; 54 -> "waiting_for_heatbed_temperature"
    else -> "stage_$n"
}

/** Newer firmware sends nozzle codes ("HS01", "HH01"): 2nd letter H = high flow, last two digits the material. */
fun nozzleTypeName(t: String): String {
    if (t.length != 4 || !t[0].isLetter() || !t.drop(2).all { it.isDigit() }) return t
    val material = when (t.substring(2)) { "00" -> "stainless_steel"; "01" -> "hardened_steel"; "05" -> "tungsten_carbide"; else -> "unknown" }
    return (if (t[1] == 'H') "high_flow_" else "") + material
}

/** An HMS entry's attr and code → "HMS_0300_0100_0001_0007". */
fun hmsCode(attr: Long, code: Long): String {
    fun h(v: Long) = v.toString(16).uppercase().padStart(4, '0')
    return "HMS_${h(attr shr 16)}_${h(attr and 0xFFFF)}_${h(code shr 16)}_${h(code and 0xFFFF)}"
}

/**
 * The entities the Bambu Lab integration would show for this report, keyed like its registry ("domain", translation
 * key, unique-id suffix, device) so [printersFrom] reads them unchanged. [online]: a report arrived recently.
 */
fun lanEntities(c: LanConfig, r: JsonObject, online: Boolean, job: LanJob? = null): List<Pair<LanRow, Entity>> {
    val p = r.o("print") ?: JsonObject(emptyMap())
    val out = mutableListOf<Pair<LanRow, Entity>>()
    fun add(domain: String, key: String, state: Any?, attrs: JsonObject = JsonObject(emptyMap()), device: String = "printer", uid: String = key, deviceModel: String = "") {
        val id = "$domain.pandy_${c.serial.lowercase()}_${uid.lowercase()}"
        out += LanRow(device, domain, key, uid, id, deviceModel) to Entity(id, state?.toString() ?: "unknown", attrs)
    }
    val connected = if (online) "on" else "off"
    add("binary_sensor", "online", connected)
    // Developer Mode off = the printer only takes commands signed by Bambu's own apps (bit 0x20000000 of "fun", as
    // the Home Assistant integration reads it): status still flows, controls don't.
    val signed = p.str("fun")?.toLongOrNull(16)?.let { it and 0x20000000L != 0L } ?: false
    add("binary_sensor", "developer_lan_mode", if (signed) "off" else "on")
    p.o("ipcam")?.let { add("binary_sensor", "timelapse", if (it.str("timelapse") == "enable") "on" else "off") }
    val hms = (p["hms"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        hmsCode(o.str("attr")?.toLongOrNull() ?: return@mapNotNull null, o.str("code")?.toLongOrNull() ?: 0)
    }
    add("binary_sensor", "hms_errors", if (hms.isEmpty()) "off" else "on", buildJsonObject {
        put("Count", hms.size)
        hms.forEachIndexed { i, code ->
            put("${i + 1}-Code", code); put("${i + 1}-Error", "Printer message ${code.removePrefix("HMS_").replace('_', '-')}")
            put("${i + 1}-Wiki", "https://wiki.bambulab.com/en/x1/troubleshooting/hmscode/${code.removePrefix("HMS_")}")
        }
    }, uid = "hms")
    val err = p.int("print_error") ?: 0
    add("binary_sensor", "print_error", if (err != 0) "on" else "off", if (err != 0) buildJsonObject {
        val hex = err.toLong().toString(16).uppercase().padStart(8, '0')
        put("code", "${hex.take(4)}_${hex.drop(4)}"); put("error", "Print error ${hex.take(4)}-${hex.drop(4)}")
    } else JsonObject(emptyMap()))
    listOf("pause", "resume", "stop").forEach { add("button", it, "unknown") }
    add("camera", "camera", if (online) "streaming" else "unavailable")
    add("switch", "camera", "on")
    // These are reported only by firmware that supports the corresponding XCam/print option.
    val xcam = p.o("xcam") ?: r.o("xcam")
    listOf("printing_monitor", "buildplate_marker_detector", "first_layer_inspector", "spaghetti_detector", "pileup_detector", "air_print_detector", "clump_detector")
        .forEach { module ->
            xcam?.flag(module)?.let { enabled ->
                val printHalt = xcam.flag("${module}_print_halt") ?: xcam.o(module)?.flag("print_halt") ?: xcam.flag("print_halt")
                add("switch", module, if (enabled) "on" else "off", buildJsonObject {
                    printHalt?.let { put("print_halt", it) }
                })
            }
        }
    val printOptions = p.o("print_option") ?: r.o("print_option")
    // Legacy firmware puts these values in home_flag; newer firmware mirrors them in the hex cfg bitmap.
    (printOptions?.flag("auto_recovery") ?: p.flag("auto_recovery") ?: p.bit("home_flag", 4) ?: p.hexBit("cfg", 16))?.let { add("switch", "auto_recovery", if (it) "on" else "off") }
    (printOptions?.flag("auto_switch_filament") ?: p.flag("auto_switch_filament") ?: p.bit("home_flag", 10) ?: p.hexBit("cfg", 18))?.let { add("switch", "auto_switch_filament", if (it) "on" else "off") }
    val light = (p["lights_report"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("node") == "chamber_light" }?.str("mode")
    add("light", "chamber_light", if (light == "on") "on" else "off")
    for ((key, raw) in listOf("cooling_fan" to "cooling_fan_speed", "aux_fan" to "big_fan1_speed", "chamber_fan" to "big_fan2_speed")) {
        val pct = fanPercent(p.str(raw))
        add("fan", key, if (pct > 0) "on" else "off", buildJsonObject { put("percentage", pct) })
        add("sensor", "${key}_speed", pct)
    }
    add("sensor", "heatbreak_fan_speed", fanPercent(p.str("heatbreak_fan_speed")))
    add("number", "target_nozzle_temperature", p.dbl("nozzle_target_temper")?.toInt() ?: 0, buildJsonObject { put("min", 0); put("max", 320) })
    add("number", "target_bed_temperature", p.dbl("bed_target_temper")?.toInt() ?: 0, buildJsonObject { put("min", 0); put("max", 120) })
    val speed = when (p.int("spd_lvl")) { 1 -> "silent"; 3 -> "sport"; 4 -> "ludicrous"; else -> "standard" }
    add("select", "printing_speed", speed, buildJsonObject { putJsonArray("options") { listOf("silent", "standard", "sport", "ludicrous").forEach { add(JsonPrimitive(it)) } } })
    add("sensor", "speed_profile", speed)
    add("sensor", "nozzle_temp", p.dbl("nozzle_temper"))
    add("sensor", "target_nozzle_temp", p.dbl("nozzle_target_temper")?.toInt())
    add("sensor", "bed_temp", p.dbl("bed_temper"))
    add("sensor", "target_bed_temp", p.dbl("bed_target_temper")?.toInt())
    (p.str("bed_type") ?: p.str("print_bed_type"))?.let { add("sensor", "print_bed_type", it) }
    // Only enclosed printers with a chamber sensor report it (X1, H2); P1S shows none, like Handy. Newer firmware moved it under device.ctc.
    (p.dbl("chamber_temper") ?: p.o("device")?.o("ctc")?.o("info")?.dbl("temp"))?.takeIf { c.model.startsWith("X1") || c.model.startsWith("H2") || c.model.startsWith("P2") }?.let { add("sensor", "chamber_temp", it) }
    val status = if (!online) "offline" else printStatus(p.str("gcode_state"))
    add("sensor", "print_status", status)
    add("sensor", "stage", stageName(p.int("stg_cur")))
    add("sensor", "print_progress", p.int("mc_percent") ?: 0)
    add("sensor", "remaining_time", p.int("mc_remaining_time") ?: 0)
    val liveLayers = p.str("gcode_state")?.uppercase() in setOf("RUNNING", "PAUSE")
    val progress = p.int("mc_percent") ?: 0
    add("sensor", "current_layer", p.layerInt("layer_num", liveLayers, progress) ?: 0)
    add("sensor", "total_layers", p.layerInt("total_layer_num", liveLayers, progress) ?: 0)
    add("sensor", "subtask_name", p.str("subtask_name")?.takeIf { it.isNotBlank() })
    add("sensor", "gcode_file", p.str("gcode_file"))
    add("sensor", "printer_name", c.name.ifBlank { c.model })
    p.str("nozzle_diameter")?.let { add("sensor", "nozzle_diameter", it) }
    p.str("nozzle_type")?.let { add("sensor", "nozzle_type", nozzleTypeName(it)) }
    p.str("wifi_signal")?.let { add("sensor", "wifi_signal", it.removeSuffix("dBm")) }
    add("sensor", "ip_address", c.ip)
    add("sensor", "serial", c.serial)
    p.str("sdcard")?.let { add("sensor", "sdcard_status", if (it == "true") "normal" else "missing") }
    if (job != null) {
        add("image", "cover_image", job.key, uid = "cover_image")
        if (job.pick != null) add("image", "pick_image", job.key, uid = "pick_image")
        add("sensor", "printable_objects", job.objects.size, buildJsonObject { putJsonObject("objects") { job.objects.forEach { (k, v) -> put("$k", v) } } })
    }
    val skipped = (p["s_obj"] as? JsonArray).orEmpty()
    add("sensor", "skipped_objects", skipped.size, buildJsonObject { put("objects", JsonArray(skipped)) })

    // AMS units and the external spool.
    val ams = p.o("ams")
    val trayNow = ams?.str("tray_now")
    var htFallback = 0
    (ams?.get("ams") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEachIndexed { amsIndex, a ->
        val ai = a.int("id") ?: 0
        val dev = "ams$ai"
        val trays = (a["tray"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val isHt = ai >= 128 || a.str("model")?.contains("HT", true) == true || a.str("type")?.contains("HT", true) == true ||
            a.str("model")?.contains("N3S", true) == true || a.str("type")?.contains("N3S", true) == true
        val htId = if (isHt) ai.takeIf { it >= 128 } ?: (128 + htFallback++) else ai
        val deviceModel = if (isHt) "AMS HT" else "AMS"
        (ams?.flag("power_on_flag") ?: a.flag("startup_read_option"))?.let { add("switch", "ams_read_on_startup", if (it) "on" else "off", device = dev, uid = "AMS_${amsIndex + 1}_ams_read_on_startup") }
        (ams?.flag("insert_flag") ?: a.flag("tray_read_option"))?.let { add("switch", "ams_read_on_insertion", if (it) "on" else "off", device = dev, uid = "AMS_${amsIndex + 1}_ams_read_on_insertion") }
        (ams?.flag("calibrate_remain_flag") ?: a.flag("calibrate_remain_flag"))?.let { add("switch", "ams_remaining_estimation", if (it) "on" else "off", device = dev, uid = "AMS_${amsIndex + 1}_ams_remaining_estimation") }
        a.int("humidity")?.let { add("sensor", "humidity_index", 6 - it, device = dev, uid = "AMS_${amsIndex + 1}_humidity_index") }
        a.int("humidity_raw")?.let { add("sensor", "humidity", it, device = dev, uid = "AMS_${amsIndex + 1}_humidity") }
        a.dbl("temp")?.let { add("sensor", "ams_temp", it, device = dev, uid = "AMS_${amsIndex + 1}_temperature") }
        (0 until if (deviceModel == "AMS HT") 1 else 4).forEach { ti ->
            val t = trays.firstOrNull { it.int("id") == ti } ?: JsonObject(emptyMap())
            val type = t.str("tray_type").orEmpty()
            val name = t.str("tray_sub_brands")?.takeIf { it.isNotBlank() }?.let { if (it.startsWith(type)) "Bambu $it" else it } ?: type
            val trayId = if (isHt) htId else ai * 4 + ti
            add("sensor", "tray", if (type.isEmpty()) "Empty" else name, trayAttrs(t, name, trayId.toString() == trayNow, ti + 1), dev, "AMS_${amsIndex + 1}_tray_${ti + 1}", deviceModel)
        }
    }
    p.o("vt_tray")?.let { t ->
        val type = t.str("tray_type").orEmpty()
        val name = t.str("tray_sub_brands")?.takeIf { it.isNotBlank() } ?: type
        add("sensor", "external_spool", if (type.isEmpty()) "Empty" else name, trayAttrs(t, name, trayNow == "254", 0), "ext", "ExternalSpool_external_spool")
    }
    return out
}

private fun trayAttrs(t: JsonObject, name: String, active: Boolean, slot: Int) = buildJsonObject {
    val color = t.str("tray_color") ?: "00000000"
    if (slot > 0) put("slot", slot)
    put("active", active); put("color", "#$color"); putJsonArray("cols") { add(JsonPrimitive("#$color")) }
    put("empty", t.str("tray_type").isNullOrEmpty()); put("name", name); put("type", t.str("tray_type").orEmpty())
    put("remain", t.int("remain") ?: -1); put("nozzle_temp_min", t.str("nozzle_temp_min")); put("nozzle_temp_max", t.str("nozzle_temp_max"))
    put("filament_id", t.str("tray_info_idx"))
}

/** The current job's 3MF, read from the printer's storage: plate picture, pick map and parts. */
class LanJob(val key: String, val cover: ByteArray?, val pick: ByteArray?, val objects: Map<Int, String>)

data class LanRow(val device: String, val domain: String, val key: String, val uid: String, val id: String, val deviceModel: String = "")

private fun printCommand(sequenceId: String, command: String, fields: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
    put("print", JsonObject(buildJsonObject { put("sequence_id", sequenceId); put("command", command) } + fields))
}

fun calibrationCommand(sequenceId: String, option: Int) = printCommand(sequenceId, "calibration", buildJsonObject { put("option", option) })

fun xcamCommand(sequenceId: String, module: String, enabled: Boolean, printHalt: Boolean) = buildJsonObject {
    putJsonObject("xcam") {
        put("sequence_id", sequenceId); put("command", "xcam_control_set"); put("module_name", module)
        put("control", enabled); put("enable", enabled); put("print_halt", printHalt)
    }
}

fun printOptionCommand(sequenceId: String, option: String, enabled: Boolean) = printCommand(sequenceId, "print_option", buildJsonObject {
    put(option, enabled)
    if (option == "auto_recovery") put("option", if (enabled) 1 else 0)
})

fun amsUserSettingCommand(sequenceId: String, amsId: Int, startup: Boolean, insertion: Boolean, remaining: Boolean) =
    printCommand(sequenceId, "ams_user_setting", buildJsonObject {
        put("ams_id", amsId); put("startup_read_option", startup); put("tray_read_option", insertion); put("calibrate_remain_flag", remaining)
    })

/** The printer reached directly, presented as a small Home Assistant (see the file comment). */
/**
 * [strict]: fail if the printer doesn't answer (when it's being set up); otherwise an unreachable printer just shows as
 * offline while the connection keeps retrying, as Handy shows it.
 */
class DirectPrinter(
    val config: LanConfig,
    private val strict: Boolean = false,
    private val linkFactory: (LanConfig) -> LanLink = ::lanLink,
    private val storageFactory: (LanConfig) -> PrinterStorage = ::printerStorage,
    private val jpegStream: suspend (LanConfig, (ByteArray) -> Unit) -> Unit = ::lanJpegStream,
) : HaBackend {
    override val base = "lan://${config.ip}"
    private var report = JsonObject(emptyMap())
    private var rows: List<LanRow> = emptyList()
    private var link: LanLink? = null
    private var seq = 0
    private var lastReport = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun entities() = lanEntities(config, report, Clock.System.now().toEpochMilliseconds() - lastReport < 60_000 && link != null, job)

    // ---------- The printer's storage (FTPS) ----------

    private val storage by lazy { storageFactory(config) }
    private var job: LanJob? = null
    private var jobLoading: String? = null
    private var jobMissAt = 0L

    /** When the job changes, finds its 3MF on the printer (as the integration does) and reads its picture and parts. */
    private fun checkJob() {
        val p = report.o("print") ?: return
        val task = p.str("subtask_name").orEmpty()
        val gcode = p.str("gcode_file").orEmpty()
        val key = "$task|$gcode"
        val now = Clock.System.now().toEpochMilliseconds()
        // Found, or being looked for; a miss is retried every 30 s (the printer can be busy just as a print starts).
        if ((key == job?.key && (job?.cover != null || now - jobMissAt < 30_000)) || key == jobLoading || (task.isEmpty() && gcode.isEmpty())) return
        jobLoading = key
        scope.launch {
            val found = runCatching { findJobFile(task, gcode) }.getOrNull()
            val plate = Regex("""plate_(\d+)\.gcode""").find(gcode)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            job = if (found == null) LanJob(key, null, null, emptyMap()) else {
                val (path, size) = found
                suspend fun entry(vararg names: String) = runCatching { zipEntry(size, names.map { Regex(it) }) { o, l -> storage.read(path, o, l) }?.second }.getOrNull()
                LanJob(key, entry("Metadata/plate_$plate\\.png", "Metadata/plate_\\d+\\.png"), entry("Metadata/pick_$plate\\.png"),
                    entry("Metadata/slice_info\\.config")?.decodeToString()?.let { sliceObjects(it, plate) }.orEmpty())
            }
            if (job?.cover == null) jobMissAt = Clock.System.now().toEpochMilliseconds()
            jobLoading = null
            onReport?.invoke()
        }
    }

    private suspend fun findJobFile(task: String, gcode: String): Pair<String, Long>? {
        val names = buildList {
            if (task.isNotEmpty()) { if (task.endsWith(".3mf")) add(task) else { add("$task.3mf"); add("$task.gcode.3mf") } }
            val g = gcode.substringAfterLast('/')
            if (g.isNotEmpty() && g != task && !g.startsWith("plate_")) { if (g.endsWith(".3mf")) add(g) else { add("$g.3mf"); add("$g.gcode.3mf") } }
        }
        val dirs = listOf("/cache/" to listCached("/cache"), "/" to listCached("/"))
        for (n in names) for ((d, files) in dirs) files.firstOrNull { it.name == n }?.let { return d + it.name to it.size }
        if (task.isEmpty()) dirs.flatMap { (d, f) -> f.filter { it.name.endsWith(".3mf") }.map { d + it.name to it } }.maxByOrNull { it.second.modified }?.let { return it.first to it.second.size }
        return null
    }

    /** Print files on the printer, as the integration's print_history API lists cached prints (newest first). */
    private suspend fun historyJson(): String {
        listings.clear() // a fresh look when the list is opened
        val files = listOf("" to listCached("/"), "cache/" to listCached("/cache")).flatMap { (d, l) -> l.filter { !it.dir && it.name.endsWith(".3mf") }.map { d to it } }
            .sortedByDescending { it.second.modified }
        return buildJsonObject {
            putJsonArray("files") {
                files.forEach { (d, f) ->
                    add(buildJsonObject {
                        put("filename", f.name); put("path", "${config.serial}/prints/$d${f.name}"); put("size", f.size); put("modified", f.modified)
                        put("thumbnail_path", "${config.serial}/prints/$d${f.name}.png")
                    })
                }
            }
        }.toString()
    }

    private suspend fun videosJson(): String {
        // Printers with no recorded timelapse may not create the directory yet.
        val files = try { storage.list("/timelapse") } catch (e: PrinterStorageException) {
            if (e.code == 550) emptyList() else throw e
        }.filter { !it.dir && it.name.endsWith(".mp4") }.sortedByDescending { it.modified }
        return buildJsonObject {
            putJsonArray("videos") {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("filename", f.name); put("path", "${config.serial}/timelapse/${f.name}"); put("size", f.size); put("modified", f.modified)
                        put("thumbnail_path", "${config.serial}/timelapse/thumbnail/${f.name.removeSuffix(".mp4")}.jpg")
                    })
                }
            }
        }.toString()
    }

    /** "SERIAL/prints/cache/x.3mf" → "/cache/x.3mf"; "SERIAL/timelapse/…" → "/timelapse/…". */
    private fun printerPath(cachePath: String): String {
        val rel = cachePath.substringAfter('/')
        return "/" + if (rel.startsWith("prints/")) rel.removePrefix("prints/") else rel
    }

    private val thumbs = mutableMapOf<String, ByteArray>()
    private val missing = mutableSetOf<String>()
    private val listings = mutableMapOf<String, Pair<Long, List<FileEntry>>>()

    /** Directory listings, reused for 30 s (a screen of thumbnails would otherwise list the folder once each). */
    private suspend fun listCached(dir: String): List<FileEntry> {
        val now = Clock.System.now().toEpochMilliseconds()
        listings[dir]?.takeIf { now - it.first < 30_000 }?.let { return it.second }
        return storage.list(dir).also { listings[dir] = now to it }
    }

    private suspend fun sizeOf(path: String) =
        listCached(path.substringBeforeLast('/').ifEmpty { "/" }).firstOrNull { it.name == path.substringAfterLast('/') }?.size
            ?: throw ApiException("Not on the printer any more")

    /** A file the screens ask Home Assistant's file cache for, read from the printer instead. */
    private suspend fun cachedFile(cachePath: String): ByteArray {
        thumbs[cachePath]?.let { return it }
        if (cachePath in missing) throw ApiException("No picture in that file")
        val out = when {
            cachePath.endsWith(".3mf.png") -> {
                val path = printerPath(cachePath.removeSuffix(".png"))
                zipEntry(sizeOf(path), listOf(Regex("Metadata/plate_1\\.png"), Regex("Metadata/plate_\\d+\\.png"), Regex("Metadata/.*\\.png"))) { o, l -> storage.read(path, o, l) }?.second
                    ?: run { missing += cachePath; throw ApiException("No picture in that file") } // files sliced on the command line have none
            }
            cachePath.endsWith(".3mf.slice_info.config") || cachePath.endsWith(".slice_info.config") -> {
                val path = printerPath(cachePath.removeSuffix(".slice_info.config") + if (cachePath.endsWith(".3mf.slice_info.config")) "" else ".3mf")
                zipEntry(sizeOf(path), listOf(Regex("Metadata/slice_info\\.config"))) { o, l -> storage.read(path, o, l) }?.second ?: throw ApiException("No slice info")
            }
            else -> storage.read(printerPath(cachePath))
        }
        if (out.size < 2_000_000) thumbs[cachePath] = out
        return out
    }

    /** A timelapse copied to the phone, for the player. */
    suspend fun localVideo(cachePath: String): String = cacheFile(cachePath.substringAfterLast('/'), storage.read(printerPath(cachePath)))

    /** Puts a print file on the printer ("/cache/name.3mf") for [print_project_file]. */
    suspend fun upload(name: String, data: ByteArray, progress: (Float) -> Unit) = storage.write("/cache/$name", data, progress)

    /** The printer's firmware version, from its get_version answer (the "ota" module). */
    private fun firmware(): String? = (report.o("info")?.get("module") as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it.str("name") == "ota" }?.str("sw_ver")

    /** Devices in the registry last handed out; when the printer reports a different set (an AMS appeared), the stream restarts. */
    private var registered = emptySet<String>()

    private fun registries(): Pair<JsonArray, JsonArray> {
        val ents = entities()
        rows = ents.map { it.first }
        registered = rows.map { it.device }.toSet()
        val devices = buildJsonArray {
            fun d(id: String, model: String, ident: String, via: String?) = add(buildJsonObject {
                put("id", "${config.serial}_$id"); put("name", if (via == null) config.model else model); put("model", model)
                put("via_device_id", via?.let { "${config.serial}_$it" }); put("name_by_user", JsonNull)
                put("sw_version", if (via == null) firmware() else null)
                putJsonArray("identifiers") { add(buildJsonArray { add(JsonPrimitive("bambu_lab")); add(JsonPrimitive(ident)) }) }
            })
            d("printer", config.model, config.serial, null)
            rows.map { it.device }.distinct().filter { it.startsWith("ams") }.forEach { amsDevice ->
                d(amsDevice, rows.firstOrNull { it.device == amsDevice && it.deviceModel.isNotBlank() }?.deviceModel ?: "AMS", "${config.serial}_$amsDevice", "printer")
            }
            if (rows.any { it.device == "ext" }) d("ext", "External Spool", "${config.serial}_ExternalSpool", "printer")
        }
        val registry = buildJsonArray {
            rows.forEach { r ->
                add(buildJsonObject {
                    put("entity_id", r.id); put("device_id", "${config.serial}_${r.device}"); put("platform", "bambu_lab")
                    put("translation_key", r.key); put("unique_id", "${config.serial}_${r.uid}"); put("disabled_by", JsonNull)
                })
            }
        }
        return devices to registry
    }

    /** Waits (up to 15 s) for the printer's first full report, so the registry lists the AMS and spools it has. */
    private val firstReport = CompletableDeferred<Unit>()
    private var running: Job? = null
    private var onReport: (() -> Unit)? = null

    private fun ensureRunning() {
        if (running?.isActive == true) return
        running = scope.launch {
            var wait = 1_000L
            while (isActive) {
                val l = linkFactory(config)
                link = l
                try {
                    l.run(onConnected = {
                        wait = 1_000L
                        publish(buildJsonObject { putJsonObject("pushing") { put("sequence_id", "0"); put("command", "pushall") } })
                        publish(buildJsonObject { putJsonObject("info") { put("sequence_id", "0"); put("command", "get_version") } })
                    }) { text ->
                        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@run
                        if (msg.o("print") == null && msg.o("info") == null) return@run
                        report = deepMerge(report, msg)
                        lastReport = Clock.System.now().toEpochMilliseconds()
                        if (msg.o("print")?.str("command") == "push_status") { firstReport.complete(Unit); checkJob() }
                        onReport?.invoke()
                    }
                } catch (e: Exception) {
                    if (!firstReport.isCompleted) firstReport.completeExceptionally(e)
                } finally { l.close(); link = null; onReport?.invoke() }
                delay(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        running?.cancel(); running = null
        camJob?.cancel(); camJob = null; frame = null
    }

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
        ensureRunning()
        return commands.map { c ->
            when (c.s("type")) {
                "config/device_registry/list" -> { awaitFirst(); registries().first }
                "config/entity_registry/list" -> { awaitFirst(); registries().second }
                "camera/stream" -> if (rtspCamera(config.model)) buildJsonObject { put("url", "rtsps://bblp:${config.code}@${config.ip}:322/streaming/live/1#${config.serial}") }
                    else throw ApiException("This printer's camera sends pictures, not video")
                else -> JsonNull
            }
        }
    }

    private suspend fun awaitFirst() {
        try { withTimeout(15_000) { firstReport.await() } }
        catch (e: Exception) {
            if (strict || e.message?.contains("access code") == true) throw ApiException("Couldn't reach the printer at ${config.ip}: ${e.message ?: "no answer"}. Check its IP address and access code, and that LAN mode (with Developer Mode) is on.")
        }
        if (strict && link == null) throw ApiException("The direct printer connection is unavailable")
    }

    override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) {
        ensureRunning()
        val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        onReport = { changed.trySend(Unit) }
        changed.trySend(Unit)
        try {
            while (true) {
                changed.receive()
                if (strict && link == null) throw ApiException("The direct printer connection dropped")
                val ents = entities()
                rows = ents.map { it.first }
                if (rows.map { it.device }.toSet() != registered) throw ApiException("The printer's parts changed") // the caller reconnects and re-reads the registry
                onEvent(buildJsonObject { putJsonObject("a") { ents.forEach { (_, e) -> put(e.id, buildJsonObject { put("s", e.state); put("a", e.attrs) }) } } })
                delay(300) // at most ~3 updates a second
            }
        } finally { onReport = null }
    }

    private fun publish(cmd: JsonObject) {
        val l = link ?: throw ApiException("Not connected to the printer")
        l.publish(cmd.toString())
    }

    private fun nextSeq() = "${++seq}"

    private fun printCmd(command: String, extra: JsonObject.() -> JsonObject = { this }) =
        buildJsonObject { put("print", JsonObject(buildJsonObject { put("sequence_id", nextSeq()); put("command", command) }.extra())) }

    private fun gcode(lines: String) = publish(printCmd("gcode_line") { JsonObject(this + ("param" to JsonPrimitive(lines))) })

    private fun rowOf(entityId: String?) = rows.firstOrNull { it.id == entityId }

    override suspend fun post(path: String, body: String): String {
        if (controlBlocked() && path.startsWith("/api/services/") && !path.endsWith("/update_entity")) throw ApiException(CONTROL_BLOCKED)
        if (path == "/api/bambu_lab/ensure_cache_file") return """{"status":"present"}""" // it's read from the printer, so it's there
        val d = json.parseToJsonElement(body).jsonObject
        val target = d.s("entity_id")
        when (val service = path.removePrefix("/api/services/")) {
            "light/turn_on", "light/turn_off" -> publish(buildJsonObject {
                putJsonObject("system") {
                    put("sequence_id", nextSeq()); put("command", "ledctrl"); put("led_node", "chamber_light")
                    put("led_mode", if (service.endsWith("on")) "on" else "off")
                    put("led_on_time", 500); put("led_off_time", 500); put("loop_times", 0); put("interval_time", 0)
                }
            })
            "number/set_value" -> {
                val v = d.s("value")?.toDoubleOrNull()?.toInt() ?: 0
                when (rowOf(target)?.key) {
                    "target_nozzle_temperature" -> gcode("M104 S$v\n")
                    "target_bed_temperature" -> gcode("M140 S$v\n")
                }
            }
            "select/select_option" -> {
                val lvl = when (d.s("option")) { "silent" -> 1; "sport" -> 3; "ludicrous" -> 4; else -> 2 }
                publish(printCmd("print_speed") { JsonObject(this + ("param" to JsonPrimitive("$lvl"))) })
            }
            "fan/set_percentage", "fan/turn_on", "fan/turn_off" -> {
                val pct = if (service == "fan/turn_off") 0 else d.s("percentage")?.toIntOrNull() ?: 100
                val fan = when (rowOf(target)?.key) { "aux_fan" -> 2; "chamber_fan" -> 3; else -> 1 }
                gcode("M106 P$fan S${(pct * 255 / 100.0).roundToInt()}\n")
            }
            "button/press" -> when (rowOf(target)?.key) {
                "pause" -> publish(printCmd("pause")); "resume" -> publish(printCmd("resume")); "stop" -> publish(printCmd("stop"))
            }
            "bambu_lab/skip_objects" -> {
                val ids = d.s("objects").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                publish(printCmd("skip_objects") { JsonObject(this + ("obj_list" to JsonArray(ids.map { JsonPrimitive(it) }))) })
            }
            "bambu_lab/move_axis" -> {
                val axis = d.s("axis") ?: "X"
                val mm = d.s("distance")?.toIntOrNull() ?: 0
                if (axis == "Home") gcode("G28\n")
                else gcode("M211 S\nM211 X1 Y1 Z1\nM1002 push_ref_mode\nG91 \nG1 $axis$mm.0 F${if (axis == "Z") 900 else 3000}\nM1002 pop_ref_mode\nM211 R\n")
            }
            "bambu_lab/extrude_retract" -> gcode("M83 \nG0 E${if (d.s("type") == "Retract") -10 else 10}.0 F900\n")
            "bambu_lab/unload_filament" -> publish(printCmd("ams_change_filament") {
                JsonObject(this + mapOf("ams_id" to JsonPrimitive(255), "slot_id" to JsonPrimitive(255), "target" to JsonPrimitive(255),
                    "curr_temp" to JsonPrimitive(0), "tar_temp" to JsonPrimitive(0)))
            })
            "bambu_lab/load_filament" -> {
                val r = rowOf(target) ?: throw ApiException("Unknown tray")
                val (ams, slot) = if (r.device == "ext") 254 to 0 else Regex("""AMS_(\d+)_tray_(\d+)""").find(r.uid)!!.destructured.let { (a, t) ->
                    val unit = r.device.removePrefix("ams").toIntOrNull() ?: throw ApiException("Unknown AMS")
                    val physicalSlot = t.toInt() - 1
                    val amsId = if (r.deviceModel.contains("HT", ignoreCase = true)) unit.takeIf { it >= 128 } ?: 128 else unit
                    amsId to physicalSlot
                }
                val global = if (ams == 254 || ams >= 128) ams else ams * 4 + slot
                publish(printCmd("ams_change_filament") {
                    JsonObject(this + mapOf("ams_id" to JsonPrimitive(ams), "slot_id" to JsonPrimitive(slot), "target" to JsonPrimitive(global),
                        "curr_temp" to JsonPrimitive(215), "tar_temp" to JsonPrimitive(215)))
                })
            }
            "bambu_lab/print_project_file" -> {
                val file = d.s("filepath") ?: throw ApiException("No file")
                val options = buildJsonObject {
                    put("param", "Metadata/plate_${d.s("plate") ?: "1"}.gcode")
                    put("url", if ("//" in file) file else "file:///sdcard/$file")
                    put("bed_type", d.s("bed_type") ?: "auto"); put("timelapse", d.s("timelapse") == "true")
                    put("file", file.substringAfterLast('/')); put("md5", ""); put("cfg", "0")
                    // Newer firmware reads these (the start G-code's g29_before_print_flag and extrude_cali_flag);
                    // older firmware reads bed_leveling and flow_cali. Both are sent.
                    put("bed_leveling", d.s("bed_leveling") != "false"); put("auto_bed_leveling", if (d.s("bed_leveling") != "false") 1 else 0)
                    put("flow_cali", d.s("flow_cali") == "true"); put("extrude_cali_flag", if (d.s("flow_cali") == "true") 1 else 0)
                    put("nozzle_offset_cali", 2); put("ams_mapping2", "")
                    put("vibration_cali", false); put("layer_inspect", true); put("use_ams", d.s("use_ams") == "true")
                    if (d.s("use_ams") == "true") putJsonArray("ams_mapping") { d.s("ams_mapping").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { add(JsonPrimitive(it)) } }
                    put("subtask_name", file.substringAfterLast('/').removeSuffix(".3mf").removeSuffix(".gcode"))
                    put("profile_id", "0"); put("project_id", "0"); put("subtask_id", "0"); put("task_id", "0")
                }
                publish(printCmd("project_file") {
                    JsonObject(this + options)
                })
            }
            "bambu_lab/calibration" -> publish(calibrationCommand(nextSeq(), d.s("option")?.toIntOrNull() ?: 0))
            "bambu_lab/xcam_control_set" -> publish(xcamCommand(nextSeq(), d.s("module_name").orEmpty(), d.s("enable") == "true", d.s("print_halt") == "true"))
            "bambu_lab/print_option" -> {
                val option = listOf("auto_recovery", "auto_switch_filament", "filament_tangle_detect", "nozzle_blob_detect", "air_print_detect", "sound_enable")
                    .firstOrNull { d[it] != null } ?: throw ApiException("No print option")
                val enabled = d[option].flagValue() ?: throw ApiException("Invalid print option")
                publish(printOptionCommand(nextSeq(), option, enabled))
            }
            "bambu_lab/ams_user_setting" -> publish(amsUserSettingCommand(nextSeq(), d.s("ams_id")?.toIntOrNull() ?: 0,
                d.s("startup_read_option") == "true", d.s("tray_read_option") == "true", d.s("calibrate_remain_flag") == "true"))
            "switch/turn_on", "switch/turn_off" -> {} // live view is always available directly
            "homeassistant/update_entity" -> publish(buildJsonObject { putJsonObject("pushing") { put("sequence_id", nextSeq()); put("command", "pushall") } })
            else -> throw ApiException("Needs Home Assistant")
        }
        return "[]"
    }

    override suspend fun get(path: String): String = when {
        path.startsWith("/api/bambu_lab/print_history") -> historyJson()
        path.startsWith("/api/bambu_lab/videos") -> videosJson()
        path.startsWith("/api/bambu_lab/file_cache/") -> cachedFile(path.removePrefix("/api/bambu_lab/file_cache/")).decodeToString()
        else -> throw ApiException(NEEDS_HA)
    }

    // ---------- Camera pictures (P1/A1): one stream kept open while someone is looking ----------

    private var frame: ByteArray? = null
    private var lastAsked = 0L
    private var camJob: Job? = null

    override suspend fun bytes(path: String): ByteArray {
        if (path.startsWith("/api/image_proxy/")) {
            val j = job ?: throw ApiException("No job picture")
            return (if ("pick_image" in path) j.pick else j.cover) ?: throw ApiException("No job picture")
        }
        if (path.startsWith("/api/bambu_lab/file_cache/")) return cachedFile(path.removePrefix("/api/bambu_lab/file_cache/"))
        if (!path.startsWith("/api/camera_proxy/")) throw ApiException(NEEDS_HA)
        lastAsked = Clock.System.now().toEpochMilliseconds()
        if (camJob?.isActive != true) camJob = scope.launch {
            val job = coroutineContext[Job]!!
            // Close the camera once nobody has asked for a picture for 15 s.
            launch { while (isActive) { delay(5_000); if (Clock.System.now().toEpochMilliseconds() - lastAsked > 15_000) job.cancel() } }
            while (isActive) {
                runCatching { jpegStream(config) { frame = it } }
                delay(2_000)
            }
        }
        repeat(50) { frame?.let { return it }; delay(200) }
        throw ApiException("The camera didn't send a picture")
    }

    private fun controlBlocked() = report.o("print")?.str("fun")?.toLongOrNull(16)?.let { it and 0x20000000L != 0L } ?: false

    companion object {
        const val NEEDS_HA = "This needs Home Assistant: Me › Settings › Home Assistant."
    }
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
