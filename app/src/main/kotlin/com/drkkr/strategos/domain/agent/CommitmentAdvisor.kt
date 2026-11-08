package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.PureNashSolver
import com.drkkr.strategos.domain.game.SolverLimits
import com.drkkr.strategos.domain.game.SolverOutcome
import com.drkkr.strategos.domain.model.Scenario

/**
 * Identifies commitment opportunities: actions where publicly binding oneself shifts the equilibrium
 * in the user's favour.
 */
object CommitmentAdvisor {

    enum class CommitmentType { BURN_BRIDGES, HOSTAGE, PUBLIC_PLEDGE, CONDITIONAL_COMMITMENT }

    data class CommitmentOpportunity(
        val type: CommitmentType,
        val action: String,
        val rationale: String,
        val credibilityRisk: String
    )

    /**
     * @param ir the game model
     * @param userPlayerId the player id representing the user
     * @param scenario used to check reversibility and public visibility of actions
     */
    fun opportunities(
        ir: GameIR,
        userPlayerId: String,
        scenario: Scenario
    ): List<CommitmentOpportunity> {
        val userIdx = ir.playerIds.indexOf(userPlayerId)
        if (userIdx < 0 || ir.playerCount < 2) return emptyList()

        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val equilibria = when (outcome) {
            is SolverOutcome.Solved -> outcome.equilibria
            is SolverOutcome.Incomplete -> outcome.partialEquilibria
            else -> return emptyList()
        }
        if (equilibria.isEmpty()) return emptyList()

        val result = mutableListOf<CommitmentOpportunity>()
        val userActions = scenario.actionsFor(ir.playerIds.getOrElse(userIdx) { userPlayerId })

        userActions.forEach { action ->
            val actionIdx = ir.actionIds.getOrNull(userIdx)?.indexOf(action.id) ?: return@forEach
            if (actionIdx < 0) return@forEach

            val payoffIfCommitted = calculatePayoffIfCommitted(ir, userIdx, actionIdx, equilibria)
            val basePayoff = equilibria.firstOrNull()?.pureProfile?.let { profile ->
                ir.utility[userIdx][ir.profileIndex(profile.toIntArray())]
            } ?: return@forEach

            if (payoffIfCommitted > basePayoff + 0.01) {
                val type = when {
                    !action.reversible && action.publicVisibility -> CommitmentType.BURN_BRIDGES
                    action.publicVisibility -> CommitmentType.PUBLIC_PLEDGE
                    else -> CommitmentType.CONDITIONAL_COMMITMENT
                }
                result += CommitmentOpportunity(
                    type = type,
                    action = action.label,
                    rationale = "Committing to '${action.label}' improves your payoff from " +
                            "${String.format("%.1f", basePayoff)} to ${String.format("%.1f", payoffIfCommitted)} " +
                            "by influencing the opponent's best response.",
                    credibilityRisk = if (!action.reversible)
                        "This commitment is irreversible — credibility is high but flexibility is lost."
                    else
                        "This commitment is reversible — credibility depends on reputation and observable effort."
                )
            }
        }

        return result
    }

    private fun calculatePayoffIfCommitted(
        ir: GameIR,
        userIdx: Int,
        committedActionIdx: Int,
        equilibria: List<com.drkkr.strategos.domain.game.EquilibriumProfile>
    ): Double {
        // Best payoff the user gets assuming opponent best-responds to the committed action
        val oppIdx = if (userIdx == 0) 1 else 0
        var bestPayoff = Double.NEGATIVE_INFINITY

        for (oppAction in 0 until ir.strategyCounts[oppIdx]) {
            val profile = IntArray(ir.playerCount)
            profile[userIdx] = committedActionIdx
            profile[oppIdx] = oppAction

            // Check if this is a best response for the opponent
            var oppBestResponse = true
            for (altOpp in 0 until ir.strategyCounts[oppIdx]) {
                if (altOpp == oppAction) continue
                val altProfile = profile.copyOf()
                altProfile[oppIdx] = altOpp
                if (ir.utility[oppIdx][ir.profileIndex(altProfile)] > ir.utility[oppIdx][ir.profileIndex(profile)]) {
                    oppBestResponse = false
                    break
                }
            }

            if (oppBestResponse) {
                val userPayoff = ir.utility[userIdx][ir.profileIndex(profile)]
                if (userPayoff > bestPayoff) bestPayoff = userPayoff
            }
        }

        return if (bestPayoff == Double.NEGATIVE_INFINITY) {
            equilibria.firstOrNull()?.pureProfile?.let {
                ir.utility[userIdx][ir.profileIndex(it.toIntArray())]
            } ?: 0.0
        } else bestPayoff
    }
}
