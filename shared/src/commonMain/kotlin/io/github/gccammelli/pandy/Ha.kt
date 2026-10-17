package io.github.gccammelli.pandy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Bambu Lab printers through the user's Home Assistant, which runs the ha-bambulab integration
 * (github.com/greghesp/ha-bambulab) in LAN or cloud mode. Pandy never talks to the printer or to Bambu's cloud for
 * printer data; MakerWorld browsing is the only part that goes to Bambu (see MakerWorld.kt).
 */

const val INTEGRATION_URL = "https://github.com/greghesp/ha-bambulab"
const val REPO_URL = "https://github.com/gccammelli/pandy"
const val DONATE_URL = "https://buymeacoffee.com/cammelli"
/** A setup guide in Pandy's repository ("docs/slicer.md"). */
fun guide(path: String) = "$REPO_URL/blob/main/$path"

class ApiException(message: String) : Exception(message)
class AuthException(message: String) : Exception(message)

val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray
private val EMPTY = JsonObject(emptyMap())

/** Home Assistant's API: real HTTP, or [FakeHomeAssistant] for the demo. */
interface HaBackend {
    val base: String
    suspend fun get(path: String): String
    suspend fun post(path: String, body: String): String
    /** Raw bytes (pictures through Home Assistant's image and camera proxies). */
    suspend fun bytes(path: String): ByteArray
    /** Websocket commands in one session. Returns each result. */
    suspend fun ws(commands: List<JsonObject>): List<JsonElement?>
    /**
     * Opens one websocket session, sends [subscribe] and hands every event message to [onEvent] until the connection
     * drops (then it returns or throws; the caller reconnects).
     */
    suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit)
}

class HaHttp(url: String, private val token: String) : HaBackend {
    override val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
    private val http = HttpClient {
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
        install(WebSockets) { pingIntervalMillis = 20_000 }
        expectSuccess = false
    }
    private val wsUrl get() = base.replaceFirst("http", "ws") + "/api/websocket"

    private suspend fun DefaultClientWebSocketSession.next() = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject

    private suspend fun DefaultClientWebSocketSession.auth() {
        next() // auth_required
        send(Frame.Text(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString()))
        if (next().s("type") != "auth_ok") throw AuthException("Home Assistant didn't accept the access token")
    }

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
        val out = mutableListOf<JsonElement?>()
        http.webSocket(wsUrl) {
            auth()
            commands.forEachIndexed { i, c ->
                send(Frame.Text(JsonObject(c + ("id" to JsonPrimitive(i + 1))).toString()))
                var m: JsonObject
                do m = next() while (m.s("id") != "${i + 1}")
                if (m.s("success") != "true") throw ApiException(m.o("error")?.s("message") ?: "Home Assistant refused ${c.s("type")}")
                out += m["result"]
            }
        }
        return out
    }

    override suspend fun stream(subscribe: JsonObject, onEvent: (JsonObject) -> Unit) {
        http.webSocket(wsUrl) {
            auth()
            send(Frame.Text(JsonObject(subscribe + ("id" to JsonPrimitive(1))).toString()))
            while (true) {
                val m = next()
                when (m.s("type")) {
                    "result" -> if (m.s("success") != "true") throw ApiException(m.o("error")?.s("message") ?: "Home Assistant refused the subscription")
                    "event" -> m.o("event")?.let(onEvent)
                }
            }
        }
    }

    override suspend fun get(path: String) = check(http.get(base + path) { header("Authorization", "Bearer $token") })
    override suspend fun post(path: String, body: String) = check(http.post(base + path) {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
    })

    override suspend fun bytes(path: String): ByteArray {
        val r = http.get(base + path) { header("Authorization", "Bearer $token") }
        if (r.status.value !in 200..299) { check(r) }
        return r.readRawBytes()
    }

    private suspend fun check(r: HttpResponse): String {
        val text = r.bodyAsText()
        when (r.status.value) {
            in 200..299 -> return text
            401, 403 -> throw AuthException("Home Assistant didn't accept the access token")
            else -> throw ApiException("Home Assistant: HTTP ${r.status.value} ${text.take(200)}")
        }
    }
}

// ---------- Model ----------

class Entity(val id: String, val state: String, val attrs: JsonObject) {
    val num get() = state.toDoubleOrNull()
    val on get() = state == "on"
    val ok get() = state != "unavailable" && state != "unknown"
}

/**
 * Where each entity of a printer lives: "domain.translation_key" ("sensor.nozzle_temp", "switch.camera") → entity id.
 * The integration's translation keys are the same on every install; entity ids are not (users rename them).
 */
