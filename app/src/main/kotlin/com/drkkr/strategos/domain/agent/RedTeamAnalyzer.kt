package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.PureNashSolver
import com.drkkr.strategos.domain.game.SolverLimits
import com.drkkr.strategos.domain.game.SolverOutcome

/**
 * Reframes the game from the opponent's perspective, finds the worst-case profile for the user, and
 * recommends a hedge move.
 *
 * Used as the Kotlin-computed baseline for [EngineDirective.redTeamChallenge]; the LLM call in
 * OneShotTurn may override this with a richer plain-language challenge.
 */
object RedTeamAnalyzer {

    data class RedTeamResult(
        /** The user's player index in [ir]. */
        val userPlayerIdx: Int,
        /** Action index in the worst-case equilibrium profile for the user. */
        val worstCaseActionIndex: Int?,
        /** User's payoff in the worst-case equilibrium. */
        val worstCasePayoff: Double?,
        /** Recommended hedge move label (action id or index). */
        val hedgeMove: String?,
        /** Plain-English challenge sentence. */
        val challenge: String
    )

    /**
     * @param ir the game model
     * @param userPlayerId the player id representing the user
     * @return null when the IR has fewer than 2 players or no equilibria are found
     */
    fun analyze(ir: GameIR, userPlayerId: String): RedTeamResult? {
        val userIdx = ir.playerIds.indexOf(userPlayerId)
        if (userIdx < 0 || ir.playerCount < 2) return null

        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val equilibria = when (outcome) {
            is SolverOutcome.Solved -> outcome.equilibria
            is SolverOutcome.Incomplete -> outcome.partialEquilibria
            else -> return null
        }
        if (equilibria.isEmpty()) return null

        // Worst-case equilibrium: lowest payoff for the user
        val worst = equilibria
            .filter { it.pureProfile != null }
            .minByOrNull { eq ->
                val profile = eq.pureProfile!!.toIntArray()
                ir.utility[userIdx][ir.profileIndex(profile)]
            }

        val worstProfile = worst?.pureProfile
        val worstPayoff = worstProfile?.let {
            ir.utility[userIdx][ir.profileIndex(it.toIntArray())]
        }

        // Best equilibrium for the user
        val best = equilibria
            .filter { it.pureProfile != null }
            .maxByOrNull { eq ->
                val profile = eq.pureProfile!!.toIntArray()
                ir.utility[userIdx][ir.profileIndex(profile)]
            }
        val bestActionIdx = best?.pureProfile?.getOrNull(userIdx)
        val hedgeActionId = bestActionIdx?.let {
            ir.actionIds.getOrNull(userIdx)?.getOrNull(it)
        }

        val worstActionIdx = worstProfile?.getOrNull(userIdx)
        val challenge = buildChallenge(ir, userIdx, worstProfile, worstPayoff, bestActionIdx)

        return RedTeamResult(
            userPlayerIdx = userIdx,
            worstCaseActionIndex = worstActionIdx,
            worstCasePayoff = worstPayoff,
            hedgeMove = hedgeActionId,
            challenge = challenge
        )
    }

    private fun buildChallenge(
        ir: GameIR,
        userIdx: Int,
        worstProfile: List<Int>?,
        worstPayoff: Double?,
        hedgeActionIdx: Int?
    ): String {
        if (worstProfile == null || worstPayoff == null) {
            return "No equilibrium found; consider all opponent responses as possible."
        }

        val oppIdx = if (userIdx == 0) 1 else 0
        val oppActionIdx = worstProfile.getOrNull(oppIdx)
        val oppActionLabel = oppActionIdx?.let {
            ir.actionIds.getOrNull(oppIdx)?.getOrNull(it)
        } ?: "their worst response"

        val hedgeLabel = hedgeActionIdx?.let {
            ir.actionIds.getOrNull(userIdx)?.getOrNull(it)
        } ?: "the safest available option"

        return "If the opponent plays '$oppActionLabel', your payoff drops to ${String.format("%.1f", worstPayoff)}. " +
                "Consider '$hedgeLabel' as a hedge that limits downside across all opponent responses."
    }
}
