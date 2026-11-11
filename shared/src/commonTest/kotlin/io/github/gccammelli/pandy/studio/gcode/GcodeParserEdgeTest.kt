// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.gcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The untaken paths of the G-code parser: malformed numbers, odd line endings, every duration and arc form. */
class GcodeParserEdgeTest {
    private fun parse(s: String, travel: Boolean = false) = GcodeParser.parse(s, travel)

    @Test fun longRunsGrowTheSegmentBuffers() {
        val sb = StringBuilder("M83\nG90\n; FEATURE: Outer wall\n")
        for (i in 1..200) sb.append("G1 X$i Y0 E0.1\n")
        val layer = parse(sb.toString()).layers.single()
        assertEquals(200, layer.features.size); assertEquals(1200, layer.segments.size)
        assertEquals(200f, layer.segments[1199 - 2]); assertEquals(200, layer.widths.size); assertEquals(200, layer.tools.size)
    }

    @Test fun windowsLineEndingsAndBlankLinesAreSkipped() {
        val layer = parse("M83\r\nG90\r\n\r\n\r\nG1 X1 E1\r\n\r\nG1 X2 E1\r\n").layers.single()
        assertEquals(2, layer.features.size); assertEquals(2f, layer.segments[9])
    }

    @Test fun unparsableCommentValuesAreIgnored() {
        val model = parse("""M83
; CHANGE_LAYER
; Z_HEIGHT: tall
; LAYER_HEIGHT: thin
; LINE_WIDTH: wide
; total layer number: many
G1 X1 Y0 Z0.3 E1
; LINE_WIDTH: -1
G1 X2 Y0 E1
""")
        val layer = model.layers.single()
        assertEquals(0.3f, layer.z); assertEquals(0f, layer.height); assertEquals(0f, layer.widths[0]); assertEquals(0f, layer.widths[1])
        assertNull(model.stats.layerCount)
    }

    @Test fun timeEstimateFormsAndFirstOneWins() {
        assertEquals(5853, parse("; estimated printing time (normal mode) = 1h 37m 33s\n").stats.estimatedSeconds)
        assertEquals(300, parse("; model printing time: 5m\n").stats.estimatedSeconds)
        assertEquals(100, parse("; model printing time: 1m 40s\n; estimated printing time (normal mode) = 3h\n").stats.estimatedSeconds)
        assertEquals(60, parse("; estimated printing time = 1m\n; model printing time: 9m\n").stats.estimatedSeconds)
        assertEquals(93784, parse("; total estimated time: 1d 2h 3m 4s\n").stats.estimatedSeconds)
        assertNull(parse("; total estimated time: soon\n").stats.estimatedSeconds)
        assertNull(parse("; total estimated time: 12\n").stats.estimatedSeconds)
    }

    @Test fun statsWithoutAColonOrNumberAreNull() {
        val stats = parse("; total filament length [mm] none\n; total filament weight [g] : heavy\n").stats
        assertNull(stats.filamentLengthMm); assertNull(stats.filamentGrams)
    }

    @Test fun oddCharactersChecksumsAndSpacingInCommands() {
        val layer = parse("M83\nN5 G1 X 1 Y2 E1 *71\n$ G1 X3 E1\nG1 X Y5 E1\nG1 X+4 E-.5 E1e0\n").layers.single()
        assertEquals(1f, layer.segments[3]); assertEquals(2f, layer.segments[4])
        assertEquals(3f, layer.segments[9])
    }

    @Test fun numberFormatsIncludeExponentsAndSigns() {
        val layer = parse("M83\nG1 X1e1 E1\nG1 X1E-1 E1\nG1 X5e E1\nG1 X-.5 E1\nG1 X+2e+0 E1\n").layers.single()
        val xs = (0 until layer.features.size).map { layer.segments[it * 6 + 3] }
        assertEquals(listOf(10f, 0.1f, 5f, -0.5f, 2f), xs)
    }

    @Test fun toolSelectionIgnoresOutOfRangeAndFractionalValues() {
        val code = "M83\nT1\nG1 X1 E1\nT150\nG1 X2 E1\nT2.5\nG1 X3 E1\nT-1\nG1 X4 E1\nT7\nG1 X5 E1\n"
        assertEquals(listOf(1, 1, 1, 1, 7), parse(code).layers.single().tools.map { it.toInt() })
    }

    @Test fun mCodeOnAGLineDoesNotReplaceTheGCode() {
        val layer = parse("M83\nG1 X1 M106 E1\n").layers.single()
        assertEquals(1, layer.features.size); assertEquals(1f, layer.segments[3])
    }

    @Test fun relativeMovesAccumulateInAllAxes() {
        val layer = parse("M83\n; CHANGE_LAYER\nG91\nG1 X1 Y2 Z3 E1\nG1 X1 Y1 Z1 E1\nG90\nG1 X0 Y0 Z0 E1\n").layers.single()
        assertEquals(listOf(1f, 2f, 3f), layer.segments.slice(3..5)); assertEquals(listOf(2f, 3f, 4f), layer.segments.slice(9..11))
        assertEquals(listOf(0f, 0f, 0f), layer.segments.slice(15..17))
    }

