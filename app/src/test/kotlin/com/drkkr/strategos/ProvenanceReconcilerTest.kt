package com.drkkr.strategos

import com.drkkr.strategos.domain.analysis.ProvenanceReconciler
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Test

class ProvenanceReconcilerTest {

    private fun allAi(s: Scenario) = s.copy(
        stakeholders = s.stakeholders.map { it.copy(factStatus = FactStatus.AI_INFERRED) },
        actions = s.actions.map { it.copy(factStatus = FactStatus.AI_INFERRED) },
        constraints = s.constraints.map { it.copy(factStatus = FactStatus.AI_INFERRED) },
        payoffs = s.payoffs.map { it.copy(factStatus = FactStatus.AI_INFERRED) },
        beliefs = s.beliefs.map { it.copy(status = FactStatus.AI_INFERRED) }
    )

    @Test
    fun TC_RECON_01_editedStakeholderBecomesUserEstimate() {
        val before = allAi(Fx.pd())
        val after = before.copy(stakeholders = before.stakeholders.map { if (it.id == "p1") it.copy(name = "Alice") else it })
        val r = ProvenanceReconciler.onManualEdit(before, after)
        assertEquals(FactStatus.USER_ESTIMATE, r.stakeholders.first { it.id == "p1" }.factStatus)
        assertEquals(FactStatus.AI_INFERRED, r.stakeholders.first { it.id == "p2" }.factStatus)
    }

    @Test
    fun TC_RECON_02_untouchedStaysAiInferred() {
        val before = allAi(Fx.pd())
        val after = before.copy(title = "Renamed scenario")
        assertEquals(after, ProvenanceReconciler.onManualEdit(before, after))
    }

    @Test
    fun TC_RECON_03_editedPayoffBecomesUserEstimate() {
        val before = allAi(Fx.pd())
        val target = before.payoffs.first()
        val after = before.copy(payoffs = before.payoffs.map {
            if (it === target) it.copy(payoff = MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to 9.0))) else it
        })
        val r = ProvenanceReconciler.onManualEdit(before, after)
        assertEquals(FactStatus.USER_ESTIMATE, r.payoffs.first().factStatus)
        assertEquals(7, r.payoffs.count { it.factStatus == FactStatus.AI_INFERRED })
    }

    @Test
    fun TC_RECON_04_confirmedActionStaysConfirmed() {
        val before = Fx.pd().let { it.copy(actions = it.actions.map { a -> a.copy(factStatus = FactStatus.CONFIRMED) }) }
        val after = before.copy(actions = before.actions.map { if (it.id == "c1") it.copy(label = "Cooperate fully") else it })
        val r = ProvenanceReconciler.onManualEdit(before, after)
        assertEquals(FactStatus.CONFIRMED, r.actions.first { it.id == "c1" }.factStatus)
    }

    @Test
    fun editedBeliefAndConstraintBecomeUserEstimate() {
        val base = Fx.pd().copy(
            beliefs = listOf(Belief("Market holds", 0.6, FactStatus.AI_INFERRED)),
            constraints = listOf(Constraint(id = "k", label = "Law", kind = Constraint.Kind.LEGAL, factStatus = FactStatus.AI_INFERRED))
        )
        val after = base.copy(
            beliefs = listOf(base.beliefs[0].copy(probability = 0.8)),
            constraints = listOf(base.constraints[0].copy(description = "Section 4"))
        )
        val r = ProvenanceReconciler.onManualEdit(base, after)
        assertEquals(FactStatus.USER_ESTIMATE, r.beliefs[0].status)
        assertEquals(FactStatus.USER_ESTIMATE, r.constraints[0].factStatus)
    }
}
