// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the slicer reported in result.json (Bambu Studio's CLI writes it on success and on every refusal). */
data class SliceOutcome(val returnCode: Int, val message: String) {
    val ok get() = returnCode == 0

    companion object {
        fun parse(text: String?): SliceOutcome? = runCatching {
            val o = Json.parseToJsonElement(text ?: return null).jsonObject
            SliceOutcome(o["return_code"]?.jsonPrimitive?.intOrNull ?: -1, o["error_string"]?.jsonPrimitive?.content ?: "")
        }.getOrNull()
    }
}

/**
 * Checks a sliced plate before it can be sent, as Pandy Slicer does: Bambu's printer start sequence must be there
 * (homing G28, and bed levelling G29 or the M1002 calibration hooks, before the first layer), and no temperature may
 * exceed a Bambu printer's limits. A slice from the engine always passes; this guards against broken presets.
 */
object Safety {
    const val MAX_NOZZLE = 350
    const val MAX_BED = 120
    const val MAX_CHAMBER = 65
    private val command = Regex("^([A-Za-z][0-9]+)\\b")
    private val temperature = Regex("\\bS(-?[0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)

    fun problems(gcode: String): List<String> {
        val out = mutableListOf<String>()
        val firstLayer = gcode.indexOf("; CHANGE_LAYER").let { if (it < 0) gcode.length else it }
        val head = gcode.substring(0, firstLayer)
        val commands = head.lineSequence().mapNotNull { command.find(it.substringBefore(';').trim())?.groupValues?.get(1)?.uppercase() }.toList()
        val homes = "G28" in commands
        val levels = "G29" in commands || "M1002" in commands
        if (!homes || !levels) out += "The sliced file lacks the printer's start sequence (homing, bed levelling, purge line)."
        for (line in gcode.lineSequence()) {
            val code = line.substringBefore(';').trim()
            val cmd = command.find(code)?.groupValues?.get(1)?.uppercase() ?: continue
            val limit = when (cmd) { "M104", "M109" -> MAX_NOZZLE; "M140", "M190" -> MAX_BED; "M141", "M191" -> MAX_CHAMBER; else -> continue }
            val s = temperature.find(code)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (s > limit) out += "The sliced file sets $cmd S$s, beyond the printer's limit of $limit °C."
        }
        return out.distinct()
    }
}
