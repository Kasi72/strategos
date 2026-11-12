package com.drkkr.strategos.e2e

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The project overview. Since the chat-first flow (Nov 2026) it is a sheet over the conversation, opened from the chat's
 * title; it no longer lists conversations, asks the check-in or shows the pattern card.
 */
@RunWith(AndroidJUnit4::class)
class OverviewE2e : E2eBase() {

    private fun openOverview(name: String) { openProject(name); openOverviewSheet() }

    @Test fun TC_E2E_OVR_01_overviewShowsBothTabsAndSections() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        projectTab("Overview").assertExists(); projectTab("Journal").assertExists()
        waitForText("MOMENTUM"); waitForText("What the app knows")
        assertTrue("the pattern card lives on the X-ray now", !hasText("The pattern here"))
        assertTrue("one project, one chat: no conversations list", !hasText("Conversations"))
        assertTrue("the chat is right behind the sheet: no chat button", !hasText("Start chat") && !hasText("Continue chat"))
    }

    @Test fun TC_E2E_OVR_02_journalTabSavesALinkedEntryPrivately() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        projectTab("Journal").performClick()
        waitForText("Linked to Loan for my brother")
        waitForText("Nothing linked yet", substring = true)
        // The chat's composer sits behind the sheet, so the journal's own box is the last text field on screen.
        val box = rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())
        val last = box.fetchSemanticsNodes().size - 1
        typeInto(last, "He went quiet again after dinner")
        text("Save privately").performClick()
        waitForText("He went quiet again after dinner")
        waitFor(what = "empty note gone") { !hasText("Nothing linked yet", substring = true) }
        projectTab("Overview").performClick()
        waitForText("MOMENTUM")
    }

    /** TC_ID_E2E_01. Since the chat-first flow the Talk header carries the project name again: it is the door to the overview. */
    @Test fun TC_ID_E2E_01_OVR_03_aProjectOpensItsChatAndTheTitleOpensTheOverview() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openProject("Loan for my brother")
        waitForText("Loan for my brother")          // the title, in the chat's top bar
        assertTrue("the chat opens first, not the overview", !hasText("MOMENTUM"))
        openOverviewSheet()
        waitForText("What the app knows")
    }

    @Test fun TC_E2E_OVR_04_backReturnsToProjects() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openProject("Loan for my brother")
        desc("Back to projects").performClick()
        waitForText("What's on your mind?")
        assertTrue(!hasText("MOMENTUM"))
    }
}
