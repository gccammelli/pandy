package io.github.gccammelli.pandy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The plugin protocol never contains printer credentials or model download URLs. */
fun phoneSliceRequest(plate: Int, name: String, printer: String, bed: String, filamentType: String?, filamentColor: String?, settings: Map<String, String>): String =
    buildJsonObject {
        put("version", 1); put("plate", plate); put("name", name); put("printer", printer); put("bed_type", bed)
        filamentType?.let { put("filament_type", it) }; filamentColor?.let { put("filament_color", it) }
        put("settings", JsonObject(settings.mapValues { JsonPrimitive(it.value) }))
    }.toString()

fun phoneSettings(response: PhoneSlicerResponse): List<SlicerSetting> = parseSlicerSettings(phoneObject(response.metadata))

/** Reject ambiguous or incomplete results before any printer upload or command. */
suspend fun phoneSliceResult(response: PhoneSlicerResponse, requested: Map<String, String>, sourcePlate: Int, printer: String, bed: String): Pair<ByteArray, SlicedPlate> {
    val o = phoneObject(response.metadata)
    if (o.s("version") != "1" || !settingsConfirmed(o, requested)) throw ApiException("The slicer plugin did not confirm the slicer settings. Update the plugin and try again.")
    if (o.s("source_plate") != sourcePlate.toString() || o.s("printer") != printer || o.s("bed_type") != bed || o.s("project_preserved") != "true")
        throw ApiException("The slicer plugin did not confirm the model, printer and build plate. Review the print and try again.")
    val file = response.file?.takeIf { it.size in 1..100_000_000 } ?: throw ApiException("The slicer plugin returned no sliced file.")
    val plate = o.s("output_plate")?.toIntOrNull()?.takeIf { it > 0 } ?: throw ApiException("The slicer plugin returned no output plate.")
    val filaments = (o["filaments"] as? kotlinx.serialization.json.JsonArray)?.map { item ->
        val f = item as? JsonObject ?: throw ApiException("The slicer plugin returned invalid filament information.")
        val id = f.s("id")?.toIntOrNull()?.takeIf { it in 1..16 } ?: throw ApiException("The slicer plugin returned invalid filament information.")
        val type = f.s("type")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: throw ApiException("The slicer plugin returned invalid filament information.")
        val color = f.s("color")?.takeIf { it.matches(Regex("#?[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) } ?: throw ApiException("The slicer plugin returned invalid filament information.")
        mapOf("id" to id.toString(), "type" to type, "color" to color)
    } ?: throw ApiException("The slicer plugin returned no filament information.")
    if (filaments.isEmpty() || filaments.map { it["id"] }.distinct().size != filaments.size) throw ApiException("The slicer plugin returned invalid filament information.")
    val gcode = zipEntry(file.size.toLong(), listOf(Regex("Metadata/plate_${plate}\\.gcode")), 100_000_000) { offset, length ->
        file.copyOfRange(offset.toInt(), offset.toInt() + length)
    }?.second
    if (gcode == null || gcode.isEmpty()) throw ApiException("The slicer plugin returned a file without the selected sliced plate.")
    if (io.github.gccammelli.pandy.studio.slicer.Safety.problems(gcode.decodeToString()).isNotEmpty())
        throw ApiException("The sliced file failed its printer safety check.")
    return file to SlicedPlate(plate, 0, 0.0, filaments)
}

private fun phoneObject(text: String): JsonObject = runCatching { require(text.length <= 128_000); json.parseToJsonElement(text) as JsonObject }
    .getOrElse { throw ApiException("The slicer plugin returned an invalid response.") }
