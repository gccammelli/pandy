package io.github.gccammelli.pandy

import org.json.JSONObject
import java.io.File

/** Validate the profile archive before extracting or binding future jobs to it. */
internal fun studioResourceVersion(engineId: String, metadata: JSONObject, archiveHash: String, advertisedVersion: String?): String? {
    require(metadata.getInt("version") == 1) { "Unsupported resource protocol." }
    require(metadata.optString("engine_id", "bambu") == engineId) { "The plugin returned another engine's profiles." }
    require(metadata.optString("profile_root", "resources") == if (engineId == "orca") "pandy/editor" else "resources") { "Unsupported profile location." }
    require(archiveHash == metadata.getString("sha256")) { "Slicer profiles failed their integrity check." }
    // Legacy Bambu replies only include the ZIP digest; capabilities supply its tree-version digest.
    val version = metadata.optString("resource_version", if (engineId == "bambu") advertisedVersion.orEmpty() else "")
    require(advertisedVersion == null || version == advertisedVersion) { "Slicer profiles changed while loading. Try again." }
    require(engineId == "bambu" || version.matches(Regex("[a-f0-9]{64}"))) { "Missing profile version." }
    require(engineId == "bambu" || archiveHash == version) { "Slicer profile version does not match the archive." }
    return version.takeIf { it.isNotEmpty() }
}

/**
 * Checks that an extracted profile tree still has its sentinel, parseable schema/layout, vendor index, at least one
 * instantiable machine preset and every indexed preset file present and non-empty. Any missing or corrupt file forces
 * a clean re-extract instead of letting PresetLibrary silently drop presets.
 */
internal fun studioProfileTreeComplete(target: File, engineId: String): Boolean = runCatching {
    if (!File(target, ".complete").isFile) return@runCatching false
    val schemaFile = File(target, "pandy/schema.json")
    val layoutFile = File(target, "pandy/layout.json")
    if (!schemaFile.isFile || !layoutFile.isFile) return@runCatching false
    JSONObject(schemaFile.readText())
    JSONObject(layoutFile.readText())
    val root = File(target, if (engineId == "orca") "pandy/editor" else "resources").canonicalFile
    val profiles = File(root, "profiles/BBL").canonicalFile
    val indexFile = File(root, "profiles/BBL.json")
    if (!indexFile.isFile) return@runCatching false
    val index = JSONObject(indexFile.readText())
    val machines = index.optJSONArray("machine_list") ?: return@runCatching false
    if (machines.length() == 0) return@runCatching false
    val prefix = profiles.path + File.separator
    var instantiableMachines = 0
    for (listKey in listOf("machine_list", "process_list", "filament_list")) {
        val list = index.optJSONArray(listKey) ?: continue
        for (i in 0 until list.length()) {
            val subPath = list.getJSONObject(i).optString("sub_path")
            if (subPath.isEmpty() || subPath.startsWith('/') || subPath.split('/').any { it == ".." }) return@runCatching false
            val file = File(profiles, subPath).canonicalFile
            if (!file.path.startsWith(prefix) || !file.isFile || file.length() <= 0L) return@runCatching false
            if (listKey == "machine_list") {
                val preset = JSONObject(file.readText())
                if (preset.optString("instantiation") == "true") instantiableMachines++
            }
        }
    }
    instantiableMachines > 0
}.getOrDefault(false)
