// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.io.Zip
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EditorImportTest {
    @Test fun acceptsPlainModelsButRejectsProjectMetadata() {
        val model = "3D/3dmodel.model" to "<model/>".encodeToByteArray()
        requirePlainEditorModel(Zip.write(listOf(model)))
        for (path in listOf("Metadata/project_settings.config", "metadata/MODEL_SETTINGS.CONFIG", "Metadata/Slic3r_PE_model.config", "Metadata/layer_config_ranges.xml", "Metadata/layer_heights_profile.txt")) {
            assertFailsWith<IllegalArgumentException> { requirePlainEditorModel(Zip.write(listOf(model, path to byteArrayOf()))) }
        }
        assertFailsWith<IllegalArgumentException> { requirePlainEditorModel(Zip.write(listOf("README" to byteArrayOf()))) }
    }
}