    @Test fun travelMovesAreOnlyKeptWhenAsked() {
        val code = "M83\nG0 X5 Y5\nG1 X6 Y6\nG1 X6 Y6 E1\nG1 X7 Y7 E1\n"
        assertEquals(1, parse(code).layers.single().features.size)
        val withTravel = parse(code, travel = true).layers.single()
        assertEquals(listOf(Feature.TRAVEL, Feature.TRAVEL, Feature.OTHER).map { it.ordinal.toByte() }, withTravel.features.toList().let { listOf(it[0], it[1], it[2]) })
    }

    @Test fun layersFromRisingZWithoutLayerComments() {
        val layers = parse("M83\nG1 X1 Y0 Z0.2 E1\nG1 X2 Y0 E1\nG1 X2 Y1 Z0.4 E1\nG1 Z0.4 X3 E1\n").layers
        assertEquals(listOf(0.2f, 0.4f), layers.map { it.z }); assertEquals(listOf(2, 2), layers.map { it.features.size })
    }

    @Test fun aRisingZBeforeAnyMoveHasBeenDrawnReusesTheLayer() {
        val layers = parse("M83\nG1 Z0.2\nG1 Z0.4\nG1 X1 E1\n").layers
        assertEquals(1, layers.size); assertEquals(0.4f, layers.single().z)
    }

    @Test fun explicitLayerCommentsIgnoreLaterZMoves() {
        val layers = parse("M83\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X1 Z0.2 E1\nG1 X2 Z0.6 E1\n; CHANGE_LAYER\nG1 X3 Z0.8 E1\n").layers
        assertEquals(2, layers.size); assertEquals(0.2f, layers[0].z); assertEquals(0.8f, layers[1].z)
    }

    @Test fun arcsWithoutCentreOffsetsAreStraightSegments() {
        val layer = parse("M83\nG2 X3 Y4 E1\n").layers.single()
        assertEquals(1, layer.features.size); assertEquals(listOf(3f, 4f), layer.segments.slice(3..4))
    }

    @Test fun zeroRadiusArcsAreStraightSegments() {
        val layer = parse("M83\nG1 X5 Y5 E1\nG3 X7 Y5 I0 J0 E1\n").layers.single()
        assertEquals(2, layer.features.size); assertEquals(7f, layer.segments[9])
    }

    @Test fun fullCirclesClockwiseAndCounterClockwiseCloseOnTheStart() {
        for (code in listOf("G2", "G3")) {
            val layer = parse("M83\nG1 X10 Y0 E1\n$code X10 Y0 I-5 J0 E1\n").layers.single()
            assertTrue(layer.features.size > 40, "$code made ${layer.features.size} segments")
            val n = layer.features.size
            assertEquals(10f, layer.segments[(n - 1) * 6 + 3]); assertEquals(0f, layer.segments[(n - 1) * 6 + 4])
            val midY = layer.segments[(n / 2) * 6 + 4]
            assertTrue(if (code == "G2") midY > 0f else midY < 0f, "$code mid y $midY")
        }
    }

    @Test fun tinyArcsAndHelicesAreTessellatedWithinHalfAMillimetre() {
        val tiny = parse("M83\nG1 X1 Y0 E1\nG3 X0 Y1 I-1 J0 E1\n").layers.single()
        assertTrue(tiny.features.size > 5)
        val helix = parse("M83\n; CHANGE_LAYER\nG1 X10 Y0 Z0 E1\nG3 X0 Y10 Z5 I-10 J0 E1\n").layers.single()
        assertEquals(5f, helix.segments[(helix.features.size - 1) * 6 + 5])
        for (i in 1 until helix.features.size) {
            val dx = helix.segments[i * 6 + 3] - helix.segments[i * 6]; val dy = helix.segments[i * 6 + 4] - helix.segments[i * 6 + 1]
            assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) <= 0.55f)
        }
    }

    @Test fun arcWithOnlyAJOffsetAndTravelArcs() {
        val j = parse("M83\nG1 X0 Y0 E1\nG2 X0 Y10 I0 J5 E1\n").layers.single()
        assertTrue(j.features.size > 5)
        val travel = parse("M83\nG1 X1 Y0 E1\nG2 X0 Y1 I-1 J0\n", travel = true).layers.single()
        assertEquals(Feature.TRAVEL.ordinal.toByte(), travel.features.last())
    }

    @Test fun everyFeatureNameMapsToItsCategory() {
        val names = listOf(
            "Outer wall" to Feature.OUTER_WALL, "Inner wall" to Feature.INNER_WALL, "Overhang wall" to Feature.OVERHANG_WALL,
            "Sparse infill" to Feature.SPARSE_INFILL, "Internal solid infill" to Feature.INTERNAL_SOLID_INFILL, "Top surface" to Feature.TOP_SURFACE,
            "Bottom surface" to Feature.BOTTOM_SURFACE, "Bridge" to Feature.BRIDGE, "Gap infill" to Feature.GAP_FILL, "Skirt" to Feature.SKIRT,
            "Brim" to Feature.BRIM, "Support" to Feature.SUPPORT, "Support interface" to Feature.SUPPORT_INTERFACE,
            "Support transition" to Feature.SUPPORT_TRANSITION, "Prime tower" to Feature.PRIME_TOWER, "Ironing" to Feature.IRONING,
            "Custom" to Feature.CUSTOM, "Travel" to Feature.TRAVEL,
        )
        val code = "M83\n" + names.mapIndexed { i, (n, _) -> "; FEATURE: $n\nG1 X${i + 1} E1\n" }.joinToString("")
        assertEquals(names.map { it.second.ordinal.toByte() }, parse(code).layers.single().features.toList())
    }
}
