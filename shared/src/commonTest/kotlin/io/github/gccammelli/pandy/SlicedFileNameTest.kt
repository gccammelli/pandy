package io.github.gccammelli.pandy

import kotlin.test.Test
import kotlin.test.assertEquals

class SlicedFileNameTest {
    @Test fun keepsAPlainName() = assertEquals("Cube + Sphere.gcode.3mf", slicedFileName("attachment; filename=\"Cube + Sphere.gcode.3mf\""))

    @Test fun dropsPathsAndOddCharacters() {
        assertEquals("evil.gcode.3mf", slicedFileName("attachment; filename=\"../../evil.gcode.3mf\""))
        assertEquals("ab.gcode.3mf", slicedFileName("attachment; filename=\"a;b.gcode.3mf\""))
    }

    @Test fun fallsBackWhenMissingOrNotAPrintFile() {
        assertEquals("model.gcode.3mf", slicedFileName(null))
        assertEquals("model.gcode.3mf", slicedFileName("attachment; filename=\"notes.txt\""))
        assertEquals("model.gcode.3mf", slicedFileName("attachment; filename=\"..gcode.3mf\""))
    }
}
