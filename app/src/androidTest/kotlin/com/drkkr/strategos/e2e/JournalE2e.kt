package com.drkkr.strategos.e2e

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalE2e : E2eBase() {

    @Test fun TC_E2E_JRN_01_firstTimeShowsTheGentleEmptyState() {
        reset(withKey = false); launch()
        openTab("Journal")
        waitForText("Nothing here yet", substring = true)
        waitForText("Only yours", substring = true)
    }

    @Test fun TC_E2E_JRN_02_saveWithAMoodShowsAToastAndTheEntry() {
        reset(withKey = false); launch()
        openTab("Journal")
        waitForText("Nothing here yet", substring = true)
        typeInto(0, "Could not sleep, thinking about the loan")
        waitForText("Hopeful"); text("Hopeful").performClick()
        // The toast lasts 2.2 s of compose time, which the test clock would skip, so step the clock by hand.
        rule.mainClock.autoAdvance = false
        text("Save privately").performClick()
        var seen = false
        repeat(200) { if (!seen) { rule.mainClock.advanceTimeByFrame(); Thread.sleep(25); seen = hasText("Recorded privately") } }
        rule.mainClock.autoAdvance = true
        assertTrue("toast never appeared", seen)
        waitForText("Could not sleep, thinking about the loan")
        waitForText("TODAY")
        assertTrue(!hasText("Nothing here yet", substring = true))
    }

    @Test fun TC_E2E_JRN_03_entriesSurviveLeavingAndReturning() {
        reset(withKey = false); launch()
        openTab("Journal")
        waitForText("Nothing here yet", substring = true)
        typeInto(0, "A note to keep")
        text("Save privately").performClick()
        waitForText("A note to keep")
        openTab("Settings"); waitForText("AI counsel")
        openTab("Journal"); waitForText("A note to keep")
    }
}
