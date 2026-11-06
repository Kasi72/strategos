package com.drkkr.strategos.e2e

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewE2e : E2eBase() {

    private fun openOverview(name: String) { waitForText(name); text(name).performClick(); waitForText("Start chat") }

    @Test fun TC_E2E_OVR_01_overviewShowsBothTabsAndSections() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        waitForText("Overview"); waitForText("Journal")
        waitForText("MOMENTUM"); waitForText("The pattern here")
    }

    @Test fun TC_E2E_OVR_02_journalTabSavesALinkedEntryPrivately() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        rule.onAllNodesWithText("Journal", useUnmergedTree = true)[0].performClick()   // the project tab, not the dock
        waitForText("Linked to Loan for my brother")
        waitForText("Nothing linked yet", substring = true)
        typeInto(0, "He went quiet again after dinner")
        text("Save privately").performClick()
        waitForText("He went quiet again after dinner")
        waitFor(what = "empty note gone") { !hasText("Nothing linked yet", substring = true) }
        text("Overview").performClick()
        waitForText("MOMENTUM")
    }

    /** TC_ID_E2E_01. Since 28 Oct (0d88c0a) the Talk header has no project name; the title is shown on the Overview. */
    @Test fun TC_ID_E2E_01_OVR_03_startChatOpensTalkForThatProject() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        text("Start chat").performClick()
        waitForDesc("Back to overview")
        assertTrue("no project name in the Talk header", !hasText("Loan for my brother"))
        desc("Back to overview").performClick()
        waitForText("Start chat")
        waitForText("Loan for my brother")   // the title, where it is shown now
    }

    @Test fun TC_E2E_OVR_04_backReturnsToProjects() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        openOverview("Loan for my brother")
        desc("Back to projects").performClick()
        waitForText("What's on your mind?")
        assertTrue(!hasText("MOMENTUM"))
    }
}
