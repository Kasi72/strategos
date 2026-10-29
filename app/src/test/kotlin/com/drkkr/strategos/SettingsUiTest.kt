package com.drkkr.strategos

import com.drkkr.strategos.agent.AiSettingsUi
import com.drkkr.strategos.agent.KeyState
import com.drkkr.strategos.data.repo.RevealMath
import com.drkkr.strategos.ui.calm.settings.KeyStatus
import com.drkkr.strategos.ui.calm.settings.SettingsUi
import org.junit.Assert.*
import org.junit.Test

class SettingsUiTest {
    private fun st(ai: AiSettingsUi) = SettingsUi.from(ai, RevealMath.ON_TAP).keyStatus

    @Test fun TC_SET_01_keyStatusMapsControllerState() {
        assertEquals(KeyStatus.MISSING, st(AiSettingsUi()))
        assertEquals(KeyStatus.CONNECTED, st(AiSettingsUi(hasKey = true, keyState = KeyState.CONNECTED)))
        assertEquals(KeyStatus.ERROR, st(AiSettingsUi(hasKey = true, keyInvalid = true)))
        assertEquals(KeyStatus.ERROR, st(AiSettingsUi(keyState = KeyState.REJECTED)))
        assertEquals(KeyStatus.CHECKING, st(AiSettingsUi(keyState = KeyState.CHECKING)))
    }

    @Test fun TC_SET_02_deleteUnlocksOnlyOnTheTypedWord() {
        assertFalse(SettingsUi.canDelete(""))
        assertFalse(SettingsUi.canDelete("DELET"))
        assertTrue(SettingsUi.canDelete(" delete "))
    }

    @Test fun TC_SET_03_aiOnNeedsConsent() {
        assertFalse(SettingsUi.from(AiSettingsUi(enabled = true, hasKey = true, needsConsent = true), RevealMath.NEVER).aiOn)
        assertTrue(SettingsUi.from(AiSettingsUi(enabled = true, hasKey = true), RevealMath.NEVER).aiOn)
    }

    @Test fun TC_SET_04_everyRevealModeHasANote() {
        RevealMath.values().forEach { assertTrue(SettingsUi.revealNote(it).isNotBlank()) }
    }
}
