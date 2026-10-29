package com.drkkr.strategos

import com.drkkr.strategos.ui.calm.overview.OverviewChart
import org.junit.Assert.*
import org.junit.Test

class OverviewChartTest {

    @Test fun TC_CHART_01_holdContinuesTheRecentSlope() {
        val proj = OverviewChart.hold(listOf(50f, 55f, 60f), steps = 2)
        assertEquals(listOf(65f, 70f), proj)
    }

    @Test fun TC_CHART_02_holdClampsTo100() {
        assertTrue(OverviewChart.hold(listOf(90f, 96f), steps = 3).all { it <= 100f })
    }

    @Test fun TC_CHART_03_revertDriftsTowardTheMiddle() {
        val proj = OverviewChart.revert(listOf(40f, 20f), steps = 3, toward = 50f)
        // each step moves closer to 50 and never overshoots past it from below
        assertTrue(proj.zipWithNext().all { (a, b) -> b > a })
        assertTrue(proj.all { it < 50f })
    }

    @Test fun TC_CHART_04_shortSeriesProjectsNothing() {
        assertTrue(OverviewChart.hold(listOf(50f)).isEmpty())
        assertTrue(OverviewChart.revert(emptyList()).isEmpty())
    }
}
