package com.drkkr.strategos

import com.drkkr.strategos.ui.calm.projects.ProjectsUi
import com.drkkr.strategos.ui.calm.projects.ProjectsUiState
import org.junit.Assert.*
import org.junit.Test

class ProjectsUiTest {
    private val day = 86_400_000L
    private val now = 1_000L * day   // a UTC midnight

    @Test fun TC_PROJ_01_activityLabelBuckets() {
        assertEquals("Today", ProjectsUi.activityLabel(now, now))
        assertEquals("Yesterday", ProjectsUi.activityLabel(now - day, now))
        assertEquals("3 days ago", ProjectsUi.activityLabel(now - 3 * day, now))
        assertEquals("Last week", ProjectsUi.activityLabel(now - 8 * day, now))
        assertEquals("2 weeks ago", ProjectsUi.activityLabel(now - 15 * day, now))
    }

    @Test fun TC_PROJ_02_subtitlePluralizesTopics() {
        assertEquals("Today · 1 topic", ProjectsUi.subtitle(now, now, 1))
        assertEquals("Today · 3 topics", ProjectsUi.subtitle(now, now, 3))
        assertEquals("Today · 0 topics", ProjectsUi.subtitle(now, now, 0))
    }

    @Test fun TC_PROJ_03_firstRunOnlyWhenEmptyAndLoaded() {
        assertFalse("loading is not first-run", ProjectsUiState(loading = true).isFirstRun)
        assertTrue(ProjectsUiState(loading = false).isFirstRun)
        assertFalse(ProjectsUiState(loading = false, archivedCount = 1).isFirstRun)
    }

    @Test fun TC_PROJ_04_canSendTracksDraft() {
        assertFalse(ProjectsUiState(draft = "   ").canSend)
        assertTrue(ProjectsUiState(draft = "co-sign?").canSend)
    }
}
