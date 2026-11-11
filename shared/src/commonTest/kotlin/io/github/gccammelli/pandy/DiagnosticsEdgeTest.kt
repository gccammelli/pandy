// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy

import io.github.gccammelli.pandy.studio.io.Stl
import io.github.gccammelli.pandy.studio.model.Shapes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IPv6 redaction on valid and invalid shapes, optional report fields, and STL preview simplification. */
class DiagnosticsEdgeTest {
    private fun masked(s: String) = "[redacted]" in redact(s)

    @Test fun validIpv6AddressesAreMasked() {
        for (a in listOf("2001:db8::1", "1:2:3:4:5:6:7:8", "fe80::1%eth0", "::1", "2001:db8:0:0:0:0:0:1", "1:2:3:4:5:6::")) assertTrue(masked("addr $a end"), a)
    }

    @Test fun invalidIpv6ShapesAreLeftAlone() {
        for (a in listOf("1::2::3", "12345::1", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8:9", "1:2:3")) {
            assertEquals("addr $a end", redact("addr $a end"), a)
        }
    }

    @Test fun reportsShowUnknownForMissingOptionalFieldsAndNoneForNoAms() {
        val report = diagnosticsReport(DiagnosticsInfo("1", "test", null, "demo", 0, null, null, emptyList(), null), listOf("line"))
        assertTrue("OS version: Unknown" in report && "Camera type: Unknown" in report && "Printer model: Unknown" in report && "Firmware: Unknown" in report)
        assertTrue("AMS units: 0" in report && "AMS types: None" in report && report.trimEnd().endsWith("line"))
        val full = diagnosticsReport(DiagnosticsInfo("2", "test", "Android 15", "lan", 2, "X1C", "01.02", listOf("AMS", "AMS HT"), "rtsp"), emptyList())
        assertTrue("OS version: Android 15" in full && "Camera type: rtsp" in full && "AMS types: AMS, AMS HT" in full)
        assertFalse("Unknown" in full)
    }

    @Test fun aBigStlIsSimplifiedToTheTriangleBudgetAndDuplicatesAreDrawnOnce() {
        val binary = Stl.writeBinary(Shapes.torus(seg = 24, sides = 12))
        val full = stlPreviewMesh(binary, maxTriangles = 100_000)
        val small = stlPreviewMesh(binary, maxTriangles = 200)
        assertTrue(full.triangles.size > 500, "${full.triangles.size}")
        assertTrue(small.triangles.size < full.triangles.size)
        val doubled = Stl.writeBinary(Shapes.box(10f, 10f, 10f))
        val twice = doubled.copyOf(84 + 50 * 24).also { out -> doubled.copyInto(out, 84 + 50 * 12, 84, 84 + 50 * 12); out[80] = 24 }
        assertEquals(24, stlPreviewMesh(twice).triangles.size)
        assertEquals(12, stlPreviewMesh(twice, maxTriangles = 12).triangles.size)
        assertEquals(12, stlPreviewMesh(doubled, maxTriangles = 12).triangles.size)
    }
}
