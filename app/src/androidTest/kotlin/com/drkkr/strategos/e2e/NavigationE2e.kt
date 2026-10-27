package com.drkkr.strategos.e2e

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationE2e : E2eBase() {

    @Test fun TC_E2E_NAV_01_dockSwitchesBetweenTheThreeTabs() {
        reset(withKey = false); launch()
        waitForText("What's on your mind?")
        openTab("Journal"); waitForText("Only yours", substring = true)
        openTab("Settings"); waitForText("AI counsel")
        openTab("Projects"); waitForText("What's on your mind?")
    }

    @Test fun TC_E2E_NAV_02_archivedListIsEmptyWhenNothingIsArchived() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother")
        assert(!hasText("Archived (", substring = true))
    }

    @Test fun TC_E2E_NAV_03_aProjectSurvivesLeavingAndReopening() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother"); text("Loan for my brother").performClick()
        waitForText("Start chat")
        openTab("Settings"); waitForText("AI counsel")
        openTab("Projects"); waitForText("Start chat")      // the Projects tab keeps its depth
    }
}
