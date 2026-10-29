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

    private fun pressBack() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun TC_E2E_NAV_04_backFromAnotherTabReturnsToProjectsThenExits() {
        reset(withKey = false); launch()
        waitForText("What's on your mind?")
        openTab("Settings"); waitForText("AI counsel")
        pressBack(); waitForText("What's on your mind?")
        openTab("Journal"); waitForText("Only yours", substring = true)
        pressBack(); waitForText("What's on your mind?")
        pressBack()                                         // nothing left: the app closes
        waitFor(what = "activity finished") { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
    }

    @Test fun TC_E2E_NAV_05_backFromTalkReturnsToTheOverviewThenProjects() {
        reset(withKey = false); seedProject("Loan for my brother"); launch()
        waitForText("Loan for my brother"); text("Loan for my brother").performClick()
        waitForText("Start chat"); text("Start chat").performClick(); waitForDesc("Back to overview")
        pressBack()
        waitForText("MOMENTUM")
        pressBack()
        waitForText("What's on your mind?")
    }
}