data class PrinterInfo(val haId: String, val serial: String, val name: String, val model: String, val firmware: String?,
                       val ids: Map<String, String>, val ams: List<AmsInfo>, val external: List<String>, val externalDevice: String? = null)

/** One AMS: [trays] are the tray sensors' entity ids by slot (1–4; an AMS HT has one). */
data class AmsInfo(val haId: String, val index: Int, val model: String, val ids: Map<String, String>, val trays: Map<Int, String>, val protocolId: Int? = null)

/** A spool in an AMS slot or on the external holder, from the tray sensor's attributes. */
data class Spool(val entityId: String, val slot: Int, val name: String, val type: String, val color: Long, val colors: List<Long>,
                 val remain: Int, val active: Boolean, val empty: Boolean, val nozzleMin: Int?, val nozzleMax: Int?, val filamentId: String?) {
    val known get() = !empty && type.isNotBlank()
}

fun spoolFrom(e: Entity, slot: Int): Spool {
    val a = e.attrs
    return Spool(e.id, slot, a.s("name") ?: e.state, a.s("type").orEmpty(), hexColor(a.s("color")),
        a.a("cols")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::hexColor) }.orEmpty(),
        a.s("remain")?.toIntOrNull() ?: -1, a.s("active") == "true", a.s("empty") == "true" || e.state == "Empty",
        a.s("nozzle_temp_min")?.toIntOrNull(), a.s("nozzle_temp_max")?.toIntOrNull(), a.s("filament_id"))
}

/** "#RRGGBBAA" (the integration's format) or "#RRGGBB" → ARGB. Fully transparent spools show as light grey. */
fun hexColor(s: String?): Long {
    val h = s?.removePrefix("#") ?: return 0xFFDDDDDD
    val rgb = h.take(6).toLongOrNull(16) ?: return 0xFFDDDDDD
    val alpha = if (h.length >= 8) h.substring(6, 8).toLongOrNull(16) ?: 0xFF else 0xFF
    return if (alpha == 0L) 0xFFDDDDDD else (0xFF000000 or rgb)
}

