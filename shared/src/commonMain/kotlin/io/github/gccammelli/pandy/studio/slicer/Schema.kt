// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One slicing option as Bambu Studio declares it (PrintConfig.cpp, extracted by equivalence/pandy_equiv/schema.py). */
@Serializable
data class OptionDef(
    val type: String,
    val label: String? = null,
    val full_label: String? = null,
    val category: String? = null,
    val tooltip: String? = null,
    val sidetext: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val enum: List<String>? = null,
    val enum_labels: List<String>? = null,
    val mode: String? = null,
    val readonly: Boolean = false,
) {
    val isBool get() = type == "coBool" || type == "coBools"
    val isEnum get() = enum != null
    val isVector get() = type.endsWith("s") && type != "coPercents" || type == "coPercents"
    val isNumber get() = type in setOf("coInt", "coInts", "coFloat", "coFloats", "coPercent", "coPercents", "coFloatOrPercent", "coFloatsOrPercents")
    val isInt get() = type == "coInt" || type == "coInts"
    fun labelOf(value: String) = enum?.indexOf(value)?.takeIf { it >= 0 }?.let { enum_labels?.getOrNull(it) } ?: value
}

@Serializable data class OptionGroup(val title: String, val options: List<String>)
@Serializable data class SettingsPage(val page: String, val groups: List<OptionGroup>)
@Serializable data class Layout(val process: List<SettingsPage>, val filament: List<SettingsPage>)

/** Bambu Studio's options and its settings pages (Tab.cpp), shipped with the engine as pandy/schema.json and pandy/layout.json. */
class Schema(val options: Map<String, OptionDef>, val layout: Layout) {
    operator fun get(key: String) = options[key]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun load(source: ProfileSource) = Schema(
            json.decodeFromString(source.read("pandy/schema.json") ?: "{}"),
            json.decodeFromString(source.read("pandy/layout.json") ?: """{"process":[],"filament":[]}"""),
        )
    }
}
