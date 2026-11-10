// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.gcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcodeParserTest {
    @Test fun statsLayersFeaturesAndRelativeMoves() {
        val text="""; total estimated time: 1h 41m 21s
; total filament length [mm] : 3716.44
; total filament weight [g] : 11.17
; total layer number: 240
G90
M83
; CHANGE_LAYER
; Z_HEIGHT: 0.2
; LAYER_HEIGHT: 0.2
; LINE_WIDTH: 0.42
; FEATURE: Outer wall
G1 X1 Y0 Z0.2 E0.5
; FEATURE: Internal Bridge
G91
G1 X1 Y0 E0.25
"""
        val model=GcodeParser.parse(text);assertEquals(6081,model.stats.estimatedSeconds);assertEquals(3716.44f,model.stats.filamentLengthMm);assertEquals(11.17f,model.stats.filamentGrams);assertEquals(240,model.stats.layerCount)
        val layer=model.layers.single();assertEquals(2,layer.features.size);assertEquals(Feature.OUTER_WALL.ordinal.toByte(),layer.features[0]);assertEquals(Feature.BRIDGE.ordinal.toByte(),layer.features[1]);assertEquals(0.42f,layer.widths[0]);assertEquals(0.2f,layer.z)
        assertEquals(1f,layer.segments[6]);assertEquals(2f,layer.segments[9]);assertEquals(0.2f,layer.segments[8])
    }

    @Test fun absoluteRetractionsTravelAndTools() {
        val code="""M82
G90
; FEATURE: Sparse infill
T1
G1 X2 Y0 Z0.2 E1
G1 X3 E0.5
G0 X4
"""
        val model=GcodeParser.parse(code,includeTravel=true);val layer=model.layers.single()
        assertEquals(3,layer.features.size);assertEquals(Feature.SPARSE_INFILL.ordinal.toByte(),layer.features[0]);assertEquals(Feature.TRAVEL.ordinal.toByte(),layer.features[1]);assertEquals(Feature.TRAVEL.ordinal.toByte(),layer.features[2])
        assertEquals(1,layer.tools[0].toInt());assertEquals(3f,layer.segments[9]);assertEquals(4f,layer.segments[15])
        assertEquals(1,GcodeParser.parse(code).layers.single().features.size)
    }

    @Test fun arcsAreTessellatedAndFallbackLayers() {
        val code="""M83
G90
; FEATURE: Outer wall
G1 X1 Y0 Z0.2 E1
G3 X0 Y1 I-1 J0 E1
G1 Z0.4 X0 Y2 E1
"""
        val layers=GcodeParser.parse(code).layers;assertEquals(2,layers.size);assertTrue(layers[0].features.size>10)
        assertEquals(0f,layers[0].segments[0]);assertEquals(1f,layers[0].segments[3]);assertEquals(0.4f,layers[1].z)
        val arc=layers[0].segments;var longest=0f
        for(i in 1 until layers[0].features.size){val dx=arc[i*6+3]-arc[i*6];val dy=arc[i*6+4]-arc[i*6+1];longest=maxOf(longest,kotlin.math.sqrt(dx*dx+dy*dy))}
        assertTrue(longest<=0.501f)
    }

    @Test fun featureMappingAndEmptyInput() {
        val model=GcodeParser.parse("; FEATURE: unknown\nG1 X1 E1\n; FEATURE: Gap infill\nG1 X2 E2\n")
        assertEquals(Feature.OTHER.ordinal.toByte(),model.layers.single().features[0]);assertEquals(Feature.GAP_FILL.ordinal.toByte(),model.layers.single().features[1])
        assertTrue(GcodeParser.parse("G90\n").layers.isEmpty())
    }

    @Test fun g92ResetsExtrusionAndExecutableMovesAreKept() {
        val model=GcodeParser.parse("""M83
G92 E0
; EXECUTABLE_BLOCK_START
G1 X1 E0.4
G92 E0
G1 X2 E0.2
; EXECUTABLE_BLOCK_END
""")
        assertEquals(2,model.layers.single().features.size)
        assertEquals(2f,model.layers.single().segments[9])
    }
}