/** Finds the Bambu Lab printers, their AMS units and external spools in the device and entity registries. */
fun printersFrom(devs: JsonArray, ents: JsonArray): List<PrinterInfo> {
    val all = devs.map { it.jsonObject }.filter { d -> d.a("identifiers")?.any { (it.jsonArray[0] as JsonPrimitive).content == "bambu_lab" } == true }
    val byDevice = ents.map { it.jsonObject }.filter { it.s("platform") == "bambu_lab" && it.s("disabled_by") == null }.groupBy { it.s("device_id") }
    fun keyed(id: String?) = byDevice[id].orEmpty().mapNotNull { r ->
        val eid = r.s("entity_id") ?: return@mapNotNull null
        val key = r.s("translation_key") ?: r.s("unique_id")?.substringAfterLast('_') ?: return@mapNotNull null
        "${eid.substringBefore('.')}.$key" to eid
    }.toMap()
    val printers = all.filter { it.s("via_device_id") == null }
    return printers.map { p ->
        val children = all.filter { it.s("via_device_id") == p.s("id") }
        var nextHtId = 128
        val ams = children.filter { c -> byDevice[c.s("id")].orEmpty().any { it.s("translation_key") == "tray" } }.mapIndexed { i, c ->
            val rows = byDevice[c.s("id")].orEmpty()
            val trays = rows.filter { it.s("translation_key") == "tray" }.mapNotNull { r ->
                val n = Regex("""tray_(\d+)$""").find(r.s("unique_id").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                n to r.s("entity_id")!!
            }.toMap()
            val protocolId = c.a("identifiers")?.mapNotNull { id ->
                val value = id.jsonArray.getOrNull(1) as? JsonPrimitive
                Regex("""(?i)(?:^|_)ams_?(\d+)$""").find(value?.contentOrNull.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            }?.firstOrNull()
            val model = c.s("model") ?: "AMS"
            val singleSlot = model.contains("HT", true) || model.contains("N3S", true) || protocolId?.let { it >= 128 } == true
            if (protocolId != null && protocolId >= 128) nextHtId = maxOf(nextHtId, protocolId + 1)
            AmsInfo(c.s("id")!!, i + 1, model, keyed(c.s("id")), trays, protocolId ?: if (singleSlot) nextHtId++ else null)
        }
        val extDev = children.firstOrNull { c -> byDevice[c.s("id")].orEmpty().any { it.s("translation_key") == "external_spool" } }?.s("id")
        val ext = children.flatMap { c -> byDevice[c.s("id")].orEmpty().filter { it.s("translation_key") == "external_spool" }.mapNotNull { it.s("entity_id") } }
        val serial = p.a("identifiers")!!.map { it.jsonArray }.first { (it[0] as JsonPrimitive).content == "bambu_lab" }[1].let { (it as JsonPrimitive).content }
        PrinterInfo(p.s("id")!!, serial, p.s("name_by_user") ?: p.s("name") ?: serial, p.s("model") ?: "", p.s("sw_version"), keyed(p.s("id")), ams, ext, extDev)
    }
}

/** A printer with its live entity states. */
class Printer(val info: PrinterInfo, private val states: Map<String, Entity>) {
    val serial get() = info.serial
    val model get() = info.model
    /** The entity for "domain.translation_key" ("sensor.nozzle_temp"). */
    operator fun get(key: String): Entity? = info.ids[key]?.let { states[it] }
    fun num(key: String) = get(key)?.num
    fun state(key: String) = get(key)?.state?.takeIf { it != "unavailable" && it != "unknown" }

    /** The name the user gave the printer on its screen (or in Home Assistant), as Handy shows it. */
    val name get() = state("sensor.printer_name") ?: info.name
    val online get() = get("binary_sensor.online")?.on ?: false
    /** "printing", "idle", "finish", "failed", "pause", "prepare", "offline"… (the integration's print_status). */
    val status get() = state("sensor.print_status") ?: if (online) "idle" else "offline"
    val printing get() = status in setOf("running", "printing", "pause", "prepare", "slicing")
    val paused get() = status == "pause"
    val progress get() = num("sensor.print_progress")?.toInt() ?: 0
    /** Minutes left. */
    val remaining get() = num("sensor.remaining_time")
    val layer get() = num("sensor.current_layer")?.toInt()
    val layers get() = num("sensor.total_layers")?.toInt()
    val task get() = state("sensor.subtask_name")
    val stage get() = state("sensor.stage")
    val lightOn get() = get("light.chamber_light")?.on ?: false
    val speed get() = state("select.printing_speed") ?: state("sensor.speed_profile")
    val nozzle get() = num("sensor.nozzle_temp")
    val nozzleTarget get() = num("sensor.target_nozzle_temp")
    val bed get() = num("sensor.bed_temp")
    val bedTarget get() = num("sensor.target_bed_temp")
    val chamber get() = num("sensor.chamber_temp")
    val chamberTarget get() = num("sensor.target_chamber_temp")
    val hasChamberHeater get() = info.ids.containsKey("number.target_chamber_temperature")
    val doorOpen get() = get("binary_sensor.door_open")?.on
    val camera get() = info.ids["camera.camera"]
    val coverImage get() = get("image.cover_image")?.let { "/api/image_proxy/${it.id}?v=${it.state}" }
    val pickImage get() = get("image.pick_image")?.let { "/api/image_proxy/${it.id}?v=${it.state}" }
    /** Objects on the plate: id → name (for Skip Parts). */
    val objects: Map<Int, String> get() = get("sensor.printable_objects")?.attrs?.o("objects")
        ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to ((v as? JsonPrimitive)?.contentOrNull ?: "") } }?.toMap().orEmpty()
    val skipped: List<Int> get() = get("sensor.skipped_objects")?.attrs?.a("objects")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty()

    val ams: List<Pair<AmsInfo, List<Spool?>>> get() = info.ams.map { a ->
        val n = if (a.isSingleSlot()) 1 else 4
        a to (1..n).map { slot -> a.trays[slot]?.let { states[it] }?.let { spoolFrom(it, slot) } }
    }
    val external: List<Spool> get() = info.external.mapIndexedNotNull { i, id -> states[id]?.let { spoolFrom(it, i) } }
    fun amsNum(a: AmsInfo, key: String) = a.ids[key]?.let { states[it] }?.num
    /** A boolean setting exposed by an AMS; null means this firmware/integration did not report it. */
    fun amsFlag(a: AmsInfo, key: String): Boolean? = a.ids["switch.$key"]?.let { id -> states[id]?.flag }
    fun flag(key: String): Boolean? = get("switch.$key")?.flag
    fun flagAttribute(key: String, attribute: String): Boolean? = get("switch.$key")?.attrs?.s(attribute)?.let(::parseFlag)

    /** Fans as Handy names them → (fan entity, speed %). */
    val fans: List<Triple<String, String?, Int>> get() = listOfNotNull(
        Triple("Part", info.ids["fan.cooling_fan"], num("sensor.cooling_fan_speed")?.toInt() ?: 0),
        info.ids["fan.aux_fan"]?.let { Triple("Aux", it, num("sensor.aux_fan_speed")?.toInt() ?: 0) },
        info.ids["fan.chamber_fan"]?.let { Triple("Chamber", it, num("sensor.chamber_fan_speed")?.toInt() ?: 0) },
    )
    val fansOn get() = fans.count { it.third > 0 }

    /** Health Management System errors: (code, message) from the integration's attributes. */
    val hms: List<Pair<String, String>> get() {
        val a = get("binary_sensor.hms_errors")?.attrs ?: return emptyList()
        val n = a.s("Count")?.toIntOrNull() ?: 0
        return (1..n).mapNotNull { i -> a.s("$i-Error")?.let { (a.s("$i-Code") ?: "") to it } }
    }
    val printError get() = get("binary_sensor.print_error")?.let { if (it.on) it.attrs.s("error") ?: "Print error" else null }

    /** Every entity id of this printer (and its AMS and spools), to subscribe to. */
    val entityIds get() = info.ids.values + info.ams.flatMap { it.ids.values + it.trays.values } + info.external
}

