package io.github.gccammelli.pandy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/*
 * Printing MakerWorld models: the model's 3MF project is sliced by Pandy Slicer (Bambu Studio's command line in a
 * container: slicer/ in this repository), which also puts the result on the printer; then Pandy starts it like any
 * other print. See docs/slicer.md.
 */

const val SLICER_PORT = 8737
private const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024

private val slicerHttp = HttpClient {
    install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 20 * 60_000; socketTimeoutMillis = 20 * 60_000 }
    expectSuccess = false
}.localOnlyCleartext()

/** One plate of a sliced file, as Pandy Slicer reports it. */
data class SlicedPlate(val index: Int, val seconds: Int, val grams: Double, val filaments: List<Map<String, String>>)

/**
 * Pandy Slicer, reached at [url] (Docker, or any slicer with an address), or through Home Assistant [ha]: its add-on's
 * ingress path on Home Assistant's own address, so Pandy needs only its Home Assistant connection.
 */
class SlicerClient(
    url: String?, private val token: String?, private val ha: HaBackend? = null,
    private val http: HttpClient = slicerHttp,
    private val storageFactory: (LanConfig) -> PrinterStorage = ::printerStorage,
) {
    val base = url?.trim()?.trimEnd('/')?.let { if ("://" in it) it else "http://$it" }?.let { if (Regex(""":\d+$""").containsMatchIn(it.substringAfter("://"))) it else "$it:$SLICER_PORT" }
    val viaHa get() = ha != null && base == null

    /** Where to send requests, and the ingress session cookie when it's through Home Assistant. */
    private suspend fun target(): Pair<String, String?> = ha?.takeIf { base == null }?.slicerIngress() ?: ((base ?: throw ApiException("Set up Pandy Slicer in Me › Settings")) to null)

    private fun io.ktor.client.request.HttpRequestBuilder.auth(cookie: String?) {
        if (cookie != null) header("Cookie", "ingress_session=$cookie")
        else if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
    }

    /** Bambu Studio's version, if the slicer answers. */
    suspend fun health(): String {
        val (at, cookie) = target()
        val r = http.get("$at/health") { auth(cookie) }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
        if (r.status.value == 401) throw ApiException(if (cookie != null) "Home Assistant didn't let Pandy in to the slicer" else "The slicer wants a different token")
        if (r.status.value != 200 || o == null) throw ApiException(if (cookie != null) "The Pandy Slicer add-on didn't answer: is it started?" else "No Pandy Slicer at $base")
        return o.s("version") ?: "?"
    }

    /**
     * Slices [plate] of a 3MF and puts it on the printer [to]; returns its path there and the plate's details. The
     * printer's access code goes to the slicer only through Home Assistant over HTTPS; otherwise Pandy downloads the
     * sliced file and sends it to the printer itself, over FTPS.
     */
    suspend fun sliceAndUpload(data: ByteArray, plate: Int, name: String, to: LanConfig, printer: String? = null, bedType: String = "auto"): Pair<String, SlicedPlate?> {
        val printerParam = printer?.let { "&printer=${it.encodeURLParam()}" }.orEmpty()
        val bed = "&bed_type=${bedType.encodeURLParam()}"
        val (at, cookie) = target()
        val query = "plate=$plate&name=${name.encodeURLParam()}$printerParam$bed"
        if (cookie == null || !at.startsWith("https://", ignoreCase = true)) {
            val r = http.post("$at/slice?$query") { auth(cookie); setBody(data) }
            if (r.status.value != 200) {
                val o = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
                throw ApiException(o?.s("error") ?: "The slicer answered HTTP ${r.status.value}")
            }
            val sliced = r.readRawBytes()
            val info = runCatching { json.parseToJsonElement(r.headers["X-Pandy-Result"].orEmpty()).jsonObject }.getOrNull()
            val file = slicedFileName(r.headers[HttpHeaders.ContentDisposition])
            storageFactory(to).write("/cache/$file", sliced)
            return "cache/$file" to info?.let { firstPlate(it, plate) }
        }
        val r = http.post("$at/slice?$query&upload=1") {
            auth(cookie)
            header("X-Printer-Ip", to.ip); header("X-Printer-Serial", to.serial); header("X-Printer-Code", to.code)
            setBody(data)
        }
        val o = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
        if (r.status.value != 200 || o == null) throw ApiException(o?.s("error") ?: "The slicer answered HTTP ${r.status.value}")
        return (o.s("path") ?: throw ApiException("The slicer didn't say where it put the file")) to firstPlate(o, plate)
    }

    private fun firstPlate(o: JsonObject, plate: Int): SlicedPlate? = ((o["plates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let {
        SlicedPlate(it.s("index")?.toIntOrNull() ?: plate, it.s("seconds")?.toIntOrNull() ?: 0, it.s("grams")?.toDoubleOrNull() ?: 0.0,
            (it["filaments"] as? JsonArray).orEmpty().mapNotNull { f -> (f as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() } })
    }
}

/** The sliced file's name from the slicer's Content-Disposition, reduced to a plain name for the printer's /cache. */
fun slicedFileName(disposition: String?): String {
    val raw = disposition?.let { Regex("filename=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.orEmpty()
    val name = raw.substringAfterLast('/').substringAfterLast('\\').filter { it.isLetterOrDigit() || it in " ._()+-" }.trim()
    return if (name.endsWith(".gcode.3mf") && !name.startsWith(".")) name else "model.gcode.3mf"
}

/** The Pandy Slicer add-on's address through Home Assistant's ingress, and a fresh ingress session for it. */
suspend fun HaBackend.slicerIngress(): Pair<String, String> {
    fun supervisor(endpoint: String, method: String) = buildJsonObject { put("type", "supervisor/api"); put("endpoint", endpoint); put("method", method) }
    val addons = try { ws(listOf(supervisor("/addons", "get"))).first() as? JsonObject }
        catch (e: ApiException) { throw ApiException("Pandy's Home Assistant user must be an administrator to use the slicer add-on (${e.message})") }
    val slug = (addons?.a("addons")).orEmpty().mapNotNull { (it as? JsonObject)?.s("slug") }.firstOrNull { it == "pandy_slicer" || it.endsWith("_pandy_slicer") }
        ?: throw ApiException("Install the Pandy Slicer add-on in Home Assistant")
    val (info, session) = ws(listOf(supervisor("/addons/$slug/info", "get"), supervisor("/ingress/session", "post"))).map { it as? JsonObject }
    if (info?.s("state") != "started") throw ApiException("Start the Pandy Slicer add-on in Home Assistant")
    val entry = info.s("ingress_entry") ?: throw ApiException("Update the Pandy Slicer add-on in Home Assistant")
    return base + entry to (session?.s("session") ?: throw ApiException("Home Assistant didn't open a session for the slicer"))
}

/** Percent-encodes a query value (UTF-8; letters, digits and -_.~ stay). */
fun String.encodeURLParam(): String = encodeToByteArray().joinToString("") { b ->
    val c = b.toInt().toChar()
    if (b >= 0 && (c.isLetterOrDigit() || c in "-_.~")) "$c" else "%" + (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}
private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

/** Fetches a MakerWorld file (with the web view's cookies, for files behind the sign-in). */
suspend fun download(url: String, cookies: String?): ByteArray {
    return slicerHttp.prepareGet(url) { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }.execute { r ->
        if (r.status.value !in 200..299) throw ApiException("The download failed (HTTP ${r.status.value})")
        r.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > MAX_DOWNLOAD_BYTES }?.let {
            throw ApiException("The download is too large (64 MiB maximum)")
        }
        val channel = r.bodyAsChannel()
        val result = GrowingBytes()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n == -1) break
            if (n == 0) continue
            if (total > MAX_DOWNLOAD_BYTES - n) throw ApiException("The download is too large (64 MiB maximum)")
            result.append(buffer, n)
            total += n
        }
        result.toByteArray()
    }
}

private class GrowingBytes {
    private var bytes = ByteArray(64 * 1024)
    private var size = 0

    fun append(source: ByteArray, count: Int) {
        if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
        source.copyInto(bytes, size, 0, count)
        size += count
    }

    fun toByteArray() = if (size == bytes.size) bytes else bytes.copyOf(size)
}

// ---------- 3MF projects ----------

/** A plate of a project: [index] as Bambu Studio numbers them, its name and picture if the project has them. */
data class ProjectPlate(val index: Int, val name: String, val picture: ByteArray?, val seconds: Int? = null, val grams: Double? = null, val parts: Int? = null, val bedType: String? = null)

/** What Pandy shows before printing a project: its plates, the printer it was made for, its filaments. */
data class PreviewVertex(val x: Float, val y: Float, val z: Float)
data class PreviewTriangle(val a: PreviewVertex, val b: PreviewVertex, val c: PreviewVertex, val material: Int? = null)
data class PreviewMesh(val triangles: List<PreviewTriangle>)

data class Project(val plates: List<ProjectPlate>, val printerModel: String?, val filaments: List<Pair<String, String>>, val sliced: Boolean,
                   val profileName: String? = null, val profileSummary: String? = null, val mesh: PreviewMesh? = null,
                   val meshes: Map<Int, PreviewMesh> = emptyMap())

private suspend fun ByteArray.entry(vararg names: String) =
    zipEntry(size.toLong(), names.map { Regex(it) }) { o, l -> copyOfRange(o.toInt(), (o + l).toInt().coerceAtMost(size)) }?.second

private suspend fun ByteArray.boundedEntry(maxSize: Long, vararg names: String) =
    zipEntry(size.toLong(), names.map { Regex(it) }, maxSize) { o, l -> copyOfRange(o.toInt(), (o + l).toInt().coerceAtMost(size)) }?.second

suspend fun readProject(data: ByteArray): Project {
    if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) throw ApiException("That download isn't a 3MF project")
    val settings = data.entry("Metadata/project_settings\\.config")?.decodeToString()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    fun list(k: String) = (settings?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val types = list("filament_type")
    val colours = list("filament_colour")
    val model = data.entry("Metadata/model_settings\\.config")?.decodeToString().orEmpty()
    val defaultBed = settings?.s("curr_bed_type")
    val plateIds = XML_PLATE.findAll(model).mapNotNull { match ->
        val meta = xmlMetadata(match.groupValues[1])
        meta["plater_id"]?.toIntOrNull()?.let { Triple(it, meta["plater_name"].orEmpty(), meta["curr_bed_type"] ?: meta["bed_type"] ?: defaultBed) }
    }.toList().ifEmpty { listOf(Triple(1, "", null)) }
    val sliceInfo = data.entry("Metadata/slice_info\\.config")?.decodeToString()
    val plates = mutableListOf<ProjectPlate>()
    for ((i, n, bedType) in plateIds) {
        val metrics = sliceInfo?.let { sliceInfoMetrics(it, i) } ?: plateMetrics(data.entry("Metadata/plate_${i}\\.gcode"))
        plates += ProjectPlate(i, n, data.entry("Metadata/plate_${i}\\.png"), metrics.seconds, metrics.grams, metrics.parts, bedType)
    }
    val profileSummary = listOfNotNull(
        settings?.s("layer_height")?.toDoubleOrNull()?.let { "${decimal(it)} mm layer" },
        settings?.s("wall_loops")?.toIntOrNull()?.let { "$it walls" },
        settings?.s("sparse_infill_density")?.let { density(it) }
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")
    val model3d = data.boundedEntry(MAX_PREVIEW_ENTRY_BYTES, Regex.escape(ROOT_MODEL_PATH))?.decodeToString()
    val modelSources = mutableMapOf(ROOT_MODEL_PATH to model3d?.let(::xmlObjects).orEmpty())
    model3d?.let(::componentPaths).orEmpty().forEach { path ->
        data.boundedEntry(MAX_PREVIEW_ENTRY_BYTES, Regex.escape(path))?.decodeToString()?.let { modelSources[path] = xmlObjects(it) }
    }
    val objectMaterials = objectExtruders(model)
    val meshes = model3d?.let { runCatching { previewMeshes(it, modelSources, plateObjectIds(model), objectMaterials) }.getOrDefault(emptyMap()) }.orEmpty()
    val mesh = meshes[plateIds.firstOrNull()?.first] ?: meshes.values.firstOrNull()
    return Project(plates, (settings?.get("printer_model") as? JsonPrimitive)?.contentOrNull, types.mapIndexed { i, t -> t to (colours.getOrNull(i) ?: "#888888") },
        sliceInfo != null || data.entry("Metadata/plate_\\d+\\.gcode\\.md5") != null,
        settings?.s("profile_name")?.takeIf { it.isNotBlank() }, profileSummary, mesh, meshes)
}

private const val MAX_PREVIEW_TRIANGLES = 20_000
private const val MAX_PREVIEW_ENTRY_BYTES = 8L * 1024 * 1024
private const val ROOT_MODEL_PATH = "3D/3dmodel.model"
private val XML_OBJECT = Regex("""(?s)<object\b([^>]*)>(.*?)</object>""")
private val XML_MESH = Regex("""(?s)<mesh\b[^>]*>(.*?)</mesh>""")
private val XML_VERTEX = Regex("""<vertex\b([^>]*)/?>""")
private val XML_TRIANGLE = Regex("""<triangle\b([^>]*)/?>""")
private val XML_COMPONENT = Regex("""<component\b([^>]*)/?>""")
private val XML_ITEM = Regex("""<item\b([^>]*)/?>""")
private val XML_METADATA = Regex("""<metadata\b([^>]*)/?>""")
private val XML_PLATE = Regex("""(?s)<plate\b[^>]*>(.*?)</plate>""")
private val WHITESPACE = Regex("""\s+""")

private fun xmlAttr(text: String, name: String): String? {
    var from = 0
    while (true) {
        val at = text.indexOf(name, from)
        if (at < 0) return null
        val before = text.getOrNull(at - 1)
        val equals = at + name.length
        if ((before == null || before.isWhitespace()) && text.getOrNull(equals) == '=') {
            val quote = text.getOrNull(equals + 1)
            if (quote == '"' || quote == '\'') {
                val start = equals + 2
                val end = text.indexOf(quote, start)
                if (end >= 0) return text.substring(start, end)
            }
        }
        from = at + name.length
    }
}

private fun xmlMetadata(body: String): Map<String, String> = XML_METADATA.findAll(body).mapNotNull { match ->
    val attrs = match.groupValues[1]
    val key = xmlAttr(attrs, "key") ?: return@mapNotNull null
    key to (xmlAttr(attrs, "value") ?: "")
}.toMap()

private fun xmlObjects(xml: String): Map<Int, String> = XML_OBJECT.findAll(xml).mapNotNull { match ->
    xmlAttr(match.groupValues[1], "id")?.toIntOrNull()?.let { it to match.groupValues[2] }
}.toMap()

private fun componentPaths(xml: String): List<String> = XML_COMPONENT.findAll(xml).mapNotNull { match ->
    xmlAttr(match.groupValues[1], "p:path")?.trim()?.removePrefix("/")
}.distinct().toList()

private fun objectExtruders(xml: String): Map<Int, Int> = XML_OBJECT.findAll(xml).mapNotNull { match ->
    val id = xmlAttr(match.groupValues[1], "id")?.toIntOrNull() ?: return@mapNotNull null
    val extruder = xmlMetadata(match.groupValues[2].substringBefore("<part"))["extruder"]?.toIntOrNull()?.minus(1) ?: return@mapNotNull null
    id to extruder
}.toMap()

private fun plateObjectIds(xml: String): Map<Int, Set<Int>> = XML_PLATE.findAll(xml).mapNotNull { match ->
    val meta = xmlMetadata(match.groupValues[1])
    val plate = meta["plater_id"]?.toIntOrNull() ?: return@mapNotNull null
    val objects = Regex("""(?s)<model_instance\b[^>]*>(.*?)</model_instance>""").findAll(match.groupValues[1]).flatMap { instance ->
        xmlMetadata(instance.groupValues[1])["object_id"]?.toIntOrNull()?.let { sequenceOf(it) } ?: emptySequence()
    }.toSet()
    if (objects.isEmpty()) return@mapNotNull null
    plate to objects
}.toMap()

private fun identityTransform() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

private fun parseTransform(raw: String?): FloatArray {
    val values = raw?.trim()?.split(WHITESPACE)?.mapNotNull(String::toFloatOrNull)
    return if (values?.size == 12) floatArrayOf(
        values[0], values[3], values[6], values[1], values[4], values[7], values[2], values[5], values[8], values[9], values[10], values[11]
    ) else identityTransform()
}

private fun multiply(parent: FloatArray, local: FloatArray): FloatArray {
    val result = FloatArray(12)
    for (row in 0..2) for (column in 0..2) {
        result[row * 3 + column] = parent[row * 3] * local[column] + parent[row * 3 + 1] * local[3 + column] + parent[row * 3 + 2] * local[6 + column]
    }
    for (row in 0..2) result[9 + row] = parent[row * 3] * local[9] + parent[row * 3 + 1] * local[10] + parent[row * 3 + 2] * local[11] + parent[9 + row]
    return result
}

private fun applyTransform(v: PreviewVertex, t: FloatArray) = PreviewVertex(
    t[0] * v.x + t[1] * v.y + t[2] * v.z + t[9],
    t[3] * v.x + t[4] * v.y + t[5] * v.z + t[10],
    t[6] * v.x + t[7] * v.y + t[8] * v.z + t[11],
)

private fun appendMesh(body: String, transform: FloatArray, out: MutableList<PreviewTriangle>, defaultMaterial: Int? = null) {
    val vertices = XML_VERTEX.findAll(body).mapNotNull { match ->
        val attrs = match.groupValues[1]
        PreviewVertex(xmlAttr(attrs, "x")?.toFloatOrNull() ?: return@mapNotNull null,
            xmlAttr(attrs, "y")?.toFloatOrNull() ?: return@mapNotNull null,
            xmlAttr(attrs, "z")?.toFloatOrNull() ?: return@mapNotNull null)
    }.toList()
    for (match in XML_TRIANGLE.findAll(body)) {
        if (out.size >= MAX_PREVIEW_TRIANGLES) break
        val attrs = match.groupValues[1]
        val a = xmlAttr(attrs, "v1")?.toIntOrNull()?.let(vertices::getOrNull)
        val b = xmlAttr(attrs, "v2")?.toIntOrNull()?.let(vertices::getOrNull)
        val c = xmlAttr(attrs, "v3")?.toIntOrNull()?.let(vertices::getOrNull)
        if (a != null && b != null && c != null) out += PreviewTriangle(applyTransform(a, transform), applyTransform(b, transform), applyTransform(c, transform),
            xmlAttr(attrs, "p1")?.toIntOrNull() ?: defaultMaterial)
    }
}

private fun appendObject(id: Int, source: String, transform: FloatArray, sources: Map<String, Map<Int, String>>, out: MutableList<PreviewTriangle>, objectMaterials: Map<Int, Int>, defaultMaterial: Int? = null, depth: Int = 0) {
    if (depth > 8 || out.size >= MAX_PREVIEW_TRIANGLES) return
    val body = sources[source]?.get(id) ?: return
    val ownMaterial = objectMaterials[id]?.takeIf { source == ROOT_MODEL_PATH }
    val mesh = XML_MESH.find(body)?.groupValues?.get(1)
    if (mesh != null) {
        appendMesh(mesh, transform, out, ownMaterial ?: defaultMaterial)
        return
    }
    for (component in XML_COMPONENT.findAll(body)) {
        if (out.size >= MAX_PREVIEW_TRIANGLES) break
        val attrs = component.groupValues[1]
        val child = xmlAttr(attrs, "objectid")?.toIntOrNull() ?: continue
        val path = xmlAttr(attrs, "p:path")?.trim()?.removePrefix("/") ?: source
        appendObject(child, path, multiply(transform, parseTransform(xmlAttr(attrs, "transform"))), sources, out, objectMaterials, ownMaterial ?: defaultMaterial, depth + 1)
    }
}

private fun previewMeshes(root: String, sources: Map<String, Map<Int, String>>, objectIds: Map<Int, Set<Int>>, objectMaterials: Map<Int, Int> = emptyMap()): Map<Int, PreviewMesh> {
    val buildItems = XML_ITEM.findAll(root).mapNotNull { match ->
        val attrs = match.groupValues[1]
        xmlAttr(attrs, "objectid")?.toIntOrNull()?.let { it to parseTransform(xmlAttr(attrs, "transform")) }
    }.toList()
    val targets = objectIds.ifEmpty { mapOf(1 to emptySet()) }
    return targets.mapNotNull { (plate, wanted) ->
        val triangles = mutableListOf<PreviewTriangle>()
        val candidates = buildItems.filter { wanted.isEmpty() || it.first in wanted }.ifEmpty { buildItems }
        if (candidates.isNotEmpty()) candidates.forEach { (id, transform) -> appendObject(id, ROOT_MODEL_PATH, transform, sources, triangles, objectMaterials) }
        else sources[ROOT_MODEL_PATH].orEmpty().keys.forEach { id -> appendObject(id, ROOT_MODEL_PATH, identityTransform(), sources, triangles, objectMaterials) }
        triangles.takeIf { it.isNotEmpty() }?.let { plate to PreviewMesh(it) }
    }.toMap()
}

/** Extracts a small, dependency-free preview mesh from an inline or component-based 3MF model. */
fun previewMesh(xml: String): PreviewMesh? {
    val sources = mapOf(ROOT_MODEL_PATH to xmlObjects(xml))
    return previewMeshes(xml, sources, mapOf(1 to emptySet()))[1]
}

private data class PlateMetrics(val seconds: Int?, val grams: Double?, val parts: Int?)

private fun sliceInfoMetrics(xml: String, plate: Int): PlateMetrics? {
    val block = XML_PLATE.findAll(xml).firstOrNull { xmlMetadata(it.groupValues[1])["index"]?.toIntOrNull() == plate }?.groupValues?.get(1) ?: return null
    val metadata = xmlMetadata(block)
    val parts = Regex("""<object\b([^>]*)/?>""").findAll(block).count { xmlAttr(it.groupValues[1], "skipped") != "true" }
    return PlateMetrics(metadata["prediction"]?.toIntOrNull(), metadata["weight"]?.toDoubleOrNull(), parts.takeIf { it > 0 })
}

private fun plateMetrics(bytes: ByteArray?): PlateMetrics {
    val text = bytes?.decodeToString() ?: return PlateMetrics(null, null, null)
    val time = Regex("""(?i)(?:total\s+)?estimated(?:\s+printing)?\s*time[^0-9]*(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?""").find(text)
    val colonTime = Regex("""(?i)(?:total\s+)?estimated(?:\s+printing)?\s*time[^0-9]*(\d+):(\d+):(\d+)""").find(text)
    val hms = time?.takeIf { it.groupValues.drop(1).any(String::isNotEmpty) }
    val seconds = hms?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 3600 + (it.groupValues[2].toIntOrNull() ?: 0) * 60 + (it.groupValues[3].toIntOrNull() ?: 0) }
        ?: colonTime?.let { it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt() }
    val grams = Regex("""(?i)(?:total\s+)?filament\s+used\s*\[g]\s*[=:]\s*([0-9.]+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    val parts = Regex("""(?i)(?:total\s+number\s+of\s+objects|number\s+of\s+objects)\s*[=:]\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return PlateMetrics(seconds, grams, parts)
}

private fun decimal(value: Double) = ((value * 100).roundToInt() / 100.0).toString().removeSuffix(".0")

private fun density(value: String): String? {
    val n = value.removeSuffix("%").toDoubleOrNull() ?: return null
    return "${if (n <= 1) (n * 100).roundToInt() else n.roundToInt()}% infill"
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
