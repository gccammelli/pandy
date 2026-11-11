// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.slicer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Preset library, job and result parsing on the paths the happy tests skip. */
class SlicerEdgeTest {
    private fun lib(files: Map<String, String>) = PresetLibrary(ProfileSource { files[it] })
    private val index = """{"machine_list":[{"name":"M 0.4 nozzle","sub_path":"m.json"},{"name":"M 0.2 nozzle","sub_path":"m2.json"},{"name":"Plain","sub_path":"p.json"},{"name":"Gone","sub_path":"gone.json"}],
        "process_list":[{"name":"0.20mm Std","sub_path":"pr.json"},{"name":"0.10mm Fine","sub_path":"pr2.json"},{"name":"Other","sub_path":"pr3.json"}],
        "filament_list":[{"name":"PLA","sub_path":"f.json"},{"name":"ABS","sub_path":"f2.json"}]}"""
    private val files = mapOf(
        "profiles/BBL.json" to index,
        "profiles/BBL/m.json" to """{"name":"M 0.4 nozzle","type":"machine","instantiation":"true","printer_model":"M","nozzle_diameter":["0.4"],"extra":{"a":1},"mixed":["x",{"y":2}]}""",
        "profiles/BBL/m2.json" to """{"name":"M 0.2 nozzle","type":"machine","instantiation":"true","printer_model":"M","nozzle_diameter":["0.2"]}""",
        "profiles/BBL/p.json" to """{"name":"Plain","instantiation":"true","inherits":""}""",
        "profiles/BBL/pr.json" to """{"name":"0.20mm Std","type":"process","instantiation":"true","layer_height":"0.2","compatible_printers":["M 0.4 nozzle"]}""",
        "profiles/BBL/pr2.json" to """{"name":"0.10mm Fine","type":"process","instantiation":"true","layer_height":"0.1","compatible_printers":["M 0.4 nozzle","M 0.2 nozzle"]}""",
        "profiles/BBL/pr3.json" to """{"name":"Other","type":"process","instantiation":"true","layer_height":"0.3"}""",
        "profiles/BBL/f.json" to """{"name":"PLA","type":"filament","instantiation":"true","compatible_printers":["M 0.4 nozzle"]}""",
        "profiles/BBL/f2.json" to """{"name":"ABS","type":"filament","instantiation":"false","compatible_printers":["M 0.4 nozzle"]}""",
    )

    @Test fun printersGroupByModelAndSortByNozzle() {
        val printers = lib(files).printers()
        val m = printers.first { it.model == "M" }
        assertEquals(listOf("M 0.2 nozzle", "M 0.4 nozzle"), m.nozzles.map { it.name })
        assertEquals("Plain", printers.first { it.model == "Plain" }.model)
    }

    @Test fun processesAndFilamentsAreFilteredByPrinterAndSortedByLayerHeight() {
        val l = lib(files)
        assertEquals(listOf("0.10mm Fine", "0.20mm Std"), l.processes("M 0.4 nozzle").map { it.name })
        assertEquals(listOf("0.10mm Fine"), l.processes("M 0.2 nozzle").map { it.name })
        assertEquals(listOf("PLA"), l.filaments("M 0.4 nozzle").map { it.name })
        assertTrue(l.filaments("Nothing").isEmpty())
    }

    @Test fun unusualJsonValuesBecomeSettings() {
        val p = lib(files).preset("M 0.4 nozzle")!!
        assertEquals(Setting.One("""{"a":1}"""), p["extra"])
        assertEquals(Setting.Many(listOf("x", """{"y":2}""")), p["mixed"])
    }

    @Test fun unknownPresetsHaveNoIncludesOrValues() {
        val l = lib(files)
        assertNull(l.preset("Gone")); assertNull(l.preset("No such"))
        assertTrue(l.includes("No such").isEmpty())
    }

    @Test fun emptyInheritsAndMissingListsAreHarmless() {
        val l = lib(files + ("profiles/BBL.json" to """{"machine_list":[{"name":"Plain","sub_path":"p.json"}]}"""))
        assertEquals("", l.preset("Plain")!!.type)
        assertTrue(l.includes("Plain").isEmpty())
        assertTrue(l.processes("Plain").isEmpty() && l.filaments("Plain").isEmpty())
    }

