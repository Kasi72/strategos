package com.drkkr.strategos.domain.game

/**
 * Counterfactual analysis: what happens if a specific player is *forced* to
 * play a particular action (i.e., strategy is held fixed) while all others
 * optimise freely?
 *
 * For each forced profile, the remaining players' sub-game is re-solved
 * (using [GameModelService]) with the forced player removed. The result is
 * compared to the baseline equilibrium payoff to compute the *deviation cost*
 * (positive = costs the forced player, negative = actually benefits them).
 *
 * Limitations:
 *  - Only 2-player games are supported for forced re-solve; n-player games
 *    fall back to best-response enumeration.
 *  - The sub-game is a degenerate 1-player optimisation when playerCount == 2.
 */
object CounterfactualAnalyzer {

    data class CounterfactualResult(
        val forcedPlayerId: String,
        val forcedActionId: String,
        /** Equilibrium payoff the forced player would receive under the baseline. */
        val baselinePayoff: Double,
        /** Expected payoff under the forced action (opponent best-responding). */
        val counterfactualPayoff: Double,
        /** counterfactualPayoff - baselinePayoff. Negative = deviation hurts the player. */
        val deviationGain: Double
    )

    data class Analysis(
        val results: List<CounterfactualResult>,
        /** Action recommended to each player by the counterfactual analysis. */
        val recommendations: Map<String, String>
    )

    /**
     * Analyse all forced actions for [playerId] given the [baseline] equilibrium.
     * [ir] must already be the compiled game.
     */
    fun analyze(
        ir: GameIR,
        baseline: SolverOutcome.Solved,
        playerId: String
    ): Analysis {
        val playerIdx = ir.playerIds.indexOf(playerId)
        if (playerIdx < 0) return Analysis(emptyList(), emptyMap())

        val baselineEq = baseline.equilibria.firstOrNull()
            ?: return Analysis(emptyList(), emptyMap())

        // Compute baseline expected payoff for each player
        val baselinePayoffs = expectedPayoffs(ir, baselineEq.mixedStrategies)

        val results = mutableListOf<CounterfactualResult>()

        for (actionIdx in 0 until ir.strategyCounts[playerIdx]) {
            val actionId = ir.actionIds[playerIdx][actionIdx]
            val counterfactualPayoff = opponentBestResponsePayoff(ir, playerIdx, actionIdx)
            results += CounterfactualResult(
                forcedPlayerId         = playerId,
                forcedActionId         = actionId,
                baselinePayoff         = baselinePayoffs[playerIdx],
                counterfactualPayoff   = counterfactualPayoff,
                deviationGain          = counterfactualPayoff - baselinePayoffs[playerIdx]
            )
        }

        // Recommend the action that maximises the forced player's payoff
        val best = results.maxByOrNull { it.counterfactualPayoff }
        val recommendations = if (best != null) mapOf(playerId to best.forcedActionId) else emptyMap()

        return Analysis(results, recommendations)
    }

    /**
     * Computes the forced player's payoff when they play [forcedAction] and each
     * opponent plays their own best response to that action.
     */
    private fun opponentBestResponsePayoff(
        ir: GameIR,
        forcedPlayerIdx: Int,
        forcedAction: Int
    ): Double {
        // Build opponent best-response profile
        val profile = IntArray(ir.playerCount)
        profile[forcedPlayerIdx] = forcedAction

        for (opp in 0 until ir.playerCount) {
            if (opp == forcedPlayerIdx) continue
            var bestAction = 0
            var bestPayoff = Double.NEGATIVE_INFINITY
            for (a in 0 until ir.strategyCounts[opp]) {
                profile[opp] = a
                val pay = ir.utility[opp][ir.profileIndex(profile)]
                if (pay > bestPayoff) { bestPayoff = pay; bestAction = a }
            }
            profile[opp] = bestAction
        }

        return ir.utility[forcedPlayerIdx][ir.profileIndex(profile)]
    }

    /** Expected payoffs for each player given a mixed strategy profile. */
    private fun expectedPayoffs(ir: GameIR, mixed: List<DoubleArray>): DoubleArray {
        val payoffs = DoubleArray(ir.playerCount)
        val profile = IntArray(ir.playerCount)

        fun enumerate(p: Int, prob: Double) {
            if (p == ir.playerCount) {
                val idx = ir.profileIndex(profile)
                for (pl in 0 until ir.playerCount) payoffs[pl] += prob * ir.utility[pl][idx]
                return
            }
            for (a in 0 until ir.strategyCounts[p]) {
                profile[p] = a
                enumerate(p + 1, prob * mixed[p][a])
            }
        }
        enumerate(0, 1.0)
        return payoffs
    }
}
