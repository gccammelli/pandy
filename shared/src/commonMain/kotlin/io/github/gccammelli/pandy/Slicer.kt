package io.github.gccammelli.pandy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * Printing MakerWorld models: the model's 3MF project is sliced by Pandy Slicer (Bambu Studio's command line in a
 * container: slicer/ in this repository), which also puts the result on the printer; then Pandy starts it like any
 * other print. See docs/slicer.md.
 */

const val SLICER_PORT = 8737

private val http = HttpClient {
    install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 20 * 60_000; socketTimeoutMillis = 20 * 60_000 }
    expectSuccess = false
}

/** One plate of a sliced file, as Pandy Slicer reports it. */
data class SlicedPlate(val index: Int, val seconds: Int, val grams: Double, val filaments: List<Map<String, String>>)

class SlicerClient(url: String, private val token: String?) {
    val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }.let { if (Regex(""":\d+$""").containsMatchIn(it.substringAfter("://"))) it else "$it:$SLICER_PORT" }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    /** Bambu Studio's version, if the slicer answers. */
    suspend fun health(): String {
        val r = http.get("$base/health") { auth() }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
        if (r.status.value == 401) throw ApiException("The slicer wants a different token")
        if (r.status.value != 200 || o == null) throw ApiException("No Pandy Slicer at $base")
        return o.s("version") ?: "?"
    }

    /** Slices [plate] of a 3MF and puts it on the printer [to]; returns its path there and the plate's details. */
    suspend fun sliceAndUpload(data: ByteArray, plate: Int, name: String, to: LanConfig, printer: String? = null): Pair<String, SlicedPlate?> {
        val target = printer?.let { "&printer=${it.encodeURLParam()}" }.orEmpty()
        val r = http.post("$base/slice?plate=$plate&upload=1&name=${name.encodeURLParam()}$target") {
            auth()
            header("X-Printer-Ip", to.ip); header("X-Printer-Serial", to.serial); header("X-Printer-Code", to.code)
            setBody(data)
        }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
        if (r.status.value != 200 || o == null) throw ApiException(o?.s("error") ?: "The slicer answered HTTP ${r.status.value}")
        val p = (o["plates"] as? JsonArray)?.firstOrNull() as? JsonObject
        return (o.s("path") ?: throw ApiException("The slicer didn't say where it put the file")) to p?.let {
            SlicedPlate(it.s("index")?.toIntOrNull() ?: plate, it.s("seconds")?.toIntOrNull() ?: 0, it.s("grams")?.toDoubleOrNull() ?: 0.0,
                (it["filaments"] as? JsonArray).orEmpty().mapNotNull { f -> (f as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() } })
        }
    }
}

/** Percent-encodes a query value (UTF-8; letters, digits and -_.~ stay). */
fun String.encodeURLParam(): String = encodeToByteArray().joinToString("") { b ->
    val c = b.toInt().toChar()
    if (b >= 0 && (c.isLetterOrDigit() || c in "-_.~")) "$c" else "%" + (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}
private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

/** Fetches a MakerWorld file (with the web view's cookies, for files behind the sign-in). */
suspend fun download(url: String, cookies: String?): ByteArray {
    val r = http.get(url) { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
    if (r.status.value !in 200..299) throw ApiException("The download failed (HTTP ${r.status.value})")
    return r.readRawBytes()
}

// ---------- 3MF projects ----------

/** A plate of a project: [index] as Bambu Studio numbers them, its name and picture if the project has them. */
data class ProjectPlate(val index: Int, val name: String, val picture: ByteArray?)

/** What Pandy shows before printing a project: its plates, the printer it was made for, its filaments. */
data class Project(val plates: List<ProjectPlate>, val printerModel: String?, val filaments: List<Pair<String, String>>, val sliced: Boolean)

private suspend fun ByteArray.entry(vararg names: String) =
    zipEntry(size.toLong(), names.map { Regex(it) }) { o, l -> copyOfRange(o.toInt(), (o + l).toInt().coerceAtMost(size)) }?.second

suspend fun readProject(data: ByteArray): Project {
    if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) throw ApiException("That download isn't a 3MF project")
    val settings = data.entry("Metadata/project_settings\\.config")?.decodeToString()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    fun list(k: String) = (settings?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val types = list("filament_type")
    val colours = list("filament_colour")
    val model = data.entry("Metadata/model_settings\\.config")?.decodeToString().orEmpty()
    val plateIds = model.split("<plate>").drop(1).mapNotNull { p ->
        val meta = Regex("""key="([^"]+)"\s+value="([^"]*)"""").findAll(p).associate { it.groupValues[1] to it.groupValues[2] }
        meta["plater_id"]?.toIntOrNull()?.let { it to meta["plater_name"].orEmpty() }
    }.ifEmpty { listOf(1 to "") }
    val plates = plateIds.map { (i, n) -> ProjectPlate(i, n, data.entry("Metadata/plate_${i}\\.png")) }
    return Project(plates, (settings?.get("printer_model") as? JsonPrimitive)?.contentOrNull, types.mapIndexed { i, t -> t to (colours.getOrNull(i) ?: "#888888") },
        data.entry("Metadata/plate_\\d+\\.gcode\\.md5", "Metadata/plate_\\d+\\.gcode") != null)
}

/**
 * The Bambu Studio printer preset for a printer ("Bambu Lab X1 Carbon 0.4 nozzle"), so the slicer can re-target a
 * profile made for another printer, as Bambu Studio's printer switch does.
 */
fun printerPreset(model: String, nozzle: String?): String {
    val base = when (model) { "X1C" -> "X1 Carbon"; else -> model }
    val n = nozzle?.toDoubleOrNull()?.let { if (it == it.toInt().toDouble()) "$it" else it.toString() } ?: "0.4"
    return "Bambu Lab $base $n nozzle"
}

/** Bambu's printer names in project files ("Bambu Lab X1 Carbon") → the short names Pandy uses ("X1C"). */
fun shortModel(projectModel: String?): String? = when (projectModel?.removePrefix("Bambu Lab ")?.trim()) {
    null -> null
    "X1 Carbon" -> "X1C"; "X1" -> "X1"; "X1E" -> "X1E"; "P1S" -> "P1S"; "P1P" -> "P1P"; "P2S" -> "P2S"
    "A1" -> "A1"; "A1 mini" -> "A1 mini"; "H2D" -> "H2D"; "H2S" -> "H2S"
    else -> projectModel.removePrefix("Bambu Lab ")
}

/**
 * The print command's ams_mapping: one entry per filament slot of the project (by id), the AMS tray for the ones the
 * plate uses, -1 for the rest.
 */
fun amsMappingFor(p: Printer, used: List<Map<String, String>>): List<Int>? {
    if (p.ams.isEmpty() || used.isEmpty()) return null
    val trays = autoMapping(p, used) ?: return null
    val ids = used.map { it["id"]?.toIntOrNull() ?: 1 }
    return List(ids.max()) { slot -> ids.indexOf(slot + 1).let { if (it < 0) -1 else trays[it] } }
}

