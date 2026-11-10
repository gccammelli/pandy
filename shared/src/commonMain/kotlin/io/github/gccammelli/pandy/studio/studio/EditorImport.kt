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