private val Entity.flag: Boolean?
    get() = parseFlag(state)

private fun parseFlag(value: String?): Boolean? = when (value?.lowercase()) {
    "on", "true", "1", "enable", "enabled" -> true
    "off", "false", "0", "disable", "disabled" -> false
    else -> null
}

/** Applies one subscribe_entities event ("a" added, "c" changed, "r" removed) to [states]. */
fun applyEntityEvent(states: MutableMap<String, Entity>, ev: JsonObject) {
    ev.o("a")?.forEach { (id, v) ->
        val o = v.jsonObject
        states[id] = Entity(id, o.s("s") ?: "", o.o("a") ?: EMPTY)
    }
    ev.o("c")?.forEach { (id, v) ->
        val old = states[id] ?: Entity(id, "", EMPTY)
        val plus = v.jsonObject.o("+")
        val minus = v.jsonObject.o("-")?.a("a")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        val attrs = JsonObject(old.attrs.filterKeys { it !in minus } + (plus?.o("a") ?: EMPTY))
        states[id] = Entity(id, plus?.s("s") ?: old.state, attrs)
    }
    ev.a("r")?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(states::remove) }
}

// ---------- Actions ----------

suspend fun HaBackend.call(domain: String, service: String, data: JsonObject) {
    post("/api/services/$domain/$service", data.toString())
}

suspend fun HaBackend.bambu(service: String, p: Printer, data: JsonObject = EMPTY) =
    call("bambu_lab", service, JsonObject(data + ("device_id" to JsonPrimitive(p.info.haId))))

/** Presses one of the printer's buttons ("pause", "resume", "stop"). */
suspend fun HaBackend.press(p: Printer, key: String) {
    val id = p.info.ids["button.$key"] ?: throw ApiException("${p.name} has no \"$key\" button in Home Assistant")
    call("button", "press", buildJsonObject { put("entity_id", id) })
}

suspend fun HaBackend.setNumber(id: String, value: Double) =
    call("number", "set_value", buildJsonObject { put("entity_id", id); put("value", value) })

/** Starts the printer's machine calibration; this command is available in direct LAN mode only. */
fun Printer.calibrate(option: Int, err: (String) -> Unit) = Repo.act(err) {
    bambu("calibration", this@calibrate, buildJsonObject { put("option", option) })
}

/** Enables one of the printer's XCam modules, when the printer reports it. */
fun Printer.setXcam(module: String, enabled: Boolean, printHalt: Boolean = false, err: (String) -> Unit) = Repo.act(err) {
    bambu("xcam_control_set", this@setXcam, buildJsonObject {
        put("module_name", module); put("control", enabled); put("enable", enabled); put("print_halt", printHalt)
    })
}

/** Changes one of the firmware print-option flags, such as automatic step-loss recovery. */
fun Printer.setPrintOption(option: String, enabled: Boolean, err: (String) -> Unit) = Repo.act(err) {
    bambu("print_option", this@setPrintOption, buildJsonObject {
        put(option, enabled)
        if (option == "auto_recovery") put("option", if (enabled) 1 else 0)
    })
}

