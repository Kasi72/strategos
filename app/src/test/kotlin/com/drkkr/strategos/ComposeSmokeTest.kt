package com.drkkr.strategos

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.drkkr.strategos.ui.tabs.ForecastRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the JVM Compose test harness (Robolectric) actually renders and runs
 * here — no device/emulator. If this passes, tab-level UI tests are viable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun forecastRow_renders_label_and_value() {
        compose.setContent {
            MaterialTheme {
                ForecastRow(label = "Main concern", value = "Budget overrun")
            }
        }
        compose.onNodeWithText("Main concern").assertIsDisplayed()
        compose.onNodeWithText("Budget overrun").assertIsDisplayed()
    }
}
