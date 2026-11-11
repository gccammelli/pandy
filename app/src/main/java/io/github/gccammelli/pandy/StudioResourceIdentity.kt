package io.github.gccammelli.pandy

import org.json.JSONObject

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