/** Updates all three AMS read/remaining-filament preferences in one firmware command. */
fun Printer.setAmsSettings(amsId: Int, startup: Boolean, insertion: Boolean, remaining: Boolean, err: (String) -> Unit) = Repo.act(err) {
    bambu("ams_user_setting", this@setAmsSettings, buildJsonObject {
        put("ams_id", amsId); put("startup_read_option", startup); put("tray_read_option", insertion); put("calibrate_remain_flag", remaining)
    })
}

/** An HLS address for a camera, from Home Assistant's own streaming (valid while it's being watched). */
suspend fun HaBackend.hlsUrl(cameraId: String): String {
    val r = ws(listOf(buildJsonObject { put("type", "camera/stream"); put("entity_id", cameraId); put("format", "hls") })).first() as? JsonObject
    val path = r?.s("url") ?: throw ApiException("Home Assistant gave no stream for $cameraId")
    return if ("://" in path) path else base + path
}

/** A still from the camera, as JPEG (Home Assistant's camera proxy). */
suspend fun HaBackend.snapshot(cameraId: String): ByteArray = bytes("/api/camera_proxy/$cameraId")

// ---------- Files (the integration's file cache: prints it saw and timelapses it downloaded) ----------

/**
 * A file in the integration's cache. [path] is "SERIAL/prints/cache/123-name.3mf" (or ".../timelapse/x.mp4");
 * [thumb] the picture next to it, if any. [modified] is ISO local time.
 */
data class CachedFile(val name: String, val path: String, val size: Long, val modified: String, val thumb: String?) {
    /** Download address inside Home Assistant. */
    val url get() = "/api/bambu_lab/file_cache/$path"
    val thumbUrl get() = thumb?.let { "/api/bambu_lab/file_cache/$it" }
    /** The name without the integration's "<size>-" prefix and extensions. */
    val title get() = name.removePrefix("$size-").removeSuffix(".3mf").removeSuffix(".gcode").removeSuffix(".mp4")
    /** Where it lives on the printer's storage, as print_project_file wants it ("cache/123-name.3mf"). */
    val printerPath get() = path.substringAfter("/prints/", path)
}

fun parseFiles(text: String, key: String): List<CachedFile> =
    json.parseToJsonElement(text).jsonObject.a(key).orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        CachedFile(o.s("filename") ?: return@mapNotNull null, o.s("path") ?: return@mapNotNull null, o.s("size")?.toLongOrNull() ?: 0,
            o.s("modified").orEmpty(), o.s("thumbnail_path"))
    }

/** Prints the integration has cached for [serial], newest first (empty if its file cache is off). */
suspend fun HaBackend.printHistory(serial: String) = parseFiles(get("/api/bambu_lab/print_history?serial=$serial"), "files")

suspend fun HaBackend.timelapses(serial: String) = parseFiles(get("/api/bambu_lab/videos?serial=$serial"), "videos")

/** Plate number and filaments ("type", "color", "id") of a cached print, from the slice info saved next to it. */
data class SliceInfo(val plate: Int, val filaments: List<Map<String, String>>)

