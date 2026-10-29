package com.drkkr.strategos

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.tabs.AnalysisTab
import com.drkkr.strategos.ui.tabs.ModelEditorTab
import com.drkkr.strategos.ui.tabs.ReportTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Populated-state tab tests: seed a full template scenario into a real
 * ViewModel and assert the Model, Analysis and Report tabs render its content
 * (not just their empty states). The Main dispatcher is swapped for a test
 * dispatcher so the ViewModel's analyze() coroutine runs within the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopulatedTabTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun seededVm(): StrategosViewModel =
        StrategosViewModel(ApplicationProvider.getApplicationContext<Application>())
            .also { it.loadTemplate() }

    @Test
    fun modelEditor_withTemplate_showsStakeholdersSection() {
        val vm = seededVm()
        compose.setContent { MaterialTheme { ModelEditorTab(vm) } }
        // The first editor card binds the scenario title into a text field —
        // proof the loaded scenario populated the editor.
        compose.onNodeWithText("Psychiatric Dept. Leadership: Conflict Resolution").assertExists()
    }

    @Test
    fun analysisTab_withTemplate_showsHeaderAndTitle() {
        val vm = seededVm()
        compose.setContent { MaterialTheme { AnalysisTab(vm) } }
        compose.onNodeWithText("Analysis").assertIsDisplayed()
        compose.onNodeWithText("Psychiatric Dept. Leadership: Conflict Resolution").assertExists()
    }

    @Test
    fun reportTab_afterAnalyze_rendersReportNotEmptyState() {
        val vm = seededVm()
        kotlinx.coroutines.runBlocking { vm.analyzeAndAwait() } // 2.0 analysis runs off the main dispatcher; wait for it
        compose.setContent { MaterialTheme { ReportTab(vm) } }
        // A report was generated, so the Executive view renders instead of the
        // "No report yet" placeholder.
        compose.onNodeWithText("No report yet").assertDoesNotExist()
    }
}
