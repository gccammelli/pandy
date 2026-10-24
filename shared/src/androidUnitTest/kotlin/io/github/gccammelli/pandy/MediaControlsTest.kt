package io.github.gccammelli.pandy

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class MediaControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var position=0L
    private var duration=60_000L
    private var ready=true
    private var isPlaying=false
    private var state=Player.STATE_BUFFERING
    private val seeks=mutableListOf<Long>()
    private val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { proxy,method,args ->
        when(method.name) {
            "getCurrentPosition" -> position
            "getDuration" -> duration
            "isPlaying" -> isPlaying
            "getPlayWhenReady" -> ready
            "getPlaybackState" -> state
            "setPlayWhenReady" -> { ready=args!![0] as Boolean; null }
            "seekTo" -> { position=args!![0] as Long; seeks+=position; null }
            "hashCode" -> 1
            "equals" -> proxy===args?.get(0)
            "toString" -> "Test media transport"
            else -> error("Unexpected player call ${method.name}")
        }
    } as Player

    @Test fun pausePlayAndReplayUseTheActualPlaybackState() {
        compose.setContent { Controls(player,Modifier) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Pause").performClick()
        assertFalse(ready)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue(ready); assertTrue(seeks.isEmpty())
        compose.runOnIdle { state=Player.STATE_ENDED; ready=false }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(listOf(0L),seeks); assertTrue(ready)
        compose.runOnIdle { state=Player.STATE_READY; isPlaying=true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test fun scrubberSeeksOnReleaseAndUnknownDurationDoesNotBecomeNegativeTime() {
        compose.setContent { Controls(player,Modifier) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("1:00").assertExists()
        compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0f,0f..60_000f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30_000f) }
        compose.waitForIdle()
        assertEquals(30_000L,seeks.last())
        compose.onNodeWithText("0:30").assertExists()
        compose.runOnIdle { duration=-1; position=0 }
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodesWithText("0:00").assertCountEquals(2)
    }
}
