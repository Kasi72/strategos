package com.drkkr.strategos.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectsE2e : E2eBase() {

    @Test fun TC_E2E_PRJ_01_typingShowsTheTextAndEnablesSend() {
        reset(withKey = false); launch()
        waitForText("What's on your mind?")
        typeInto(0, "Quarrel with my landlord")
        waitForText("Quarrel with my landlord")
        desc("Send").assertIsEnabled()
    }

    @Test fun TC_E2E_PRJ_02_firstRunExampleFillsTheComposer() {
        reset(withKey = false); launch()
        waitForText("My brother wants me to co-sign a loan for his café.")
        text("My brother wants me to co-sign a loan for his café.").performClick()
        rule.waitForIdle()
        waitFor { hasText("My brother wants me to co-sign a loan for his café.") }
        desc("Send").assertIsEnabled()
    }

    /** TC_ID_E2E_01. Since 28 Oct (c489ef9): sending with no key opens Settings with the key box ready; no project is made and the draft is kept. */
    @Test fun TC_ID_E2E_01_PRJ_03_sendWithoutAKeyOpensSettingsAndKeepsTheDraft() {
        reset(withKey = false); launch()
        waitForText("What's on your mind?")
        typeInto(0, "Quarrel with my landlord about the deposit")
        desc("Send").performClick()
        waitForText("Add your AI key to get started.", substring = true)
        waitForText("Paste key")
        assertTrue("no project before a key is set", container.scenarioRepository.scenarios.value.isEmpty())
        openTab("Projects")
        waitForText("Quarrel with my landlord about the deposit")   // the draft is still in the box
        desc("Send").assertIsEnabled()
    }

    @Test fun TC_E2E_PRJ_04_tapOpensTheOverviewNotTheChat() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother"); text("Loan for my brother").performClick()
        waitForText("Start chat"); waitForText("Overview")
        assertTrue(!hasText("Send message"))
    }

    @Test fun TC_E2E_PRJ_05_renameArchiveRestoreDelete() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother")
        // rename
        text("Loan for my brother").performTouchInput { longClick() }
        text("Rename").performClick()
        waitForText("Rename project")
        field(1).performTextClearance(); field(1).performTextInput("Brother loan")
        text("Save").performClick()
        waitForText("Brother loan")
        // archive
        text("Brother loan").performTouchInput { longClick() }
        text("Archive").performClick()
        waitForText("Archived (1)")
        text("Archived (1)").performClick()
        waitForText("Restore"); waitForText("Brother loan")
        // restore
        text("Restore").performClick()
        waitForText("Nothing archived", substring = true)
        desc("Back").performClick()
        waitForText("Brother loan")
        // delete
        text("Brother loan").performTouchInput { longClick() }
        text("Delete project").performClick()
        waitForText("Delete project", substring = true)
        text("Delete project").performClick()
        waitFor { !hasText("Brother loan") }
    }
}
