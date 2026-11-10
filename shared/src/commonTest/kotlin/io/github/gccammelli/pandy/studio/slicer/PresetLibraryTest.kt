// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PresetLibraryTest {
    private val files = mapOf(
        "profiles/BBL.json" to """{"machine_list":[{"name":"base","sub_path":"machine/base.json"},{"name":"start tpl","sub_path":"machine/start.json"},
            {"name":"P 0.4 nozzle","sub_path":"machine/p.json"}],"process_list":[],"filament_list":[]}""",
        "profiles/BBL/machine/base.json" to """{"type":"machine","name":"base","nozzle_diameter":["0.4"]}""",
        "profiles/BBL/machine/start.json" to """{"type":"machine","name":"start tpl","machine_start_gcode":"G28\nG29"}""",
        "profiles/BBL/machine/p.json" to """{"type":"machine","name":"P 0.4 nozzle","inherits":"base","instantiation":"true","include":["start tpl"],"printable_height":"250"}""",
    )
    private val lib = PresetLibrary(ProfileSource { files[it] })

    @Test fun inheritanceAndIncludesResolve() {
        val p = lib.preset("P 0.4 nozzle")!!
        assertEquals("250", p.string("printable_height"))
        assertEquals("0.4", p.string("nozzle_diameter"))
        assertEquals("G28\nG29", p.string("machine_start_gcode"))
    }

    @Test fun includesAreReportedForTheJob() = assertEquals(mapOf("machine_start_gcode" to Setting.One("G28\nG29")), lib.includes("P 0.4 nozzle"))

    @Test fun missingAndCyclicInheritanceAreRejected() {
        fun library(contents: Map<String, String>) = PresetLibrary(ProfileSource { contents[it] })
        val missing = files + ("profiles/BBL/machine/p.json" to """{"name":"P 0.4 nozzle","inherits":"absent"}""")
        assertFailsWith<IllegalArgumentException> { library(missing).preset("P 0.4 nozzle") }
        assertFailsWith<IllegalArgumentException> { library(missing).includes("P 0.4 nozzle") }
        val cyclic = files + ("profiles/BBL/machine/base.json" to """{"name":"base","inherits":"P 0.4 nozzle"}""")
        assertFailsWith<IllegalArgumentException> { library(cyclic).preset("P 0.4 nozzle") }
        assertFailsWith<IllegalArgumentException> { library(cyclic).includes("P 0.4 nozzle") }
    }

    @Test fun malformedIndexAndMismatchedNamesAreRejected() {
        val invalid = files + ("profiles/BBL.json" to """{"machine_list":[{"name":"P"}],"process_list":[],"filament_list":[]}""")
        assertFailsWith<IllegalArgumentException> { PresetLibrary(ProfileSource { invalid[it] }) }
        val wrongName = files + ("profiles/BBL/machine/p.json" to """{"name":"other"}""")
        assertFailsWith<IllegalArgumentException> { PresetLibrary(ProfileSource { wrongName[it] }).preset("P 0.4 nozzle") }
    }
}
