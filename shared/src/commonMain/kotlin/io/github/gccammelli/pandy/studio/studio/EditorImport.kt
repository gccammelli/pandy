// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.io.Zip

/** The editor currently imports geometry only and cannot preserve Bambu project metadata. */
fun requirePlainEditorModel(bytes: ByteArray) {
    val entries = Zip.read(bytes).keys
    val unsupported = setOf("metadata/project_settings.config", "metadata/model_settings.config", "metadata/slic3r_pe_model.config",
        "metadata/layer_config_ranges.xml", "metadata/layer_heights_profile.txt")
    require(entries.none { it.lowercase() in unsupported }) {
        "This project contains plate or profile settings that the editor cannot preserve yet."
    }
    require(entries.any { it.endsWith(".model", ignoreCase = true) }) { "This file has no 3MF model." }
}

/**
 * The objects (build item object ids) a project puts on [plate], from its plate settings; null when the project names
 * no plates, so every object is taken.
 */
fun projectPlateObjects(bytes: ByteArray, plate: Int): Set<String>? {
    val config = Zip.read(bytes).entries.firstOrNull { it.key.equals("Metadata/model_settings.config", ignoreCase = true) }?.value?.decodeToString() ?: return null
    fun meta(block: String, key: String) = Regex("""<metadata\s+key="$key"\s+value="([^"]*)"""").findAll(block).map { it.groupValues[1] }.toList()
    val plates = Regex("""<plate>(.*?)</plate>""", RegexOption.DOT_MATCHES_ALL).findAll(config).map { it.groupValues[1] }.toList()
    if (plates.isEmpty()) return null
    val block = plates.firstOrNull { meta(it, "plater_id").firstOrNull()?.toIntOrNull() == plate } ?: return emptySet()
    return Regex("""<model_instance>(.*?)</model_instance>""", RegexOption.DOT_MATCHES_ALL).findAll(block).flatMap { meta(it.groupValues[1], "object_id") }.toSet()
}
