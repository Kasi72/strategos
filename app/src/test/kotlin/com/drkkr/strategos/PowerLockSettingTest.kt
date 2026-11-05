package com.drkkr.strategos

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.drkkr.strategos.ui.calm.settings.SettingsActions
import com.drkkr.strategos.ui.calm.settings.SettingsScreen
import com.drkkr.strategos.ui.calm.settings.SettingsUi
import com.drkkr.strategos.ui.calm.settings.SettingsUiState
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Power Mode, pm5.3: the Power Mode lock switch exists in debug builds only (I-P4; blueprint §5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerLockSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aReleaseBuildNeverShowsTheLockSwitch() {
        assertFalse(SettingsUi.powerLockVisible(debugBuild = false, hasSettings = true))
        assertFalse(SettingsUi.powerLockVisible(debugBuild = true, hasSettings = false))
        assertTrue(SettingsUi.powerLockVisible(debugBuild = true, hasSettings = true))
        compose.setContent { StrategosTheme(darkTheme = false) { SettingsScreen(SettingsUiState(powerLockVisible = SettingsUi.powerLockVisible(false, true)), SettingsActions()) } }
        compose.onNodeWithText("Power Mode lock").assertDoesNotExist()
    }

    @Test fun aDebugBuildShowsIt() {
        compose.setContent { StrategosTheme(darkTheme = false) { SettingsScreen(SettingsUiState(powerLockVisible = true), SettingsActions()) } }
        compose.onNodeWithText("Power Mode lock").assertExists()
    }
}
