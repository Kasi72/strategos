package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.ProvenanceGate
import com.drkkr.strategos.domain.analysis.ProvenanceGate.Outcome
import com.drkkr.strategos.domain.analysis.ProvenanceStats
import com.drkkr.strategos.domain.framework.ExecutiveStatus
import com.drkkr.strategos.domain.framework.ExecutiveStatus.*
import com.drkkr.strategos.domain.framework.ExecutiveStatusReason
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.recommendation.DecisionReport
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class ProvenanceGateTest {

    private fun report(status: ExecutiveStatus) = DecisionReport(
        scenario = Fx.pd(), moduleResults = emptyList(), combinedRecommendations = emptyList(),
        sensitiveAssumptions = emptyList(), missingInfo = emptyList(), frameworkDisagreements = emptyList(),
        defensiblePathway = "", overallConfidence = 0.5,
        executiveStatus = status, executiveStatusReason = ExecutiveStatusReason(status)
    )

    private fun stats(share: Double) = ProvenanceStats(
        payoffsTotal = 100, payoffsAi = (share * 100).toInt(),
        stakeholdersAi = 0, actionsAi = 0, constraintsAi = 0, beliefsAi = 0
    )

    @Test
    fun TC_GATE_01_noAiPayoffsUnchanged() {
        val (r, o) = ProvenanceGate.apply(report(PROCEED), stats(0.0))
        assertEquals(PROCEED, r.executiveStatus)
        assertEquals(Outcome.NONE, o)
    }

    @Test
    fun TC_GATE_02_proceedCappedToConditional() {
        val (r, o) = ProvenanceGate.apply(report(PROCEED), stats(0.25))
        assertEquals(CONDITIONAL_TRIAL, r.executiveStatus)
        assertEquals(Outcome.CAPPED_TO_CONDITIONAL, o)
        assertTrue(r.executiveStatusReason!!.blockingReasons.any { it.contains("25% of payoffs are AI-inferred") })
        assertTrue(r.executiveStatusReason!!.requiredActions.contains("Review AI-inferred payoffs"))
    }

    @Test
    fun TC_GATE_03_conditionalCappedToExploratory() {
        val (r, o) = ProvenanceGate.apply(report(CONDITIONAL_TRIAL), stats(0.5))
        assertEquals(EXPLORATORY_ONLY, r.executiveStatus)
        assertEquals(Outcome.CAPPED_TO_EXPLORATORY, o)
    }

    @Test
    fun TC_GATE_03b_proceedWithHalfAiGoesStraightToExploratory() {
        val (r, o) = ProvenanceGate.apply(report(PROCEED), stats(0.5))
        assertEquals(EXPLORATORY_ONLY, r.executiveStatus)
        assertEquals(Outcome.CAPPED_TO_EXPLORATORY, o)
    }

    @Test
    fun TC_GATE_03c_conditionalWithLowAiShareUnchanged() {
        val (r, o) = ProvenanceGate.apply(report(CONDITIONAL_TRIAL), stats(0.25))
        assertEquals(CONDITIONAL_TRIAL, r.executiveStatus)
        assertEquals(Outcome.NONE, o)
    }

    @Test
    fun TC_GATE_04_blockedConstraintNeverTouched() {
        val (r, o) = ProvenanceGate.apply(report(BLOCKED_CONSTRAINT), stats(1.0))
        assertEquals(BLOCKED_CONSTRAINT, r.executiveStatus)
        assertEquals(Outcome.NONE, o)
    }

    @Test
    fun TC_GATE_05_holdForEvidenceNeverTouched() {
        val (r, o) = ProvenanceGate.apply(report(HOLD_FOR_EVIDENCE), stats(0.9))
        assertEquals(HOLD_FOR_EVIDENCE, r.executiveStatus)
        assertEquals(Outcome.NONE, o)
    }

    @Test
    fun TC_GATE_06_statusAndReasonStatusAlwaysEqual() {
        for (status in ExecutiveStatus.values()) for (share in listOf(0.0, 0.25, 0.5, 1.0)) {
            val (r, _) = ProvenanceGate.apply(report(status), stats(share))
            assertEquals("$status @ $share", r.executiveStatus, r.executiveStatusReason!!.status)
        }
    }

    @Test
    fun gateNeverRaisesAStatus() {
        val rank = listOf(BLOCKED_CONSTRAINT, INDETERMINATE, EXPLORATORY_ONLY, HOLD_FOR_VERIFICATION, HOLD_FOR_EVIDENCE, CONDITIONAL_TRIAL, PROCEED)
        for (status in ExecutiveStatus.values()) for (share in listOf(0.0, 0.1, 0.5, 1.0)) {
            val (r, _) = ProvenanceGate.apply(report(status), stats(share))
            assertTrue("$status @ $share -> ${r.executiveStatus}", rank.indexOf(r.executiveStatus) <= rank.indexOf(status))
        }
    }

    @Test
    fun statsCountAiInferredItems() {
        val base = Fx.pd()
        val s = base.copy(
            payoffs = base.payoffs.mapIndexed { i, p -> if (i < 2) p.copy(factStatus = FactStatus.AI_INFERRED) else p },
            actions = base.actions.map { it.copy(factStatus = FactStatus.AI_INFERRED) }
        )
        val st = ProvenanceStats.of(s)
        assertEquals(8, st.payoffsTotal)
        assertEquals(2, st.payoffsAi)
        assertEquals(0.25, st.aiPayoffShare, 1e-9)
        assertEquals(4, st.actionsAi)
        assertEquals(0.0, ProvenanceStats.of(Fx.pd()).aiPayoffShare, 1e-9)
    }
}
