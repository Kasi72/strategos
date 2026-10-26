package com.drkkr.strategos.ui.calm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.ui.calm.projects.ProjectCardUi
import com.drkkr.strategos.ui.calm.projects.ProjectsActions
import com.drkkr.strategos.ui.calm.projects.ProjectsScreen
import com.drkkr.strategos.ui.calm.projects.ProjectsUiState
import com.drkkr.strategos.ui.calm.theme.StrategosTheme

/**
 * Debug-only harness for screenshotting redesign screens. Launch with:
 *   adb shell am start -n com.drkkr.strategos/.ui.calm.CalmPreviewActivity --ez dark true|false --es screen projects
 * It is in the debug source set only, so it never ships and MainActivity is untouched.
 */
class CalmPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val firstRun = intent.getStringExtra("screen") == "firstrun"
        setContent {
            StrategosTheme(darkTheme = dark) {
                val state = if (firstRun) {
                    ProjectsUiState(projects = emptyList(), archivedCount = 0, hasKey = true, loading = false)
                } else {
                    ProjectsUiState(projects = SAMPLE, archivedCount = 1, hasKey = true, loading = false)
                }
                ProjectsScreen(state, ProjectsActions())
            }
        }
    }

    private companion object {
        val SAMPLE = listOf(
            ProjectCardUi("1", "Loan for my brother", "Today · 3 topics", MomentumStatus.IMPROVING,
                listOf(50f, 54f, 58f, 55f, 62f, 66f), listOf(60f, 56f, 54f, 50f, 46f, 42f)),
            ProjectCardUi("2", "Co-founder deadlock", "2 days ago · 1 topic", MomentumStatus.STEADY,
                listOf(50f, 51f, 49f, 50f, 50f), listOf(50f, 49f, 51f, 50f, 50f)),
            ProjectCardUi("3", "Saying no to my manager about the weekend rota", "Last week · 2 topics", MomentumStatus.TENSE,
                listOf(55f, 52f, 48f, 44f, 40f), listOf(45f, 50f, 56f, 61f, 66f))
        )
    }
}
