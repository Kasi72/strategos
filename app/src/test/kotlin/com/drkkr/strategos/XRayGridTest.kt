package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.XRayGridBuilder
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class XRayGridTest {
    private val loan = Fx.loanFamily()

    @Test fun TC_GRID_01_buildsUpToThreeRowsFromRealPayoffs() {
        val g = XRayGridBuilder.build(loan, 0.5)!!
        assertTrue(g.rows.size in 1..3)
        assertTrue(g.rows.all { it.well in 0..100 && it.bad in 0..100 && it.expected in 0..100 })
    }

    @Test fun TC_GRID_02_rowsAreBestFirstAndRingSitsOnTop() {
        val g = XRayGridBuilder.build(loan, 0.5)!!
        assertEquals(0, g.bestIndex)
        assertTrue(g.rows.zipWithNext().all { (a, b) -> a.expected >= b.expected })
    }

    @Test fun TC_GRID_03_expectedIsTheOddsBlendOfTheTwoColumns() {
        val certainlyWell = XRayGridBuilder.build(loan, 1.0)!!.rows.associate { it.label to it }
        val certainlyBad = XRayGridBuilder.build(loan, 0.0)!!.rows.associate { it.label to it }
        certainlyWell.forEach { (k, r) -> assertEquals(r.well, r.expected) }
        certainlyBad.forEach { (k, r) -> assertEquals(r.bad, r.expected) }
    }

    @Test fun TC_GRID_04_oddsOutsideZeroOneAreClamped() {
        assertEquals(1.0, XRayGridBuilder.build(loan, 5.0)!!.odds, 0.0)
        assertEquals(0.0, XRayGridBuilder.build(loan, -2.0)!!.odds, 0.0)
    }

    @Test fun TC_GRID_05_notEnoughInputIsNull() {
        assertNull(XRayGridBuilder.build(Scenario(title = "empty"), 0.5))
    }

    @Test fun TC_GRID_06_robustMeansBestRowsWorstCellIsAtLeast50() {
        val g = XRayGridBuilder.build(loan, 0.5)!!
        assertEquals(minOf(g.best.well, g.best.bad) >= 50, g.robust)
    }

    @Test fun TC_GRID_07_pinnedEngineMoveIsShownAndRingedEvenIfNotTopByScore() {
        val labels = XRayGridBuilder.build(loan, 0.5)!!.rows.map { it.label }
        val last = XRayGridBuilder.build(loan, 0.5, pinnedLabel = labels.last())!!
        assertEquals(labels.last(), last.best.label)          // ring follows the engine's pick, not the grid's EV
        assertTrue(last.rows.any { it.label == labels.last() })
    }

    @Test fun TC_GRID_08_unknownPinnedLabelFallsBackToTopRow() {
        assertEquals(0, XRayGridBuilder.build(loan, 0.5, pinnedLabel = "no such move")!!.bestIndex)
    }
}