suspend fun HaBackend.sliceInfo(f: CachedFile): SliceInfo? = runCatching {
    val xml = get("/api/bambu_lab/file_cache/" + f.path.removeSuffix(".3mf") + ".slice_info.config")
    val plate = Regex("""key="index"\s+value="(\d+)"""").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    val fil = Regex("""<filament\s+([^>]*)/?>""").findAll(xml).map { m ->
        Regex("""(\w+)="([^"]*)"""").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
    }.toList()
    SliceInfo(plate, fil)
}.getOrNull()

/**
 * Makes sure a cached print is on the printer's storage (the integration copies it back if it's gone) and returns
 * its path there, as print_project_file wants it.
 */
suspend fun HaBackend.ensureOnPrinter(serial: String, f: CachedFile): String {
    val r = post("/api/bambu_lab/ensure_cache_file", buildJsonObject { put("serial", serial); put("cache_path", f.path); put("expected_size", f.size) }.toString())
    val st = runCatching { json.parseToJsonElement(r).jsonObject }.getOrNull()
    return if (st?.s("status") == "use_legacy_path") st.s("detail")!!.trimStart('/') else f.printerPath
}

/** Starts a print file that is on the printer. [amsMapping]: the AMS tray (0-based, AMS 1 = 0–3) for each filament. */
suspend fun HaBackend.startPrint(p: Printer, path: String, plate: Int, amsMapping: List<Int>?, timelapse: Boolean, bedLeveling: Boolean, flowCali: Boolean, bedType: String = "auto") =
    bambu("print_project_file", p, buildJsonObject {
        put("filepath", path); put("plate", plate); put("timelapse", timelapse); put("bed_leveling", bedLeveling)
        put("flow_cali", flowCali); if (this@startPrint is DirectPrinter) put("bed_type", bedType); put("vibration_cali", false); put("layer_inspect", true)
        val useAms = amsMapping?.any(::isAmsTrayId) == true
        put("use_ams", useAms)
        if (useAms) put("ams_mapping", amsMapping!!.joinToString(","))
    })

/** The bed type names used by the printer's project-file command. */
fun buildPlateCode(label: String) = when (label) {
    "Textured PEI Plate" -> "textured_plate"
    "Smooth PEI Plate / High Temp Plate", "Smooth PEI Plate", "High Temp Plate" -> "hot_plate"
    "Cool Plate SuperTack" -> "supertack_plate"
    "Engineering Plate" -> "eng_plate"
    "Cool Plate" -> "cool_plate"
    else -> "auto"
}

/** The Handy label for a Bambu Studio plate value found in a 3MF's plate metadata. */
fun buildPlateLabel(value: String?) = when (value?.lowercase()) {
    "textured pei plate", "textured_plate" -> "Textured PEI Plate"
    "high temp plate", "hot plate", "hot_plate" -> "Smooth PEI Plate / High Temp Plate"
    "supertack plate", "supertack_plate" -> "Cool Plate SuperTack"
    "engineering plate", "eng_plate", "engineering_plate" -> "Engineering Plate"
    "cool plate", "cool_plate" -> "Cool Plate"
    else -> null
}

/** Stable key for a model filament; presets must never be reused solely because two models have the same slot number. */
fun filamentKey(type: String, colour: String) = "${type.trim().uppercase()}|${hexColor(colour).toString(16)}"

/** The absolute tray ID used by the printer protocol: standard AMS 0–103, AMS HT 128–135. */
fun AmsInfo.isSingleSlot() = model.contains("HT", ignoreCase = true) || model.contains("N3S", ignoreCase = true) || protocolId?.let { it >= 128 } == true

/** The protocol's AMS unit ID; unlike [index], HT units start at 128. */
fun AmsInfo.amsId(): Int = protocolId?.takeIf { !isSingleSlot() || it >= 128 } ?: if (isSingleSlot()) 128 else index - 1

fun AmsInfo.trayId(slot: Int): Int {
    require(slot in 0 until if (isSingleSlot()) 1 else 4)
    return if (isSingleSlot()) amsId() else amsId() * 4 + slot
}

/** Returns this AMS's physical slot for an absolute tray ID, or null when it belongs elsewhere. */
fun AmsInfo.slotForTrayId(id: Int): Int? {
    return if (isSingleSlot()) id.takeIf { it == amsId() }?.let { 0 }
    else id.takeIf { it in amsId() * 4 until (amsId() + 1) * 4 }?.minus(amsId() * 4)
}

fun isAmsTrayId(id: Int) = id in 0..103 || id in 128..135

/** The currently loaded absolute tray IDs (plus 254 for the external spool). */
fun Printer.loadedTrayIds(): Set<Int> = ams.flatMap { (a, spools) ->
    spools.mapIndexedNotNull { i, s -> s?.takeIf { it.known }?.let { a.trayId(i) } }
}.toSet() + external.filter { it.known }.map { 254 }

/** For each filament of a print, the loaded tray that matches best: same type and nearest colour; null if none fits. */
fun autoMapping(p: Printer, filaments: List<Map<String, String>>): List<Int>? {
    val trays = p.ams.flatMap { (a, spools) -> spools.mapIndexedNotNull { i, s -> s?.takeIf { it.known }?.let { a.trayId(i) to it } } } +
        p.external.filter { it.known }.map { 254 to it }
    if (trays.isEmpty() || filaments.isEmpty()) return null
    val mapped = filaments.map { f ->
        val want = hexColor(f["color"])
        val sameType = trays.filter { it.second.type.equals(f["type"], ignoreCase = true) }
        sameType.minByOrNull { colorDistance(it.second.color, want) }?.first ?: -1
    }
    return mapped.takeIf { it.all { slot -> slot >= 0 } }
}

fun colorDistance(a: Long, b: Long): Int {
    fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toInt()
    return listOf(16, 8, 0).sumOf { s -> (ch(a, s) - ch(b, s)).let { it * it } }
}
