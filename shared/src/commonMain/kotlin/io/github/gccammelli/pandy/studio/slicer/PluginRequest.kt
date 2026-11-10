// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Protocol metadata; the model itself travels separately through a file descriptor. */
fun SliceJob.pluginRequest(): String = buildJsonObject {
    put("version", 1)
    put("printer", printer)
    put("process", process)
    put("filaments", JsonArray(filaments.map(::JsonPrimitive)))
    fun settings(values: Map<String, Setting>) = JsonObject(
        values.filterKeys { it !in BLOCKED_OPTIONS }.mapValues { it.value.json }
    )
    put("process_overrides", settings(processOverrides))
    put("filament_overrides", settings(filamentOverrides))
    put("printer_includes", settings(printerIncludes))
}.toString()
