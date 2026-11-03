package io.github.gccammelli.pandy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SlicerChoice(val value: String, val label: String)

data class SlicerSetting(
    val group: String, val key: String, val label: String, val type: String, val unit: String = "",
    val min: Double? = null, val max: Double? = null, val options: List<SlicerChoice> = emptyList(),
) {
    fun valid(value: String): Boolean = when (type) {
        "choice" -> options.any { it.value == value }
        "integer", "number" -> {
            val pattern = if (type == "integer") Regex("""[0-9]+""") else Regex("""(?:[0-9]+(?:\.[0-9]{1,3})?|\.[0-9]{1,3})""")
            val n = value.toDoubleOrNull()
            n != null && value.length <= 32 && pattern.matches(value) && min != null && max != null && n in min..max
        }
        else -> false
    }

    fun display(value: String): String = if (type == "choice") options.firstOrNull { it.value == value }?.label ?: value
        else value + if (unit.isEmpty()) "" else " $unit"
}

internal fun parseSlicerSettings(root: JsonObject): List<SlicerSetting> {
    fun invalid() = ApiException("Pandy Slicer returned unsupported settings. Update the slicer and try again.")
    fun text(value: String?, max: Int = 80) = value?.takeIf { it.isNotBlank() && it.length <= max && it.none { c -> c == '\n' || c == '\r' } } ?: throw invalid()
    if (root.s("version") != "1") throw invalid()
    val items = root["settings"] as? JsonArray ?: throw invalid()
    if (items.isEmpty() || items.size > 100) throw invalid()
    val settings = items.map { item ->
        val o = item as? JsonObject ?: throw invalid()
        val key = text(o.s("key"), 64).takeIf { it.matches(Regex("""[a-z0-9_]+""")) } ?: throw invalid()
        val type = o.s("type")?.takeIf { it in setOf("number", "integer", "choice") } ?: throw invalid()
        val unit = o.s("unit").orEmpty().takeIf { it.length <= 12 } ?: throw invalid()
        val min = o.s("min")?.toDoubleOrNull()
        val max = o.s("max")?.toDoubleOrNull()
        val options = if (type == "choice") {
            val list = o["options"] as? JsonArray ?: throw invalid()
            if (list.isEmpty() || list.size > 30) throw invalid()
            list.map { option ->
                val choice = option as? JsonObject ?: throw invalid()
                SlicerChoice(text(choice.s("value"), 32), text(choice.s("label")))
            }.also { if (it.map { c -> c.value }.distinct().size != it.size) throw invalid() }
        } else {
            if (min == null || max == null || !min.isFinite() || !max.isFinite() || min < 0 || min > max) throw invalid()
            emptyList()
        }
        SlicerSetting(text(o.s("group")), key, text(o.s("label")), type, unit, min, max, options)
    }
    if (settings.map { it.key }.distinct().size != settings.size) throw invalid()
    return settings
}

internal fun settingsConfirmed(info: JsonObject?, requested: Map<String, String>): Boolean {
    if (requested.isEmpty()) return true
    val applied = info?.o("applied_settings") ?: return false
    return applied.size == requested.size && requested.all { (key, value) ->
        (applied[key] as? JsonPrimitive)?.contentOrNull == value
    }
}
