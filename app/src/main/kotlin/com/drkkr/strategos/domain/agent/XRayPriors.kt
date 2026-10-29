package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder

/**
 * One what-if prior on the X-ray, mapped to a Bayesian field on the counterpart. [labelTemplate]
 * takes the counterpart's name so the slider reads in plain words ("Dana takes it well").
 */
enum class PriorId(val labelTemplate: String) {
    TAKES_LIMIT_WELL("%s takes it well"),
    SOFTENS_ON_REPAIR("%s comes round after a repair"),
    ESCALATES_IF_PUSHED("%s pushes back harder if pressed"),
    TRUST_BASELINE("you two trust each other");

    fun label(name: String): String = labelTemplate.format(name)
}

/** A slider on the sheet: its current [value], the coach's reference [coachValue], and whether the user set it. */
data class XRayPrior(
    val id: PriorId,
    val label: String,
    val value: Double,
    val coachValue: Double,
    val isUserSet: Boolean
)

/**
 * The X-ray what-if priors over the counterpart's Bayesian fields. [withPriors] is the pure primitive
 * the live re-rank runs on (`AnalysisPipeline().run(scenario.withPriors(it))`); nothing here persists.
 */
object XRayPriors {

    /** The engine's neutral reference when a prior has not been learned yet. */
    const val COACH_DEFAULT = 0.5
    private const val MIN = 0.10
    private const val MAX = 0.90

    fun of(s: Scenario): List<XRayPrior> {
        val other = counterpart(s) ?: return emptyList()
        val userSet = other.factStatus == FactStatus.USER_ESTIMATE
        return PriorId.entries.map { id ->
            val stored = field(other, id)
            XRayPrior(id, id.label(other.name), stored ?: COACH_DEFAULT, COACH_DEFAULT, isUserSet = userSet && stored != null)
        }
    }

    /** Apply overrides to the counterpart's fields (clamped). [markUser] also vouches for the person as the user's own. */
    fun withPriors(s: Scenario, overrides: Map<PriorId, Double>, markUser: Boolean = false): Scenario {
        if (overrides.isEmpty()) return s
        val other = counterpart(s) ?: return s
        val updated = overrides.entries.fold(other) { acc, (id, v) -> set(acc, id, v.coerceIn(MIN, MAX)) }
            .let { if (markUser) it.copy(factStatus = FactStatus.USER_ESTIMATE) else it }
        return s.copy(stakeholders = s.stakeholders.map { if (it.id == other.id) updated else it })
    }

    /** Clear the given priors back to "not learned", so the engine uses its own defaults again. */
    fun reset(s: Scenario, ids: Set<PriorId>): Scenario {
        if (ids.isEmpty()) return s
        val other = counterpart(s) ?: return s
        val cleared = ids.fold(other) { acc, id -> clear(acc, id) }
        return s.copy(stakeholders = s.stakeholders.map { if (it.id == other.id) cleared else it })
    }

    fun counterpart(s: Scenario): Stakeholder? {
        val self = IntakeChecklist.self(s) ?: s.players().firstOrNull()
        return s.players().firstOrNull { it.id != self?.id }
    }

    private fun field(st: Stakeholder, id: PriorId): Double? = when (id) {
        PriorId.TAKES_LIMIT_WELL -> st.probRespectsBoundary
        PriorId.SOFTENS_ON_REPAIR -> st.probSoftensOnRepair
        PriorId.ESCALATES_IF_PUSHED -> st.probEscalatesOnReactive
        PriorId.TRUST_BASELINE -> st.trustBaseline
    }

    private fun set(st: Stakeholder, id: PriorId, v: Double): Stakeholder = when (id) {
        PriorId.TAKES_LIMIT_WELL -> st.copy(probRespectsBoundary = v)
        PriorId.SOFTENS_ON_REPAIR -> st.copy(probSoftensOnRepair = v)
        PriorId.ESCALATES_IF_PUSHED -> st.copy(probEscalatesOnReactive = v)
        PriorId.TRUST_BASELINE -> st.copy(trustBaseline = v)
    }

    private fun clear(st: Stakeholder, id: PriorId): Stakeholder = when (id) {
        PriorId.TAKES_LIMIT_WELL -> st.copy(probRespectsBoundary = null)
        PriorId.SOFTENS_ON_REPAIR -> st.copy(probSoftensOnRepair = null)
        PriorId.ESCALATES_IF_PUSHED -> st.copy(probEscalatesOnReactive = null)
        PriorId.TRUST_BASELINE -> st.copy(trustBaseline = null)
    }
}
