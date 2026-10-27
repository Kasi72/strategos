package com.drkkr.strategos.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drkkr.strategos.data.repo.RevealMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsE2e : E2eBase() {

    private fun openSettings() { openTab("Settings"); waitForText("AI counsel") }

    @Test fun TC_E2E_SET_01_noKeyShowsTheKeyEditorAndKeepsTheSwitchOff() {
        reset(withKey = false); launch(); openSettings()
        waitForText("Test key"); waitForText("Add a key below to turn this on.")
    }

    @Test fun TC_E2E_SET_02_aRejectedKeyIsReportedAndNotSaved() {
        reset(withKey = false); launch(); openSettings()
        typeInto(0, "sk-this-is-not-a-real-key-0000000000")
        text("Test key").performClick()
        waitForText("didn't accept", substring = true, timeoutMs = 30_000)
        assertTrue(!runBlocking { container.keyVault!!.has() })
    }

    @Test fun TC_E2E_SET_03_pastingTheLiveKeyConnectsAndConsentTurnsAiOn() {
        requireLiveKey()
        reset(withKey = false); launch(); openSettings()
        typeInto(0, liveKey!!)
        text("Test key").performClick()
        waitForText("I understand", timeoutMs = 40_000)
        text("I understand").performClick()
        waitForText("Connected", substring = true)
        assertTrue(runBlocking { container.settingsRepository!!.aiReady() })
    }

    @Test fun TC_E2E_SET_04_revealTheMathChoicePersists() {
        reset(withKey = false); launch(); openSettings()
        text("Always").performClick(); rule.waitForIdle()
        waitFor(what = "ALWAYS stored") { runBlocking { container.settingsRepository!!.revealMath() } == RevealMath.ALWAYS }
        text("Never").performClick()
        waitFor(what = "NEVER stored") { runBlocking { container.settingsRepository!!.revealMath() } == RevealMath.NEVER }
        waitForText("No label under replies", substring = true)
    }

    @Test fun TC_E2E_SET_05_deleteEverythingNeedsTheTypedWordThenWipesData() {
        reset(withKey = false); seedProject("Loan for my brother"); launch(); openSettings()
        text("Delete everything").performScrollTo().performClick()
        waitForText("Delete everything?")
        rule.onNode(androidx.compose.ui.test.hasText("Delete")).assertIsNotEnabled()
        typeInto(field_last(), "DELETE")
        rule.onNode(androidx.compose.ui.test.hasText("Delete")).assertIsEnabled()
        rule.onNode(androidx.compose.ui.test.hasText("Delete")).performClick()
        waitFor(what = "all projects gone") { runBlocking { container.scenarioRepository.scenarios.value.isEmpty() } }
        openTab("Projects")
        waitForText("What's on your mind?")
        assertTrue(!hasText("Loan for my brother"))
    }

    @Test fun TC_E2E_SET_06_keepEverythingLeavesDataAlone() {
        reset(withKey = false); seedProject("Loan for my brother"); launch(); openSettings()
        text("Delete everything").performScrollTo().performClick()
        waitForText("Delete everything?")
        text("Keep everything").performClick()
        rule.waitForIdle()
        assertEquals(1, runBlocking { container.scenarioRepository.scenarios.value.size })
    }

    @Test fun TC_E2E_SET_07_removingTheKeyTurnsAiOff() {
        requireLiveKey()
        reset(withKey = true); launch(); openSettings()
        waitForText("Remove my key"); text("Remove my key").performScrollTo().performClick()
        waitForText("Remove your key?"); text("Remove").performClick()
        waitFor(what = "key removed") { !runBlocking { container.keyVault!!.has() } }
        waitForText("Test key")
    }

    private fun field_last(): Int = rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size - 1
}
