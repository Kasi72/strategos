package com.drkkr.strategos

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.ui.StrategosViewModel
import com.drkkr.strategos.ui.tabs.ModelEditorTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tab-level smoke test: the Model editor composes its empty state against a
 * freshly constructed ViewModel (no active scenario) without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun modelEditor_withNoActiveScenario_showsEmptyGuidance() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = StrategosViewModel(app)
        compose.setContent { MaterialTheme { ModelEditorTab(vm) } }
        compose.onNodeWithText("No active scenario.").assertIsDisplayed()
    }
}
