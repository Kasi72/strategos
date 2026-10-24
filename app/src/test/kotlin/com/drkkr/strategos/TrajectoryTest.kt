package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.QuickCheckUpdater
import com.drkkr.strategos.domain.analysis.TrajectoryCalculator
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class TrajectoryTest {
    @Test fun TC_TRAJ_01_neutralBaselineIsMidpoint() {
        assertEquals(50.0, TrajectoryCalculator.computeSigmoid(50.0, 50.0, 0), 0.0)
    }

    @Test fun TC_TRAJ_02_positiveBehaviorIncreasesSigmoid() {
        val base = TrajectoryCalculator.computeSigmoid(50.0, 50.0, 0)
        val snap = TrajectoryCalculator.buildSnapshot(65.0, 35.0, base, 3, "STAG_HUNT")
        assertTrue(snap.sigmoidProgress > 75.0)
        assertEquals("Compounding Trust & Stability", snap.phaseLabel)
    }

    @Test fun TC_TRAJ_03_highTensionTriggersFragilePhase() {
        val s = TrajectoryCalculator.buildSnapshot(60.0, 78.0, 52.0, 0, "CHICKEN_GAME")
        assertEquals("Fragile / De-escalate First", s.phaseLabel)
        assertTrue(s.momentumDelta < 0)
    }

    @Test fun TC_TRAJ_04_projectionsDivergeMonotonically() {
        val s = TrajectoryCalculator.buildSnapshot(55.0, 55.0, null, 0, "STAG_HUNT")
        assertEquals(5, s.positiveProjection.size); assertEquals(5, s.negativeProjection.size)
        val pos = listOf(s.sigmoidProgress) + s.positiveProjection
        val neg = listOf(s.sigmoidProgress) + s.negativeProjection
        assertTrue(pos.zipWithNext().all { (a, b) -> b > a })
        assertTrue(neg.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun TC_TRAJ_05_clampingBounds() {
        for ((t, e, k) in listOf(Triple(150.0, -20.0, 99), Triple(-50.0, 200.0, -5), Triple(Double.NaN, Double.POSITIVE_INFINITY, 0))) {
            val s = TrajectoryCalculator.buildSnapshot(t, e, 50.0, k, "X")
            val all = listOf(s.sigmoidProgress) + s.positiveProjection + s.negativeProjection
            assertTrue(all.all { it in 0.0..100.0 && !it.isNaN() })
            assertTrue(s.momentumDelta in -25.0..25.0)
        }
    }

    @Test fun TC_QC_01_didAsSuggestedAndDeEscalated() {
        val r = QuickCheckUpdater.update(null, 50.0, 65.0, 1, "CHICKEN_GAME", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED")
        assertEquals(58.0, r.trust, 0.0); assertEquals(53.0, r.tension, 0.0)
        assertEquals(2, r.streak); assertEquals("STAG_HUNT", r.archetype)
    }

    @Test fun TC_QC_02_heldBoundaryDespiteCounterpartEscalation() {
        val r = QuickCheckUpdater.update(null, 55.0, 65.0, 2, "CHICKEN_GAME", "DID_AS_SUGGESTED", "ESCALATED_ATTACKED", "GROUNDED")
        assertEquals(75.0, r.tension, 0.0); assertEquals("RELATIONAL_REPAIR", r.archetype); assertEquals(3, r.streak)
    }

    @Test fun TC_QC_03_skippedReactiveResetsStreak() {
        val r = QuickCheckUpdater.update(null, 60.0, 50.0, 4, "CHICKEN_GAME", "SKIPPED_REACTIVE", "ESCALATED_ATTACKED", "ANXIOUS_REGRETFUL")
        assertEquals(51.0, r.trust, 0.0); assertEquals(64.0, r.tension, 0.0); assertEquals(0, r.streak)
    }

    @Test fun TC_QC_04_updatesScenarioTrustBaseline() {
        val s = Fx.loanFamily()
        val r = QuickCheckUpdater.update(s, 50.0, 65.0, 1, "CHICKEN_GAME", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED")
        val bro = r.scenario!!.stakeholders.first { it.id == "bro" }
        assertEquals(0.58, bro.trustBaseline!!, 1e-9)
        assertNull(r.scenario!!.stakeholders.first { it.id == "you" }.trustBaseline)
    }
}
