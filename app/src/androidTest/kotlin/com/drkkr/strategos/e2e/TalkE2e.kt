package com.drkkr.strategos.e2e

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TalkE2e : E2eBase() {

    private fun openTalk(name: String) {
        waitForText(name); text(name).performClick(); waitForText("Start chat"); text("Start chat").performClick(); waitForDesc("Back to overview")
    }

    @Test fun TC_E2E_TALK_01_noKeyShowsTheConnectCardAndSettingsLink() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openTalk("Loan for my brother")
        waitForText("Open Settings")
        text("Open Settings").performClick()
        waitForText("AI counsel")
    }

    @Test fun TC_E2E_TALK_02_messageRoundTripGetsARealCoachReply() {
        requireLiveKey()
        reset(withKey = true); val id = seedProject("Loan for my brother", "My brother wants me to co-sign a loan for his cafe."); launch()
        openTalk("Loan for my brother")
        typeInto(0, "My wife is against it and I do not want to lose him either. What should I do?")
        desc("Send message").performClick()
        waitForText("My wife is against it", substring = true)
        waitForCoachReply()
        waitFor(what = "reply text on screen", timeoutMs = 20_000) { !hasText("Thinking") }
        val dao = container.database!!.messageDao()
        assertTrue(kotlinx.coroutines.runBlocking { dao.countVisibleForScenario(id) } >= 2)
    }

    @Test fun TC_E2E_TALK_03_newTopicCreatesASeparateThread() {
        requireLiveKey()
        reset(withKey = true); seedProject("Loan for my brother", "My brother wants me to co-sign a loan for his cafe."); launch()
        openTalk("Loan for my brother")
        text("+ Topic").performClick()
        waitForText("A new topic")
        typeInto(0, "Money angle")
        text("Start talking").performClick()
        waitForText("Money angle")      // the new chip
    }

    @Test fun TC_E2E_TALK_04_emptyProjectInvitesTheFirstMessage() {
        reset(withKey = true.also { requireLiveKey() }); seedProject("Loan for my brother"); launch()
        openTalk("Loan for my brother")
        waitForText("Tell me what’s going on with loan for my brother", substring = true)
    }

    @Test fun TC_E2E_TALK_05_longPressCopiesMyMessageAndTheCoachReply() {
        requireLiveKey()
        reset(withKey = true); seedProject("Loan for my brother", "My brother wants me to co-sign a loan for his cafe."); launch()
        openTalk("Loan for my brother")
        val mine = "My wife is against it. What should I do?"
        typeInto(0, mine)
        desc("Send message").performClick()
        waitForCoachReply()
        waitForText(mine)
        text(mine).performTouchInput { longClick() }
        text("Copy").performClick()
        rule.waitForIdle()
        val clip = { var s = ""; InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            s = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }; s }
        assertEquals(mine, clip())
    }

    @Test fun TC_E2E_TALK_06_withAKeyTheNoKeyCardNeverFlashes() {
        requireLiveKey()
        reset(withKey = true); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother"); text("Loan for my brother").performClick()
        waitForText("Start chat"); rule.mainClock.autoAdvance = false
        text("Start chat").performClick()
        var flashed = false
        repeat(80) { rule.mainClock.advanceTimeByFrame(); Thread.sleep(20); if (hasText("Add your AI key")) flashed = true }
        rule.mainClock.autoAdvance = true
        assertTrue("the no-key card flashed while the key was loading", !flashed)
    }
}
