// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads files of the engine's resources directory (resources/profiles/...), relative paths. */
fun interface ProfileSource {
    fun read(path: String): String?
}

/** A system preset as Bambu Studio lists it. */
data class Preset(val name: String, val type: String, val values: Map<String, Setting>) {
    operator fun get(key: String): Setting? = values[key]
    fun string(key: String) = values[key]?.first
}

/** A printer the app can slice for: its 0.2-0.8 mm nozzle presets share one model. */
data class PrinterModel(val model: String, val nozzles: List<Preset>) {
    val displayName get() = model.removePrefix("Bambu Lab ")
}

/**
 * Bambu's system presets (resources/profiles/BBL), read the way Bambu Studio reads them: the vendor index BBL.json
 * lists every preset file; a preset's "inherits" parent is applied first, then its "include" templates, then its own
 * keys. Resolved values are used for preset selection and supplied explicitly to the engine when slicing.
 */
class PresetLibrary(private val source: ProfileSource, vendor: String = "BBL") {
    private val json = Json { ignoreUnknownKeys = true }
    private val raw = mutableMapOf<String, JsonObject>()
    private val resolved = mutableMapOf<String, Preset>()
    private val resolving = mutableSetOf<String>()
    private val paths = mutableMapOf<String, String>()
    private val index: Map<String, List<String>>

    init {
        val idx = json.parseToJsonElement(source.read("profiles/$vendor.json") ?: error("No $vendor profiles")).jsonObject
        index = listOf("machine_list", "process_list", "filament_list").associateWith { list ->
            idx[list]?.jsonArray?.mapNotNull { e ->
                val o = e.jsonObject
                val name = requireNotNull(o["name"]?.jsonPrimitive?.content) { "Preset index entry has no name" }
                val path = requireNotNull(o["sub_path"]?.jsonPrimitive?.content) { "Preset index entry has no path" }
                require(name !in paths) { "Duplicate preset: $name" }
                require(!path.startsWith('/') && path.split('/').none { it == ".." }) { "Invalid preset path" }
                paths[name] = "profiles/$vendor/$path"
                name
            } ?: emptyList()
        }
    }

    private fun rawOf(name: String): JsonObject? = raw.getOrPut(name) {
        val text = paths[name]?.let(source::read) ?: return null
        json.parseToJsonElement(text).jsonObject.also { require(it["name"]?.jsonPrimitive?.content == name) { "Preset name mismatch: $name" } }
    }

    /** The preset [name] with inheritance applied, or null when Bambu has no such preset. */
    fun preset(name: String): Preset? = resolved[name] ?: run {
        val own = rawOf(name) ?: return null
        require(resolving.add(name)) { "Cyclic preset inheritance: $name" }
        try {
            val values = linkedMapOf<String, Setting>()
            own["inherits"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let { parent ->
                values.putAll(requireNotNull(preset(parent)) { "Missing inherited preset: $parent" }.values)
            }
            own["include"]?.let { inc -> (inc as? JsonArray)?.forEach { i ->
                val included = i.jsonPrimitive.content
                values.putAll(requireNotNull(preset(included)) { "Missing included preset: $included" }.values)
            } }
            for ((k, v) in own) if (k !in META) values[k] = v.toSetting()
            Preset(name, own["type"]?.jsonPrimitive?.content ?: "", values).also { resolved[name] = it }
        } finally { resolving.remove(name) }
    }

    /**
     * The keys a printer preset gets from its "include" templates (start, end, filament-change and layer-change
     * G-code), through its inheritance chain. Bambu Studio's CLI does not merge them into a user preset that inherits
     * the system one, so the job's printer preset carries them (SliceJob, and equivalence/pandy_equiv/profiles.py).
     */
    fun includes(name: String): Map<String, Setting> = includes(name, mutableSetOf())

    private fun includes(name: String, visiting: MutableSet<String>): Map<String, Setting> {
        val own = rawOf(name) ?: return emptyMap()
        require(visiting.add(name)) { "Cyclic preset inheritance: $name" }
        val out = linkedMapOf<String, Setting>()
        try {
            own["inherits"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                requireNotNull(rawOf(it)) { "Missing inherited preset: $it" }
                out.putAll(includes(it, visiting))
            }
            (own["include"] as? JsonArray)?.forEach { i ->
                val included = i.jsonPrimitive.content
                out.putAll(requireNotNull(preset(included)) { "Missing included preset: $included" }.values)
            }
            return out
        } finally { visiting.remove(name) }
    }

    private fun instantiable(list: String) = index[list].orEmpty().filter { rawOf(it)?.get("instantiation")?.jsonPrimitive?.content == "true" }

    /** Bambu Lab printers, grouped by model, nozzles sorted by size. */
    fun printers(): List<PrinterModel> = instantiable("machine_list").mapNotNull(::preset)
        .groupBy { it.string("printer_model") ?: it.name.substringBefore(" 0.") }
        .map { (model, presets) -> PrinterModel(model, presets.sortedBy { it.string("nozzle_diameter")?.toDoubleOrNull() ?: 0.0 }) }
        .sortedBy { it.model }

    /** Process presets made for [printer] (a printer preset name), in Bambu's order (layer height). */
    fun processes(printer: String) = instantiable("process_list").filter { compatible(it, printer) }.mapNotNull(::preset)
        .sortedBy { it.string("layer_height")?.toDoubleOrNull() ?: 0.0 }

    /** Filament presets made for [printer]. */
    fun filaments(printer: String) = instantiable("filament_list").filter { compatible(it, printer) }.mapNotNull(::preset)

    private fun compatible(name: String, printer: String): Boolean {
        val list = rawOf(name)?.get("compatible_printers") as? JsonArray ?: return false
        return list.any { it.jsonPrimitive.content == printer }
    }

    companion object {
        private val META = setOf("name", "inherits", "from", "instantiation", "setting_id", "type", "include", "renamed_from")

        private fun JsonElement.toSetting(): Setting = when (this) {
            is JsonArray -> Setting.Many(map { (it as? JsonPrimitive)?.content ?: it.toString() })
            is JsonPrimitive -> Setting.One(content)
            else -> Setting.One(toString())
        }
    }
}
