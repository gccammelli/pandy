package io.github.gccammelli.pandy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.pandy.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private val platform = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) { opened += url }
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = Unit
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = Unit
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
    }

    @Test fun fallbackPandaMarkRendersItsArtwork() {
        compose.setContent { PandaMarkFallback(Modifier.size(100.dp).testTag("fallback")) }
        val pixels = compose.onNodeWithTag("fallback").captureToImage().toPixelMap()
        val centre = pixels[pixels.width / 2, pixels.height / 2]
        val corner = pixels[0, 0]
        kotlin.test.assertNotEquals(corner, centre)
    }

    @Test fun everySharedComponentAcceptsEachOptionalParameterOnAndOff() {
        val step = mutableStateOf(0); val tick = mutableStateOf(0)
        var dismissed = 0; var confirmed = 0; var navClicks = 0
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            tick.value // a recomposition with unchanged arguments lets the components skip
            when (step.value) {
                0 -> Column {
                    T("plain"); T("size", size = 14.sp); T("colour", color = Color.Red); T("weight", weight = FontWeight.Bold)
                    T("modifier", modifier = Modifier.padding(2.dp)); T("align", align = TextAlign.End); T("line height", lh = 20.sp); T("lines", maxLines = 1)
                    T("all", 12.sp, Color.Blue, FontWeight.Medium, Modifier.padding(1.dp), TextAlign.Center, 16.sp, 2)
                    Gap(); Gap(4.dp)
                    Bar(.5f, Color.Red); Bar(.5f, Color.Red, Modifier.padding(1.dp)); Bar(.5f, Color.Red, track = Color.Gray); Bar(.5f, Color.Red, height = 8.dp); Bar(2f, Color.Red); Bar(-1f, Color.Red)
                    SmallIcon(Icons.Default.Close); SmallIcon(Icons.Default.Close, tint = Color.Red); SmallIcon(Icons.Default.Close, size = 12.dp)
                    NavRow("plain"); NavRow("value", value = "v"); NavRow("colour", labelColor = Color.Red); NavRow("no chevron", chevron = false)
                    NavRow("leading", leading = { T("L") }); NavRow("click", onClick = { navClicks++ })
                    NavRow("everything", "value", Color.Red, false, { T("L") }) { navClicks++ }
                    ToggleRow("on", true) {}; ToggleRow("disabled", false, enabled = false) {}; Toggle(true) {}; Toggle(false, enabled = false) {}
                    Radio(true, "selected") {}; Radio(false, "unselected") {}; Radio(true, "disabled", enabled = false) {}
                    AccentButton("go") {}; AccentButton("modifier", Modifier.padding(1.dp)) {}; AccentButton("off", enabled = false) {}
                    OutlineButton("outline", Color.Red) {}; OutlineButton("outline off", Color.Red, enabled = false) {}; OutlineButton("outline modifier", Color.Red, Modifier.padding(1.dp)) {}
                    PrinterArt(Modifier.size(80.dp), ams = false, lit = false); PrinterArt(Modifier.size(80.dp), ams = true, lit = true)
                    PrinterArt(Modifier.size(80.dp), ams = true, lit = false, dark = true); PrinterArt(Modifier.size(80.dp), ams = true, lit = true, dark = false, amsColors = listOf(Color.Red, Color.Green))
                    PandaMark(Modifier.size(40.dp))
                }
                1 -> Page("plain", null) { T("body") }
                2 -> Page("full", { dismissed++ }, Color.Yellow, { T("action") }, { T("bottom") }) { T("body") }
                3 -> Sheet({ dismissed++ }) { T("untitled, default confirm") }
                4 -> Sheet({ dismissed++ }, "Centred", true, "Go", { confirmed++ }) { T("centred title") }
                5 -> Sheet({ dismissed++ }, "Left", false, null) { T("no confirm button") }
                6 -> Sheet({ dismissed++ }, "Disabled", confirm = "Send", confirmEnabled = false) { T("disabled confirm") }
                else -> Column { SetupNote("Needs a thing", "docs/setup.md"); SetupNote("Modifier", "docs/slicer.md", Modifier.padding(2.dp)) }
            }
        } } }
        for (s in 0..7) {
            compose.runOnIdle { step.value = s }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { tick.value++ }; compose.waitForIdle()
        }
        compose.onNodeWithText("Needs a thing").performClick()
        assertEquals(1, opened.size)
        compose.runOnIdle { step.value = 4 }; compose.waitForIdle()
        compose.onNodeWithText("Go").performClick(); compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, confirmed); assertEquals(1, dismissed)
        compose.runOnIdle { step.value = 2 }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(2, dismissed)
    }

    @Test fun cardsPlaceholdersImagesAndTemperatureEntryCoverTheirOptionalParts() {
        val step = mutableStateOf(0)
        var set = mutableListOf<Int>()
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme {
            when (step.value) {
                0 -> Column {
                    MiniCard("tap", "value", Modifier, null) { }
                    MiniCard("plain", "value", Modifier, null, null)
                    MiniCard("trailing", "value", Modifier, { T("trailing") }, null)
                    MiniCard("both", "value", Modifier, { T("both") }) { }
                    Empty("nothing here"); Empty("custom icon", Icons.Default.Close)
                    HaImage(null, Modifier.size(10.dp)); HaImage("/api/bambu_lab/file_cache/x.png", Modifier.size(10.dp)); HaImage("/api/camera_proxy/x", Modifier.size(10.dp))
                }
                1 -> TempSheet("Empty target", "hint", listOf(0, 50), null, 0..100, {}) { set += it }
                2 -> TempSheet("Zero target", "hint", listOf(0, 50), 0.0, 0..100, {}) { set += it }
                else -> TempSheet("Set target", "hint", listOf(0, 50), 42.0, 0..100, {}) { set += it }
            }
        } } }
        for (i in 0..3) { compose.runOnIdle { step.value = i }; compose.waitForIdle(); compose.mainClock.advanceTimeBy(300) }
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(42), set)
    }
}
