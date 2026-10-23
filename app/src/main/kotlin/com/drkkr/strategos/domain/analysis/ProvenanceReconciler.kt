package com.drkkr.strategos.domain.analysis

import com.drkkr.strategos.domain.model.*

/**
 * When the user edits an AI-inferred item by hand it becomes their own estimate.
 * Only AI_INFERRED items are upgraded; CONFIRMED and USER_ESTIMATE are left alone.
 * Do not call this on the agent path.
 */
object ProvenanceReconciler {

    fun onManualEdit(before: Scenario, after: Scenario): Scenario {
        val oldStakeholders = before.stakeholders.associateBy { it.id }
        val oldActions = before.actions.associateBy { it.id }
        val oldConstraints = before.constraints.associateBy { it.id }
        val oldPayoffs = before.payoffs.associateBy { it.stakeholderId to it.actionProfile }
        val oldBeliefs = before.beliefs.associateBy { it.label }

        return after.copy(
            stakeholders = after.stakeholders.map { n ->
                val o = oldStakeholders[n.id]
                if (n.factStatus == FactStatus.AI_INFERRED && o != null && norm(o) != norm(n)) n.copy(factStatus = FactStatus.USER_ESTIMATE) else n
            },
            actions = after.actions.map { n ->
                val o = oldActions[n.id]
                if (n.factStatus == FactStatus.AI_INFERRED && o != null && norm(o) != norm(n)) n.copy(factStatus = FactStatus.USER_ESTIMATE) else n
            },
            constraints = after.constraints.map { n ->
                val o = oldConstraints[n.id]
                if (n.factStatus == FactStatus.AI_INFERRED && o != null && norm(o) != norm(n)) n.copy(factStatus = FactStatus.USER_ESTIMATE) else n
            },
            payoffs = after.payoffs.map { n ->
                val o = oldPayoffs[n.stakeholderId to n.actionProfile]
                if (n.factStatus == FactStatus.AI_INFERRED && o != null && norm(o) != norm(n)) n.copy(factStatus = FactStatus.USER_ESTIMATE) else n
            },
            beliefs = after.beliefs.map { n ->
                val o = oldBeliefs[n.label]
                if (n.status == FactStatus.AI_INFERRED && o != null && o.copy(status = FactStatus.USER_ESTIMATE) != n.copy(status = FactStatus.USER_ESTIMATE))
                    n.copy(status = FactStatus.USER_ESTIMATE) else n
            }
        )
    }

    private fun norm(s: Stakeholder) = s.copy(factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "")
    private fun norm(a: Action) = a.copy(factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "")
    private fun norm(c: Constraint) = c.copy(factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "")
    private fun norm(p: PayoffEntry) = p.copy(factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "")
}
