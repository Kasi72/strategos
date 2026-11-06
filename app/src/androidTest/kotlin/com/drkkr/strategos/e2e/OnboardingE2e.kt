package com.drkkr.strategos.e2e

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingE2e : E2eBase() {
    private companion object { const val NUDGE = "Add your AI key to get started." }

    /** TC_ID_E2E_01. Since 28 Oct (c489ef9): with no key saved, the first button opens Settings with a short explanation and the key box ready. */
    @Test fun TC_ID_E2E_01_ONB_01_firstRunWithNoKeyOpensSettingsWithTheKeyBoxReady() {
        reset(withKey = false, onboarded = false); launch()
        waitForText("Say it the way you’d tell a friend.")
        text("Describe a situation", substring = true).performClick()
        waitForText(NUDGE, substring = true)
        waitForText("Paste key")
        assertTrue(!hasText("Say it the way you’d tell a friend."))
    }

    @Test fun TC_E2E_ONB_02_skippedOnceOnboardedOrWhenProjectsExist() {
        reset(withKey = false, onboarded = false); seedProject("Existing project"); launch()
        waitForText("Existing project")
        assertTrue(!hasText("Say it the way you’d tell a friend."))
    }

    @Test fun TC_E2E_ONB_03_sendCreatesProjectAndOpensTalkWithTheCoachReply() {
        requireLiveKey()
        reset(withKey = true, onboarded = false); launch()
        waitForText("Say it the way you’d tell a friend.")
        text("Describe a situation", substring = true).performClick()
        waitForText("What’s on your mind?")
        typeInto(0, "My brother wants me to co-sign a loan for his cafe and my wife is against it.")
        desc("Send situation").performClick()
        waitForText("My brother wants me to co-sign", substring = true, timeoutMs = 20_000)   // user bubble in Talk
        waitForCoachReply()
        rule.waitForIdle()
        // onboarding never shows again
        assertTrue(!hasText("Say it the way you’d tell a friend."))
    }
}
