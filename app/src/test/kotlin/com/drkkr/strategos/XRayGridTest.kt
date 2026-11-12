package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.MoveKind
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

    // ── what-if re-blend and flip points (X-ray 3D) ──────────────────────────────

    private fun grid(vararg cells: Pair<Int, Int>, best: Int = 0) = com.drkkr.strategos.domain.agent.XRayGrid(
        cells.mapIndexed { i, (w, b) -> com.drkkr.strategos.domain.agent.XRayRow("m$i", w, b, (w + b) / 2) }, best, true, "well", "badly", 0.5
    )

    @Test fun TC_GRID_09_reblendKeepsRowOrderAndOnlyChangesTheBlend() {
        val g = XRayGridBuilder.build(loan, 0.5)!!
        val moved = g.at(0.9)
        assertEquals(g.rows.map { it.label }, moved.rows.map { it.label })
        moved.rows.forEachIndexed { i, r ->
            assertEquals(g.rows[i].well, r.well); assertEquals(g.rows[i].bad, r.bad)
            assertEquals(Math.round(0.9 * r.well + 0.1 * r.bad).toInt(), r.expected)
        }
        assertEquals(0.9, moved.odds, 0.0)
        assertEquals(1.0, g.at(7.0).odds, 0.0)
    }

    @Test fun TC_GRID_10_reblendMovesTheRingToTheTopRow_andKeepsTheCoachOnATie() {
        val g = grid(78 to 60, 90 to 34, 48 to 22)             // steady move vs high-risk move vs a losing one
        assertEquals(0, g.at(0.55).bestIndex)
        assertEquals(1, g.at(0.80).bestIndex)
        assertTrue(g.at(0.55).robust); assertFalse(g.at(0.80).robust)
        val tie = grid(60 to 40, 40 to 60, best = 1)
        assertEquals("a dead heat stays on the coach's row", 1, tie.at(0.5).bestIndex)
    }

    @Test fun TC_GRID_11_flipPointIsWhereTheLeadChanges() {
        val g = grid(78 to 60, 90 to 34, 48 to 22)             // 78p+60(1-p) = 90p+34(1-p) at p = 26/38 = 68.4%
        assertEquals(listOf(69), g.flipPoints())
        assertEquals(0, g.at(0.68).bestIndex); assertEquals(1, g.at(0.69).bestIndex)
    }

    @Test fun TC_GRID_12_aMoveThatLeadsAtEveryOddsHasNoFlipPoint() {
        assertTrue(grid(80 to 70, 60 to 50, 40 to 30).flipPoints().isEmpty())
    }

    @Test fun TC_GRID_13_moveKindIsReadOffTheTwoCells() {
        val g = grid(78 to 60, 90 to 34, 48 to 22)
        assertEquals(listOf(MoveKind.HOLDS_UP, MoveKind.GAMBLE, MoveKind.BACKFIRES), g.rows.map { it.kind })
    }
}
