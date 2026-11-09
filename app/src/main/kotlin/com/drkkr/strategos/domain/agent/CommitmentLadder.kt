package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.model.Scenario

/**
 * Commitment Ladder: four explicit escalation levels with credibility gain estimates.
 * Each level builds on the last — verbal is weakest, irreversible is strongest.
 *
 * Pure domain — no android.* or androidx.* imports.
 */
object CommitmentLadder {

    enum class LadderLevel {
        VERBAL,         // stated intent only
        WRITTEN_PUBLIC, // written + publicly visible
        CONTRACTUAL,    // legally or formally binding
        IRREVERSIBLE    // action taken; cannot be undone
    }

    data class LadderStep(
        val level: LadderLevel,
        val action: String,
        val rationale: String,
        val credibilityGain: String,  // plain-English estimate, e.g. "low → medium"
        val credibilityRisk: String
    )

    /**
     * Maps CommitmentAdvisor opportunities onto the four ladder levels and returns all steps
     * that apply given the game model and scenario. Preserves the CommitmentAdvisor's payoff
     * filter (only improvements are returned).
     */
    fun steps(ir: GameIR, userPlayerId: String, scenario: Scenario): List<LadderStep> {
        val opportunities = CommitmentAdvisor.opportunities(ir, userPlayerId, scenario)
        if (opportunities.isEmpty()) return emptyList()

        return opportunities.map { opp ->
            val level = when (opp.type) {
                CommitmentAdvisor.CommitmentType.CONDITIONAL_COMMITMENT -> LadderLevel.VERBAL
                CommitmentAdvisor.CommitmentType.PUBLIC_PLEDGE          -> LadderLevel.WRITTEN_PUBLIC
                CommitmentAdvisor.CommitmentType.HOSTAGE                -> LadderLevel.CONTRACTUAL
                CommitmentAdvisor.CommitmentType.BURN_BRIDGES           -> LadderLevel.IRREVERSIBLE
            }
            val credibilityGain = when (level) {
                LadderLevel.VERBAL          -> "none → low"
                LadderLevel.WRITTEN_PUBLIC  -> "low → medium"
                LadderLevel.CONTRACTUAL     -> "medium → high"
                LadderLevel.IRREVERSIBLE    -> "high → maximum"
            }
            LadderStep(
                level = level,
                action = opp.action,
                rationale = opp.rationale,
                credibilityGain = credibilityGain,
                credibilityRisk = opp.credibilityRisk
            )
        }.sortedBy { it.level.ordinal }
    }
}
