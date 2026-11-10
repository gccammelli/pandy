// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One slice request: the plate (a plain 3MF), Bambu system presets, and the user's changes to them. [files] and
 * [arguments] are exactly what the equivalence tests give both the official Bambu Studio and the engine
 * (equivalence/pandy_equiv/cases.py `materialise`): user presets that inherit the system ones, and the CLI line.
 */
data class SliceJob(
    val model3mf: ByteArray,
    val printer: String,
    val process: String,
    val filaments: List<String>,
    val processOverrides: Map<String, Setting> = emptyMap(),
    val filamentOverrides: Map<String, Setting> = emptyMap(),
    /** The printer preset's include templates (PresetLibrary.includes): its start and end G-code. */
    val printerIncludes: Map<String, Setting> = emptyMap(),
) {
    /** File name -> contents, written into an empty job directory. */
    fun files(): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>("model.3mf" to model3mf)
        out["machine.json"] = json(JsonObject(buildJsonObject {
            put("type", "machine"); put("name", "case printer"); put("from", "User"); put("inherits", printer)
            put("printer_settings_id", "case printer")
        } + printerIncludes.mapValues { it.value.json }))
        out["process.json"] = json(JsonObject(buildJsonObject {
            put("type", "process"); put("name", "case process"); put("from", "User"); put("inherits", process)
            put("print_settings_id", "case process"); put("compatible_printers", JsonArray(listOf(JsonPrimitive(printer))))
        } + processOverrides.filterKeys { it !in BLOCKED_OPTIONS }.mapValues { it.value.json }))
        filaments.forEachIndexed { i, f ->
            val name = "case filament ${i + 1}"
            out["filament${i + 1}.json"] = json(JsonObject(buildJsonObject {
                put("type", "filament"); put("name", name); put("from", "User"); put("inherits", f)
                put("filament_settings_id", JsonArray(listOf(JsonPrimitive(name))))
                put("compatible_printers", JsonArray(listOf(JsonPrimitive(printer))))
            } + filamentOverrides.filterKeys { it !in BLOCKED_OPTIONS }.mapValues { it.value.json }))
        }
        return out
    }

    /** The CLI does not reliably resolve system inheritance for externally loaded presets. */
    fun resolvedFiles(library: PresetLibrary): Map<String, ByteArray> {
        require((processOverrides.keys + filamentOverrides.keys + printerIncludes.keys).none { it in BLOCKED_OPTIONS }) {
            "Blocked slicer option in job."
        }
        val originals = files()
        fun merge(file: String, preset: String): ByteArray {
            val values = requireNotNull(library.preset(preset)) { "Missing slicer preset: $preset" }.values
                .filterKeys { it !in BLOCKED_OPTIONS }.mapValues { it.value.json }
            val overrides = Json.parseToJsonElement(originals.getValue(file).decodeToString()) as JsonObject
            return json(JsonObject(values + overrides.filterKeys { it !in BLOCKED_OPTIONS }))
        }
        return originals.toMutableMap().apply {
            put("machine.json", merge("machine.json", printer))
            put("process.json", merge("process.json", process))
            filaments.forEachIndexed { i, name -> put("filament${i + 1}.json", merge("filament${i + 1}.json", name)) }
        }
    }

    /** The slicer's arguments, relative to the job directory; the runner appends --outputdir. */
    fun arguments(): List<String> = listOf(
        "--load-settings", "machine.json;process.json",
        "--load-filaments", filaments.indices.joinToString(";") { "filament${it + 1}.json" },
        "--slice", "0", "--export-3mf", OUTPUT, "model.3mf",
    )

    override fun equals(other: Any?) = other is SliceJob && other.model3mf.contentEquals(model3mf) && other.printer == printer &&
        other.process == process && other.filaments == filaments && other.processOverrides == processOverrides &&
        other.filamentOverrides == filamentOverrides
    override fun hashCode() = model3mf.contentHashCode() * 31 + printer.hashCode()

    companion object {
        const val OUTPUT = "out.gcode.3mf"
        private val pretty = Json { prettyPrint = true }
        private fun json(o: JsonObject) = pretty.encodeToString(JsonObject.serializer(), o).encodeToByteArray()
    }
}

/**
 * Options the app never lets a user set: they make the slicer run programs (post-processing scripts) or name files.
 * Neither the settings screen nor a job carries them, so the G-code is always the slicer's own.
 */
val BLOCKED_OPTIONS = setOf("post_process", "filename_format", "gcode_substitutions")

/** A setting value as Bambu presets store it: a string, or a list of strings (one per filament or extruder). */
sealed class Setting {
    abstract val json: JsonElement
    data class One(val value: String) : Setting() { override val json get() = JsonPrimitive(value) }
    data class Many(val values: List<String>) : Setting() { override val json get() = JsonArray(values.map(::JsonPrimitive)) }
    val first get() = when (this) { is One -> value; is Many -> values.firstOrNull() ?: "" }
}
