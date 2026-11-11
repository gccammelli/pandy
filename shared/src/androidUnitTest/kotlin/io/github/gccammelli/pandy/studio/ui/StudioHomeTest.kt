// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.gccammelli.pandy.PlatformUi
import io.github.gccammelli.pandy.studio.LocalPlatform
import io.github.gccammelli.pandy.studio.Platform
import io.github.gccammelli.pandy.studio.slicer.EngineChoice
import io.github.gccammelli.pandy.studio.slicer.EngineInfo
import io.github.gccammelli.pandy.studio.studio.SliceState
import io.github.gccammelli.pandy.studio.studio.Studio
import io.github.gccammelli.pandy.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home screen drawing and the engine sheet's actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioHomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var engineSwitchWorks = true
    private val host: Platform = object : Platform by StudioTestHost() {
        override fun forEngine(id: String): Platform = if (engineSwitchWorks) this else error("Engine selection is unavailable.")
        override suspend fun engineInfo() = EngineInfo("02.08.02.61", "enginehash", "resourcehash")
        override suspend fun engines() = listOf(EngineChoice("bambu", "Bambu Studio", "02.08.02.61"), EngineChoice("orca", "OrcaSlicer", "2.5"))
    }
    private val studio = Studio(host, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).also { runBlocking { it.load().join() } }
    private val ui = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Throwable?) -> Unit) = done(null)
        override fun openApp(androidPackage: String, storeUrl: String) = Unit
        override fun landscape(on: Boolean) = Unit
        override val canNotify = true
        override fun requestNotifications() = Unit
        override fun watchInBackground(on: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }

    @Before @After fun clean() { StudioSession.reset(); StudioSession.notice = null }

    private fun show(nav: StudioNav = StudioNav()) = compose.setContent {
        CompositionLocalProvider(io.github.gccammelli.pandy.LocalPlatform provides ui, LocalPlatform provides host) { AppTheme { Home(studio, nav) } }
    }
    private fun count(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().size

    @Test fun theLogoAndSampleIconsDrawAtTheirRenderedSize() {
        show()
        val image = compose.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        for (name in listOf("Cube", "Gear", "Vase", "Torus")) compose.onNodeWithText(name).assertExists()
    }

    @Test fun choosingAnotherEngineClosesTheSheetAndDropsThePendingPrint() {
        StudioSession.print = StudioPrint(byteArrayOf(1), "Mochi.gcode.3mf", "Bambu Lab X1 Carbon 0.4 nozzle", emptyList())
        show()
        compose.onNodeWithText("Slicing engine").performClick()
        compose.onNodeWithText("OrcaSlicer 2.5").performClick()
        compose.waitUntil(5000) { !studio.loading }
        compose.runOnIdle { assertNull(StudioSession.print); assertEquals(0, count("Reload profiles")) }
    }

    @Test fun reloadingProfilesClosesTheSheetAndDropsThePendingPrint() {
        StudioSession.print = StudioPrint(byteArrayOf(1), "Mochi.gcode.3mf", "Bambu Lab X1 Carbon 0.4 nozzle", emptyList())
        show()
        compose.onNodeWithText("Slicing engine").performClick()
        compose.onNodeWithText("Reload profiles").performClick()
        compose.waitUntil(5000) { !studio.loading }
        compose.runOnIdle { assertNull(StudioSession.print); assertEquals(0, count("Reload profiles")); assertEquals("bambu", studio.engine?.id) }
    }

    @Test fun anEngineSwitchThatFailsShowsTheReasonAndKeepsThePrint() {
        engineSwitchWorks = false
        val pending = StudioPrint(byteArrayOf(1), "Mochi.gcode.3mf", "Bambu Lab X1 Carbon 0.4 nozzle", emptyList())
        StudioSession.print = pending
        show()
        compose.onNodeWithText("Slicing engine").performClick()
        compose.onNodeWithText("OrcaSlicer 2.5").performClick()
        compose.runOnIdle { assertEquals("Engine selection is unavailable.", StudioSession.notice); assertTrue(StudioSession.print === pending) }
        compose.onNodeWithText("Reload profiles").performClick()
        compose.runOnIdle { assertEquals("Engine selection is unavailable.", StudioSession.notice); assertTrue(StudioSession.print === pending) }
    }

    @Test fun whileSlicingTheEngineSheetAsksToWaitAndOffersNoReload() {
        studio.state = SliceState.Running("Generating walls", .4f)
        show()
        compose.onNodeWithText("Slicing engine").performClick()
        assertEquals(1, count("Wait for the current operation to finish"))
        assertEquals(0, count("Reload profiles"))
    }

    @Test fun beforeTheEngineHasLoadedBothRowsSayLoadingAndAfterAFailedLoadUnavailable() {
        var failing = false
        val unloaded = Studio(object : Platform by host {
            override suspend fun engineInfo(): EngineInfo = if (failing) error("The plugin stopped.") else host.engineInfo()
            override suspend fun engines(): List<EngineChoice> = if (failing) error("The plugin stopped.") else host.engines()
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        compose.setContent {
            CompositionLocalProvider(io.github.gccammelli.pandy.LocalPlatform provides ui, LocalPlatform provides host) { AppTheme { Home(unloaded, StudioNav()) } }
        }
        compose.runOnIdle { assertNull(unloaded.engine); assertNull(unloaded.loadError); assertEquals(2, count("Loading…")); assertEquals(0, count("Slicer plugin")) }
        failing = true
        runBlocking { unloaded.load().join() }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(unloaded.engine); assertEquals(2, count("Unavailable")); assertEquals(0, count("Loading…")) }
    }
}
