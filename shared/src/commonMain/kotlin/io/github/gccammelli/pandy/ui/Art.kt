package io.github.gccammelli.pandy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * Drawn stand-ins for Handy's product photos (Bambu's renders aren't ours to ship). Sizes are fractions of the
 * canvas so they scale with the card.
 */

/** Pandy's mark: a panda face. */
@Composable
fun PandaMark(modifier: Modifier) = Canvas(modifier) {
    val w = size.minDimension
    val ink = Color(0xFF1A1A1A)
    drawCircle(Color(0xFFAE006D), w / 2)
    drawCircle(ink, w * .11f, Offset(w * .3f, w * .3f)); drawCircle(ink, w * .11f, Offset(w * .7f, w * .3f))
    drawOval(Color.White, Offset(w * .2f, w * .3f), Size(w * .6f, w * .5f))
    for (x in listOf(.38f, .62f)) {
        drawOval(ink, Offset(w * x - w * .075f, w * .43f), Size(w * .15f, w * .17f))
        drawCircle(Color.White, w * .025f, Offset(w * x, w * .5f))
    }
    drawOval(ink, Offset(w * .45f, w * .62f), Size(w * .1f, w * .06f))
}

/** An enclosed CoreXY printer seen from the front, with an AMS on top when [ams]. [lit]: chamber light on. */
@Composable
fun PrinterArt(modifier: Modifier, ams: Boolean, lit: Boolean, dark: Boolean = ThemeMode.dark) = Canvas(modifier) {
    val bodyColor = if (dark) Color(0xFF3A3A3C) else Color(0xFF2C2C2E)
    val edge = if (dark) Color(0xFF4A4A4E) else Color(0xFF3C3C40)
    // Fit a 1 : 1.12 (w : h) printer, plus the AMS above, into the canvas.
    val amsH = if (ams) .2f else 0f
    val unitH = size.height / (1.12f + amsH)
    val bw = minOf(size.width * .78f, unitH)
    val bh = bw * 1.12f
    val left = (size.width - bw) / 2
    val top = size.height - bh - 2f
    if (ams) {
        val ah = bw * .2f
        drawRoundRect(Color(0xFF3A3A3E), Offset(left + bw * .04f, top - ah), Size(bw * .92f, ah), CornerRadius(bw * .03f))
        drawRoundRect(Color(0x55FFFFFF), Offset(left + bw * .1f, top - ah * .82f), Size(bw * .8f, ah * .55f), CornerRadius(bw * .02f))
        listOf(0xFFFFFFFF, 0xFF161616, 0xFF00AE42, 0xFF9E9E9E).forEachIndexed { i, c ->
            drawCircle(Color(c), ah * .2f, Offset(left + bw * (.22f + i * .19f), top - ah * .55f))
        }
    }
    drawRoundRect(bodyColor, Offset(left, top), Size(bw, bh), CornerRadius(bw * .035f))
    // Glass door and the chamber behind it.
    val gx = left + bw * .08f; val gy = top + bh * .12f; val gw = bw * .84f; val gh = bh * .8f
    drawRoundRect(if (lit) Color(0xFF55524A) else Color(0xFF1C1C1E), Offset(gx, gy), Size(gw, gh), CornerRadius(bw * .02f))
    if (lit) drawRoundRect(Brush.verticalGradient(listOf(Color(0x66FFF3D0), Color(0x00FFF3D0)), gy, gy + gh * .7f), Offset(gx, gy), Size(gw, gh), CornerRadius(bw * .02f))
    // Gantry, toolhead and bed.
    drawRect(Color(0xFF6B6B6E), Offset(gx + gw * .04f, gy + gh * .16f), Size(gw * .92f, gh * .03f))
    drawRoundRect(Color(0xFF8E8E92), Offset(gx + gw * .42f, gy + gh * .1f), Size(gw * .16f, gh * .14f), CornerRadius(4f))
    drawRect(Color(0xFF77777A), Offset(gx + gw * .1f, gy + gh * .72f), Size(gw * .8f, gh * .035f))
    drawRoundRect(edge, Offset(gx, gy), Size(gw, gh), CornerRadius(bw * .02f), style = Stroke(bw * .012f))
    // Screen top left, handle on the right, feet.
    drawRoundRect(Color(0xFF111113), Offset(left + bw * .08f, top + bh * .025f), Size(bw * .2f, bh * .07f), CornerRadius(4f))
    drawRoundRect(Color(0xFF9A9AA0), Offset(gx + gw * .88f, gy + gh * .4f), Size(bw * .015f, gh * .18f), CornerRadius(4f))
    drawRect(Color(0xFF1A1A1A), Offset(left + bw * .06f, top + bh - 2f), Size(bw * .1f, 3f))
    drawRect(Color(0xFF1A1A1A), Offset(left + bw * .84f, top + bh - 2f), Size(bw * .1f, 3f))
}

/** The toolhead in Handy's Nozzle & Extruder card: a grey hotend with its fan and a tube going up. */
@Composable
fun ToolheadArt(modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val body = if (ThemeMode.dark) Color(0xFF55555A) else Color(0xFF3A3A3E)
    drawRect(Color(0x55A0A0A6), Offset(w * .42f, 0f), Size(w * .08f, h * .3f))
    drawRect(Color(0x33A0A0A6), Offset(w * .56f, h * .05f), Size(w * .28f, h * .3f))
    drawRoundRect(body, Offset(w * .2f, h * .25f), Size(w * .6f, h * .62f), CornerRadius(w * .08f))
    drawCircle(Color(0xFF1E1E20), w * .2f, Offset(w * .5f, h * .52f))
    drawCircle(Color(0xFF2E2E32), w * .12f, Offset(w * .5f, h * .52f))
    drawCircle(Color(0xFF6A6A70), w * .03f, Offset(w * .5f, h * .52f))
    drawRect(Color(0xFF8A8A90), Offset(w * .26f, h * .29f), Size(w * .48f, h * .06f))
    val n = Path().apply { moveTo(w * .44f, h * .87f); lineTo(w * .56f, h * .87f); lineTo(w * .5f, h * .97f); close() }
    drawPath(n, Color(0xFFB08A3E))
}

/** A spool seen side-on in [color] (AMS slot tiles); [remain] 0–100 or -1 for unknown, drawn as the fill height. */
fun DrawScope.spool(color: Color, remain: Int, empty: Boolean) {
    val w = size.width; val h = size.height
    if (empty) {
        drawRoundRect(Color(0x22888888), Offset(0f, 0f), size, CornerRadius(w * .18f))
        drawRoundRect(Color(0x55888888), Offset(0f, 0f), size, CornerRadius(w * .18f), style = Stroke(2f))
        return
    }
    drawRoundRect(color, Offset(0f, 0f), size, CornerRadius(w * .18f))
    if (remain in 0..99) {
        val used = h * (1 - remain / 100f)
        drawRoundRect(Color(0x66FFFFFF), Offset(0f, 0f), Size(w, used), CornerRadius(w * .18f))
    }
    val light = (color.red * .3f + color.green * .59f + color.blue * .11f) > .8f
    if (light) drawRoundRect(Color(0x33000000), Offset(0f, 0f), size, CornerRadius(w * .18f), style = Stroke(2f))
}
