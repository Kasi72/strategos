package com.drkkr.strategos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drkkr.strategos.ui.calm.talk.TalkActions
import com.drkkr.strategos.ui.calm.talk.TalkScreen
import com.drkkr.strategos.ui.calm.talk.TalkUiState
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Power Mode, PM5.1: the Talk header switch, the banner and the one-time explainer (I-P1, I-P4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerSwitchUiTest {
    @get:Rule val compose = createComposeRule()

    private val banner = "Power Mode — hardball moves for work and negotiations."
    private fun base() = TalkUiState(projectName = "Raise", loading = false)

    @Test fun TC_PM_UI_01_noChipWhenPowerModeIsNotAvailable() {
        compose.setContent { StrategosTheme(darkTheme = false) { TalkScreen(base().copy(powerAvailable = false), TalkActions()) } }
        compose.onNodeWithText("⚡ Power").assertDoesNotExist()
        compose.onNodeWithText(banner).assertDoesNotExist()
    }

    @Test fun TC_PM_UI_02_theChipTurnsItOnAndOff_andTheBannerFollows() {
        val calls = mutableListOf<Boolean>()
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            StrategosTheme(darkTheme = false) {
                TalkScreen(base().copy(powerAvailable = true, powerExplained = true, powerMode = on),
                    TalkActions(onSetPowerMode = { calls += it; on = it }))
            }
        }
        compose.onNodeWithText(banner).assertDoesNotExist()
        compose.onNodeWithText("⚡ Power").performClick()
        compose.onNodeWithText(banner).assertExists()
        compose.onNodeWithText("⚡ Power").performClick()
        compose.onNodeWithText(banner).assertDoesNotExist()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun TC_PM_UI_03_theExplainerShowsOnlyOnTheFirstEnableInAConversation() {
        val calls = mutableListOf<String>()
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            var explained by remember { mutableStateOf(false) }
            StrategosTheme(darkTheme = false) {
                TalkScreen(base().copy(powerAvailable = true, powerExplained = explained, powerMode = on),
                    TalkActions(onSetPowerMode = { calls += "set $it"; on = it }, onPowerExplained = { calls += "explained"; explained = true }))
            }
        }
        // first enable: the explainer, then "Turn on"
        compose.onNodeWithText("⚡ Power").performClick()
        compose.onNodeWithText("It's off for partners, family and friends", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(2_000)        // let the sheet finish sliding in before tapping inside it
        compose.waitForIdle()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertEquals("after Turn on", listOf("explained", "set true"), calls)
        compose.onNodeWithText(banner).assertExists()
        // off, then on again: no explainer this time
        compose.onNodeWithText("⚡ Power").performClick()
        compose.onNodeWithText("⚡ Power").performClick()
        compose.onNodeWithText("It's off for partners, family and friends", substring = true).assertDoesNotExist()
        compose.onNodeWithText(banner).assertExists()
        assertEquals(listOf("explained", "set true", "set false", "set true"), calls)
    }
}