    @Test fun indexEntriesMustBeUniqueAndSafe() {
        fun build(entry: String) = { lib(files + ("profiles/BBL.json" to """{"machine_list":[$entry]}""")) }
        assertFailsWith<IllegalArgumentException> { build("""{"name":"A","sub_path":"a.json"},{"name":"A","sub_path":"b.json"}""")() }
        assertFailsWith<IllegalArgumentException> { build("""{"name":"A","sub_path":"/etc/a.json"}""")() }
        assertFailsWith<IllegalArgumentException> { build("""{"name":"A","sub_path":"../a.json"}""")() }
        assertFailsWith<IllegalArgumentException> { build("""{"name":"A"}""")() }
        assertFailsWith<IllegalStateException> { lib(emptyMap()) }
    }

    @Test fun includedPresetsMustExist() {
        val bad = files + ("profiles/BBL/m.json" to """{"name":"M 0.4 nozzle","include":["Missing tpl"],"extra":"x"}""")
        assertFailsWith<IllegalArgumentException> { lib(bad).preset("M 0.4 nozzle") }
        assertFailsWith<IllegalArgumentException> { lib(bad).includes("M 0.4 nozzle") }
        val notArray = files + ("profiles/BBL/m.json" to """{"name":"M 0.4 nozzle","include":"Missing tpl"}""")
        assertTrue(lib(notArray).preset("M 0.4 nozzle")!!.values.isEmpty())
        assertTrue(lib(notArray).includes("M 0.4 nozzle").isEmpty())
    }

    @Test fun sliceJobEqualityCoversEveryField() {
        val a = SliceJob(byteArrayOf(1, 2), "P", "Q", listOf("F"), mapOf("k" to Setting.One("1")))
        val same = SliceJob(byteArrayOf(1, 2), "P", "Q", listOf("F"), mapOf("k" to Setting.One("1")))
        assertEquals(a, same); assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals<Any?>(a, "not a job")
        assertNotEquals(a, a.copy(model3mf = byteArrayOf(9)))
        assertNotEquals(a, a.copy(printer = "X"))
        assertNotEquals(a, a.copy(process = "X"))
        assertNotEquals(a, a.copy(filaments = listOf("G")))
        assertNotEquals(a, a.copy(processOverrides = emptyMap()))
        assertNotEquals(a, a.copy(filamentOverrides = mapOf("f" to Setting.One("2"))))
    }

    @Test fun blockedOptionsNeverReachTheJobFiles() {
        val job = SliceJob(byteArrayOf(1), "P", "Q", listOf("F"), mapOf("post_process" to Setting.One("x"), "wall_loops" to Setting.One("3")),
            mapOf("filename_format" to Setting.One("y"), "filament_flow_ratio" to Setting.Many(listOf("0.9"))))
        val files = job.files().mapValues { it.value.decodeToString() }
        assertFalse("post_process" in files.getValue("process.json")); assertTrue("wall_loops" in files.getValue("process.json"))
        assertFalse("filename_format" in files.getValue("filament1.json")); assertTrue("filament_flow_ratio" in files.getValue("filament1.json"))
    }

    @Test fun settingFirstValueOfAnEmptyListIsBlank() {
        assertEquals("", Setting.Many(emptyList()).first); assertEquals("a", Setting.Many(listOf("a", "b")).first); assertEquals("z", Setting.One("z").first)
    }

    @Test fun sliceOutcomeParsingDefaultsAndFailures() {
        assertEquals(SliceOutcome(0, "ok"), SliceOutcome.parse("""{"return_code":0,"error_string":"ok"}"""))
        assertTrue(SliceOutcome.parse("""{"return_code":0}""")!!.ok)
        assertEquals(SliceOutcome(-1, ""), SliceOutcome.parse("{}"))
        assertEquals(-1, SliceOutcome.parse("""{"return_code":"zero"}""")!!.returnCode)
        assertFalse(SliceOutcome.parse("""{"return_code":3}""")!!.ok)
        assertNull(SliceOutcome.parse(null)); assertNull(SliceOutcome.parse("not json")); assertNull(SliceOutcome.parse("[1]"))
    }

    @Test fun safetyAcceptsM1002AndIgnoresUnparsableTemperatures() {
        val head = "G28\nM1002 set_flag\n"
        assertEquals(emptyList(), Safety.problems(head + "; CHANGE_LAYER\nM104 S200\nM104 S\nM190 S60\nG1 X1\nM141 S40\nM999 S9999\n"))
        assertEquals(1, Safety.problems("G28\n; CHANGE_LAYER\n").size)
        assertEquals(1, Safety.problems("G29\n; CHANGE_LAYER\n").size)
        val hot = Safety.problems(head + "M109 S400\nM191 S99\nM104 S400\n")
        assertEquals(3, hot.size)
    }
}
