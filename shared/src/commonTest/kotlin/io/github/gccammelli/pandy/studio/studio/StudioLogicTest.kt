// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.io.SceneObject
import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.io.ThreeMf
import io.github.gccammelli.pandy.studio.io.Zip
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.model.Vec3
import io.github.gccammelli.pandy.studio.slicer.*
import io.github.gccammelli.pandy.studio.ui.StudioTestHost
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StudioLogicTest {
    private val gcode = "; total layer number: 2\nM83\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\n; FEATURE: Outer wall\nG1 X1 Y0 Z0.2 E0.5\n; CHANGE_LAYER\n; Z_HEIGHT: 0.4\n; FEATURE: Outer wall\nG1 X2 Y0 Z0.4 E0.5\n"
    private fun run(code: Int = 0, message: String = "Success.", sliced: ByteArray? = Zip.write(listOf("Metadata/plate_1.gcode" to gcode.encodeToByteArray()))) =
        SliceRun(SliceOutcome(code, message), code, sliced, 5, listOf("a", "b", "c", "d"), null)
    private fun host(slice: suspend (SliceJob, (String) -> Unit) -> SliceRun) = object : Platform by StudioTestHost() {
        override suspend fun slice(job: SliceJob, onLog: (String) -> Unit) = slice(job, onLog)
    }

    // ---------- Shapes ----------

    @Test fun everySampleShapeIsAClosedSolidOfTheExpectedSize() {
        assertEquals(listOf("Cube", "Gear", "Vase", "Torus"), Shapes.samples.map { it.first })
        for ((name, make) in Shapes.samples) {
            val m = make()
            assertEquals(0, m.indices.size % 3, name)
            assertTrue(m.indices.all { it in 0 until m.vertexCount }, name)
            assertTrue(m.volume() > 0f, name)
        }
        val cube = Shapes.box(20f, 20f, 20f); assertEquals(8000f, cube.volume(), 1f)
        val torus = Shapes.torus().bounds(); assertEquals(44f, torus.size.x, 0.5f); assertEquals(12f, torus.size.z, 0.5f)
        val vase = Shapes.vase().bounds(); assertEquals(90f, vase.size.z, 0.01f)
        val gear = Shapes.gear().bounds(); assertEquals(8f, gear.size.z, 0.01f); assertEquals(40f, gear.size.x, 1f)
        val cone = Shapes.lathe(listOf(0f to 0f, 10f to 0f, 0f to 10f), 8); assertEquals(8 * 2, cone.triangleCount); assertEquals(10f, cone.bounds().size.z, 0.01f)
    }

    // ---------- Schema ----------

    @Test fun theSchemaLoadsOptionsAndLayoutAndTellsTheirKinds() {
        val files = mapOf(
            "pandy/schema.json" to """{"a":{"type":"coBool"},"b":{"type":"coEnum","enum":["x","y"],"enum_labels":["Ex","Why"],"extra":1},"c":{"type":"coInts","min":1.0,"max":5.0},"d":{"type":"coPercent"},"e":{"type":"coString"}}""",
            "pandy/layout.json" to """{"process":[{"page":"Quality","groups":[{"title":"Layer","options":["a","b"]}]}],"filament":[]}""",
        )
        val s = Schema.load { files[it] }
        assertEquals("Quality", s.layout.process.single().page); assertEquals(listOf("a", "b"), s.layout.process.single().groups.single().options)
        assertTrue(s["a"]!!.isBool); assertFalse(s["a"]!!.isNumber)
        assertTrue(s["b"]!!.isEnum); assertEquals("Why", s["b"]!!.labelOf("y")); assertEquals("zzz", s["b"]!!.labelOf("zzz"))
        assertTrue(s["c"]!!.isNumber && s["c"]!!.isInt && s["c"]!!.isVector); assertEquals(5.0, s["c"]!!.max)
        assertTrue(s["d"]!!.isNumber && !s["d"]!!.isInt); assertFalse(s["e"]!!.isNumber)
        assertNull(s["missing"])
        val empty = Schema.load { null }
        assertTrue(empty.options.isEmpty()); assertTrue(empty.layout.process.isEmpty() && empty.layout.filament.isEmpty())
    }

    // ---------- Scene ----------

    @Test fun theBedComesFromThePrinterAreaAndFallsBackToTheDefault() {
        val bed = Bed.of(listOf("10x20", "270x20", "270x180", "10x180"), "300")
        assertEquals(Bed(10f, 20f, 260f, 160f, 300f), bed); assertEquals(140f, bed.centerX); assertEquals(100f, bed.centerY)
        assertEquals(Bed(), Bed.of(null, null)); assertEquals(Bed(), Bed.of(listOf("bad", "1x"), "x"))
        assertEquals(256f, Bed.of(listOf("0x0", "100x100"), "oops").height)
    }

    @Test fun aPlateObjectSitsOnTheBedAtItsPositionAndCopiesItsPlacement() {
        val o = PlateObject.of(1, SceneObject("cube", Shapes.box(10f, 20f, 30f), Mat4.translation(100f, 100f, 50f)))
        assertEquals(105f, o.x); assertEquals(110f, o.y)
        var b = o.worldBounds(); assertEquals(0f, b.min.z, 0.001f); assertEquals(30f, b.max.z, 0.001f)
        o.rotX = 90f; b = o.worldBounds(); assertEquals(0f, b.min.z, 0.001f); assertEquals(20f, b.max.z, 0.01f)
        o.rotX = 0f; o.rotY = 90f; assertEquals(10f, o.worldBounds().max.z, 0.01f)
        o.rotY = 0f; o.scale = Vec3(2f, 1f, 1f); assertEquals(20f, o.localBounds().size.x, 0.01f)
        o.filament = 2; o.rotZ = 30f
        val c = o.copy(7)
        assertEquals(7, c.id); assertEquals("cube", c.name); assertEquals(2, c.filament); assertEquals(30f, c.rotZ); assertEquals(Vec3(2f, 1f, 1f), c.scale); assertSame(o.mesh, c.mesh)
    }

    @Test fun theSceneArrangesCentresAndFlagsObjectsOutsideTheBed() {
        val scene = Scene(); scene.bed = Bed(0f, 0f, 100f, 100f, 50f)
        assertEquals(1L, scene.newId()); assertEquals(2L, scene.newId())
        val a = PlateObject(10, "a", Shapes.box(40f, 40f, 10f)); val b = PlateObject(11, "b", Shapes.box(30f, 30f, 10f)); val c = PlateObject(12, "c", Shapes.box(30f, 30f, 10f))
        val v0 = scene.version
        scene.add(a); scene.add(b); scene.add(c)
        assertEquals(12L, scene.selected); assertSame(c, scene.selection); assertTrue(scene.version > v0)
        scene.arrange()
        assertTrue(scene.outside().isEmpty())
        val all = scene.objects.map { it.worldBounds() }.reduce { x, y -> x.union(y) }
        assertEquals(50f, all.center.x, 0.01f); assertEquals(50f, all.center.y, 0.01f)
        val boxes = scene.objects.map { it.worldBounds() }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertTrue(
            boxes[i].max.x <= boxes[j].min.x || boxes[j].max.x <= boxes[i].min.x || boxes[i].max.y <= boxes[j].min.y || boxes[j].max.y <= boxes[i].min.y, "$i overlaps $j")
        a.x = 500f; assertEquals(listOf(a), scene.outside())
        a.x = 50f; a.scale = Vec3(1f, 1f, 10f); assertEquals(listOf(a), scene.outside())
        assertEquals(listOf("a", "b", "c"), scene.sceneObjects().map { it.name })
        scene.remove(12); assertNull(scene.selected); assertNull(scene.selection); assertEquals(2, scene.objects.size)
        scene.remove(10); assertEquals(1, scene.objects.size)
        Scene().arrange() // nothing to lay out
    }

    // ---------- Studio ----------

    private fun loaded(p: Platform = StudioTestHost()) = runBlocking { Studio(p, this).also { it.load().join() } }

    @Test fun filamentsCanBeAddedRecolouredAndRemovedWithObjectsFollowing() {
        val s = loaded(); val pla = s.filamentChoices.first { "PLA" in it.name }; val petg = s.filamentChoices.first { "PETG" in it.name }
        assertEquals(listOf("#00AE42"), s.scene.colors)
        s.setFilament(1, petg)
        assertEquals(listOf("#00AE42", "#E0409C"), s.scene.colors)
        s.setFilament(1, pla, "#112233"); assertEquals("#112233", s.filaments[1].second)
        s.setColor(0, "#ABCDEF"); s.setColor(9, "#000000")
        assertEquals(listOf("#ABCDEF", "#112233"), s.scene.colors)
        s.addSample("cube", Shapes.box(10f, 10f, 10f)); s.scene.objects.single().filament = 1
        s.removeFilament(1)
        assertEquals(0, s.scene.objects.single().filament); assertEquals(1, s.filaments.size)
        s.removeFilament(0); assertEquals(1, s.filaments.size)
    }

    @Test fun settingsOverridesAreKeptOnlyWhenTheyDifferFromThePreset() {
        val s = loaded()
        assertEquals("0.2", s.value("layer_height")!!.first)
        s.set("layer_height", Setting.One("0.3")); assertEquals("0.3", s.value("layer_height")!!.first)
        s.set("layer_height", Setting.One("0.2")); assertTrue(s.overrides.isEmpty())
        s.set("layer_height", Setting.One("0.1")); s.reset("layer_height"); assertTrue(s.overrides.isEmpty())
        val blocked = BLOCKED_OPTIONS.first(); s.set(blocked, Setting.One("1")); assertFalse(blocked in s.overrides)
        s.set("layer_height", Setting.One("0.3")); s.chooseProcess(s.processes.single()); assertEquals("0.3", s.value("layer_height")!!.first)
        assertNull(s.value("nonexistent"))
    }

    @Test fun addingModelsDuplicatingAndInvalidatingResetTheLastSlice() {
        val s = loaded()
        s.addSample("cube", Shapes.box(10f, 10f, 10f)); s.addSample("cube2", Shapes.box(10f, 10f, 10f))
        assertEquals(2, s.scene.objects.size); assertTrue(s.scene.outside().isEmpty())
        val id = s.scene.objects.first().id
        s.state = SliceState.Failed("old"); s.scene.mode = ViewMode.PREVIEW
        s.duplicate(id); s.duplicate(999)
        assertEquals(3, s.scene.objects.size); assertEquals(SliceState.Idle, s.state); assertEquals(ViewMode.PREPARE, s.scene.mode)
        assertEquals(s.scene.objects.last().id, s.scene.selected)
    }

    @Test fun filesOfBothFormatsLandOnThePlate() {
        val s = loaded()
        s.addFile("Part.STL", Stl.writeBinary(Shapes.box(10f, 10f, 10f)))
        assertEquals("Part", s.scene.objects.single().name); assertEquals(s.scene.bed.centerX, s.scene.objects.single().x, 0.01f)
        val three = ThreeMf.writePlain(listOf(SceneObject("holder", Shapes.box(10f, 10f, 10f), Mat4.translation(30f, 40f, 0f))))
        s.addFile("holder.3mf", three)
        assertEquals(2, s.scene.objects.size)
        assertFailsWith<IllegalArgumentException> { s.addFile("project.3mf", Zip.write(listOf("3D/3dmodel.model" to "<model/>".encodeToByteArray(), "Metadata/project_settings.config" to "{}".encodeToByteArray()))) }
    }

    @Test fun theJobAndTheUsedFilamentsDescribeThePlate() {
        val s = loaded()
        assertNull(s.job()); assertFalse(s.canSlice)
        s.addSample("cube", Shapes.box(10f, 10f, 10f)); s.addSample("b", Shapes.box(10f, 10f, 10f))
        assertTrue(s.canSlice)
        s.set("layer_height", Setting.One("0.3"))
        val job = s.job()!!
        assertEquals("Bambu Lab X1 Carbon 0.4 nozzle", job.printer); assertEquals("0.20mm Standard @BBL X1C", job.process)
        assertEquals(listOf("Bambu PLA Basic @BBL X1C"), job.filaments); assertEquals(mapOf("layer_height" to Setting.One("0.3")), job.processOverrides)
        assertEquals(listOf(mapOf("id" to "1", "type" to "PLA", "color" to "#00AE42")), s.usedFilaments())
        s.setFilament(1, s.filamentChoices.first { "PETG" in it.name }, "#123456"); s.scene.objects.last().filament = 1
        assertEquals(listOf(mapOf("id" to "1", "type" to "PLA", "color" to "#00AE42"), mapOf("id" to "2", "type" to "PETG", "color" to "#123456")), s.usedFilaments())
        s.scene.objects.forEach { it.filament = 5 }; assertTrue(s.usedFilaments().isEmpty())
    }

    @Test fun aSuccessfulSliceLoadsThePreviewAndReportsProgress() = runBlocking {
        val lines = mutableListOf<SliceState>()
        lateinit var s: Studio
        s = Studio(host { _, log -> log("Initializing StaticPrintConfigs"); lines += s.state; log("unrelated"); log("export_gcode"); lines += s.state; run() }, this)
        s.load().join()
        s.slice(); assertEquals(SliceState.Idle, s.state) // nothing on the plate yet
        s.addSample("cube", Shapes.box(10f, 10f, 10f))
        s.slice(); assertIs<SliceState.Running>(s.state)
        while (s.state !is SliceState.Done) kotlinx.coroutines.yield()
        assertEquals(SliceState.Running("Starting", .02f), lines[0]); assertEquals(SliceState.Running("Writing G-code", .8f), lines[1])
        val done = s.state as SliceState.Done
        assertEquals(2, done.gcode.layers.size); assertEquals(ViewMode.PREVIEW, s.scene.mode); assertSame(done.gcode, s.scene.preview)
        assertEquals(1, s.scene.layerLow); assertEquals(2, s.scene.layerHigh)
        s.set("layer_height", Setting.One("0.3")); assertEquals(SliceState.Idle, s.state); assertNull(s.scene.preview)
    }

    @Test fun aFailedSliceSaysWhy() = runBlocking {
        var next: () -> SliceRun = { run(code = 3, message = "Object too tall.") }
        val s = Studio(host { _, _ -> next() }, this); s.load().join(); s.addSample("cube", Shapes.box(10f, 10f, 10f))
        suspend fun slice(): SliceState { s.slice(); while (s.state is SliceState.Running) kotlinx.coroutines.yield(); return s.state }
        assertEquals(SliceState.Failed("Object too tall."), slice())
        next = { SliceRun(null, 1, null, 1, listOf("x", "l1", "l2", "l3"), null) }
        assertEquals(SliceState.Failed("The slicer stopped (code 1).\nl1\nl2\nl3"), slice())
        next = { throw IllegalStateException("plugin crashed") }
        assertEquals(SliceState.Failed("plugin crashed"), slice())
        s.cancel(); assertEquals(SliceState.Idle, s.state)
    }

    @Test fun enginesCannotBeChangedWhileBusyOrToAnUnknownOne() = runBlocking {
        val s = Studio(StudioTestHost(), this); s.load().join()
        assertEquals("This engine is not installed.", assertFailsWith<IllegalArgumentException> { s.chooseEngine("nope") }.message)
        s.state = SliceState.Running("x", null)
        assertEquals("Wait for the current operation to finish.", assertFailsWith<IllegalArgumentException> { s.chooseEngine("bambu") }.message)
    }

    @Test fun progressLinesMapToStepsIgnoringCase() {
        assertEquals("Generating walls" to .4f, Studio.progressOf("[info] PERIMETERS done"))
        assertEquals("Packing the file" to .95f, Studio.progressOf("export_3mf"))
        assertNull(Studio.progressOf("hello"))
    }
}
