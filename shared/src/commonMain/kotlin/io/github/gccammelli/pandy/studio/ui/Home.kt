// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.model.Shapes
import io.github.gccammelli.pandy.studio.studio.Studio
import kotlinx.coroutines.launch

@Composable
fun Home(studio: Studio, nav: StudioNav) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var printerSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Gap(20.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                T("Studio", 26.sp, weight = FontWeight.Bold)
                T("Slice your own models on this phone", 14.sp, C.Sub)
            }
        }
        Gap(24.dp)
        // Start: the hero card.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(C.Accent, C.Accent.copy(alpha = .78f), Color(0xFF6A1B9A).copy(alpha = .85f)))).padding(20.dp)) {
            T("New plate", 22.sp, Color.White, FontWeight.Bold)
            Gap(4.dp)
            T("Open an STL or 3MF, arrange it, pick settings, slice, and print it.", 14.sp, Color.White.copy(alpha = .9f), lh = 19.sp)
            Gap(18.dp)
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).clickable {
                scope.launch { if (pickModels(platform, studio)) nav.go(StudioScreen.Plate) }
            }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileOpen, null, tint = C.Accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    T("Open a model", 16.sp, C.Accent, FontWeight.Medium)
                }
            }
            if (studio.scene.objects.isNotEmpty()) {
                Gap(10.dp)
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .18f)).clickable { nav.go(StudioScreen.Plate) },
                    contentAlignment = Alignment.Center) { T("Back to the plate (${studio.scene.objects.size})", 15.sp, Color.White, FontWeight.Medium) }
            }
        }
        StudioSession.notice?.let { Gap(8.dp); T(it, 13.sp, C.Red) }
        Gap(20.dp)
        T("Try a sample", 15.sp, C.Sub, FontWeight.Medium, Modifier.padding(start = 4.dp))
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shapes.samples.forEach { (name, make) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { studio.addSample(name, make()); nav.go(StudioScreen.Plate) }
                    .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SampleIcon(name, Modifier.size(38.dp))
                    Gap(6.dp); T(name, 13.sp)
                }
            }
        }
        Gap(20.dp)
        Card(padding = PaddingValues(0.dp)) {
            val p = studio.printer
            NavRow("Printer", p?.name?.removePrefix("Bambu Lab ") ?: if (studio.loadError != null) "Unavailable" else "Loading…",
                leading = { Icon(Icons.Default.Print, null, tint = C.Accent, modifier = Modifier.size(22.dp)) }) { printerSheet = true }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            NavRow("Slicing engine", studio.engine?.let { "Bambu Studio ${it.version}".trim() } ?: "Slicer plugin",
                leading = { Icon(Icons.Default.Verified, null, tint = C.Ok, modifier = Modifier.size(22.dp)) }) { nav.go(StudioScreen.About) }
        }
        studio.loadError?.let {
            Gap(8.dp); T("Couldn't load Bambu's profiles: $it", 13.sp, C.Red)
            Gap(8.dp); OutlineButton("Try again", C.Accent) { studio.load() }
        }
        Gap(28.dp)
    }
    if (printerSheet) PrinterSheet(studio) { printerSheet = false }
}

/** Pandy Studio's mark: a magenta plate with a sliced layer stack. */
@Composable
fun Logo(modifier: Modifier) = Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(C.Accent)) {
    val w = size.width; val h = size.height
    for (i in 0 until 4) {
        val y = h * (.68f - i * .13f); val inset = w * (.2f + i * .06f)
        drawRoundRect(Color.White.copy(alpha = .55f + i * .15f), Offset(inset, y), androidx.compose.ui.geometry.Size(w - 2 * inset, h * .08f),
            androidx.compose.ui.geometry.CornerRadius(h * .04f))
    }
    drawRect(Color.White.copy(alpha = .3f), Offset(w * .12f, h * .8f), androidx.compose.ui.geometry.Size(w * .76f, h * .04f))
}

@Composable
private fun SampleIcon(name: String, modifier: Modifier) {
    val accent = C.Accent
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = Stroke(width = w * .07f)
        when (name) {
            "Cube" -> { val p = Path().apply { moveTo(w * .5f, h * .1f); lineTo(w * .9f, h * .3f); lineTo(w * .9f, h * .75f); lineTo(w * .5f, h * .95f); lineTo(w * .1f, h * .75f); lineTo(w * .1f, h * .3f); close() }
                drawPath(p, accent.copy(alpha = .18f)); drawPath(p, accent, style = s)
                drawLine(accent, Offset(w * .1f, h * .3f), Offset(w * .5f, h * .5f), s.width); drawLine(accent, Offset(w * .9f, h * .3f), Offset(w * .5f, h * .5f), s.width)
                drawLine(accent, Offset(w * .5f, h * .5f), Offset(w * .5f, h * .95f), s.width) }
            "Gear" -> { drawCircle(accent.copy(alpha = .18f), w * .38f); drawCircle(accent, w * .38f, style = s); drawCircle(accent, w * .12f, style = s)
                for (i in 0 until 8) { val a = i * kotlin.math.PI / 4; drawLine(accent, Offset(w / 2 + (w * .38f * kotlin.math.cos(a)).toFloat(), h / 2 + (w * .38f * kotlin.math.sin(a)).toFloat()),
                    Offset(w / 2 + (w * .48f * kotlin.math.cos(a)).toFloat(), h / 2 + (w * .48f * kotlin.math.sin(a)).toFloat()), w * .1f) } }
            "Vase" -> { val p = Path().apply { moveTo(w * .35f, h * .05f); cubicTo(w * .1f, h * .4f, w * .05f, h * .7f, w * .3f, h * .95f); lineTo(w * .7f, h * .95f)
                cubicTo(w * .95f, h * .7f, w * .9f, h * .4f, w * .65f, h * .05f); close() }; drawPath(p, accent.copy(alpha = .18f)); drawPath(p, accent, style = s) }
            else -> { drawCircle(accent.copy(alpha = .18f), w * .42f); drawCircle(accent, w * .42f, style = s); drawCircle(C.Card, w * .16f); drawCircle(accent, w * .16f, style = s) }
        }
    }
}

@Composable
fun Dot(color: Color, size: Int = 18, ring: Boolean = false) = Box(Modifier.size(size.dp).clip(CircleShape).background(color)
    .let { if (ring) it.background(Color.Transparent) else it })
