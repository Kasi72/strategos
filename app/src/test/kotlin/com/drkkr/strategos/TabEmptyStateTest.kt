package com.drkkr.strategos

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.tabs.AnalysisTab
import com.drkkr.strategos.ui.tabs.FrameTab
import com.drkkr.strategos.ui.tabs.ImplementationDashboardTab
import com.drkkr.strategos.ui.tabs.ModelEditorTab
import com.drkkr.strategos.ui.tabs.ReportTab
import com.drkkr.strategos.ui.tabs.ScenarioListTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tab-level smoke tests: each primary tab composes its empty/no-scenario state
 * against a freshly constructed ViewModel without crashing, and shows the right
 * guidance. Runs on the JVM via Robolectric — no device required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vm(): StrategosViewModel =
        StrategosViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun modelEditor_withNoActiveScenario_showsEmptyGuidance() {
        val vm = vm()
        compose.setContent { MaterialTheme { ModelEditorTab(vm) } }
        compose.onNodeWithText("No active scenario.").assertIsDisplayed()
    }

    @Test
    fun frameTab_withNoActiveScenario_showsGuidance() {
        val vm = vm()
        compose.setContent { MaterialTheme { FrameTab(vm) } }
        compose.onNodeWithText("No scenario selected").assertIsDisplayed()
    }

    @Test
    fun analysisTab_withNoActiveScenario_showsGuidance() {
        val vm = vm()
        compose.setContent { MaterialTheme { AnalysisTab(vm) } }
        compose.onNodeWithText("Load a scenario first.").assertIsDisplayed()
    }

    @Test
    fun implementationDashboard_withNoActiveScenario_showsGuidance() {
        val vm = vm()
        compose.setContent { MaterialTheme { ImplementationDashboardTab(vm) } }
        compose.onNodeWithText("Load a scenario first.").assertIsDisplayed()
    }

    @Test
    fun reportTab_withNoReport_showsEmptyState() {
        val vm = vm()
        compose.setContent { MaterialTheme { ReportTab(vm) } }
        compose.onNodeWithText("No report yet").assertIsDisplayed()
    }

    @Test
    fun scenarioList_composesWithTemplatesHeader() {
        val vm = vm()
        compose.setContent { MaterialTheme { ScenarioListTab(vm) {} } }
        // The Templates section is static near the top of the list (SectionHeader
        // uppercases it) — a reliable proof the tab composed on a fresh, empty VM.
        compose.onNodeWithText("TEMPLATES").assertExists()
    }
}
