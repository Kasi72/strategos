package com.drkkr.strategos.domain.analysis

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

data class ProvenanceStats(
    val payoffsTotal: Int,
    val payoffsAi: Int,
    val stakeholdersAi: Int,
    val actionsAi: Int,
    val constraintsAi: Int,
    val beliefsAi: Int
) {
    val aiPayoffShare: Double get() = if (payoffsTotal == 0) 0.0 else payoffsAi.toDouble() / payoffsTotal

    companion object {
        fun of(s: Scenario): ProvenanceStats = ProvenanceStats(
            payoffsTotal = s.payoffs.size,
            payoffsAi = s.payoffs.count { it.factStatus == FactStatus.AI_INFERRED },
            stakeholdersAi = s.stakeholders.count { it.factStatus == FactStatus.AI_INFERRED },
            actionsAi = s.actions.count { it.factStatus == FactStatus.AI_INFERRED },
            constraintsAi = s.constraints.count { it.factStatus == FactStatus.AI_INFERRED },
            beliefsAi = s.beliefs.count { it.status == FactStatus.AI_INFERRED }
        )
    }
}
