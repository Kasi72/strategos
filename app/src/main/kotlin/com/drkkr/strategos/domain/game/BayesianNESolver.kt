package com.drkkr.strategos.domain.game

/**
 * Solves a Bayesian Nash Equilibrium when private-type uncertainty exists.
 *
 * Triggered when infoAsymmetryScore > THRESHOLD.  Models the opponent as a mixture of two
 * types (cooperative vs competitive) using priors derived from the OpponentModel's
 * cooperationIndex.  For each type the solver computes the user's best response strategy
 * index; the final recommendation is the best response against the mixture.
 *
 * Returns null for n≠2 games or when the IR is too small to solve.
 */
object BayesianNESolver {

    const val THRESHOLD = 0.4
    private const val COOP_TYPE_ID = "cooperative"
    private const val COMP_TYPE_ID = "competitive"

    data class BayesianNEResult(
        val strategy: Int,
        val typePosteriors: Map<String, Double>,
        val reasoning: String
    )

    /**
     * @param ir           compiled game representation
     * @param opponentModel opponent model carrying cooperationIndex as the coop prior (or 0.5 if null)
     * @param infoAsymmetryScore score in [0,1]; solve only when > THRESHOLD
     */
    fun solve(ir: GameIR, opponentModel: OpponentModel?, infoAsymmetryScore: Double): BayesianNEResult? {
        if (infoAsymmetryScore <= THRESHOLD) return null
        if (ir.playerCount != 2) return null
        val userStrategies = ir.strategyCounts.getOrNull(0) ?: return null
        val oppStrategies  = ir.strategyCounts.getOrNull(1) ?: return null
        if (userStrategies < 1 || oppStrategies < 1) return null

        val coopPrior = opponentModel?.cooperationIndex?.coerceIn(0.0, 1.0) ?: 0.5
        val compPrior = 1.0 - coopPrior

        // Build opponent types: cooperative type shifts their payoffs toward cooperation (action 0),
        // competitive type shifts them toward defection (last action index).
        val oppUtility = ir.utility.getOrNull(1) ?: return null

        val coopPayoffs = DoubleArray(oppUtility.size) { i ->
            val oppAction = i % oppStrategies
            oppUtility[i] * (1.0 + 0.3 * (1.0 - oppAction.toDouble() / (oppStrategies - 1).coerceAtLeast(1)))
        }
        val compPayoffs = DoubleArray(oppUtility.size) { i ->
            val oppAction = i % oppStrategies
            oppUtility[i] * (1.0 + 0.3 * oppAction.toDouble() / (oppStrategies - 1).coerceAtLeast(1))
        }

        val types = listOf(
            PlayerType(COOP_TYPE_ID, coopPayoffs, coopPrior),
            PlayerType(COMP_TYPE_ID, compPayoffs, compPrior)
        )

        val userUtility = ir.utility.getOrNull(0) ?: return null

        // For each user strategy, compute expected payoff averaging over opponent types' best replies
        val userExpected = DoubleArray(userStrategies) { userAction ->
            var expectedPayoff = 0.0
            for (type in types) {
                // Find opponent's best reply given this type's payoffs when user plays userAction
                var bestOppReply = 0
                var bestOppVal = Double.NEGATIVE_INFINITY
                for (oppAction in 0 until oppStrategies) {
                    val idx = userAction * oppStrategies + oppAction
                    val v = type.payoffVector.getOrElse(idx) { 0.0 }
                    if (v > bestOppVal) { bestOppVal = v; bestOppReply = oppAction }
                }
                // User's payoff when playing userAction vs type's best reply
                val userIdx = userAction * oppStrategies + bestOppReply
                expectedPayoff += type.prior * userUtility.getOrElse(userIdx) { 0.0 }
            }
            expectedPayoff
        }

        val bestStrategy = userExpected.indices.maxByOrNull { userExpected[it] } ?: 0

        // Posterior: Bayes update given the best strategy chosen (simplified — likelihood = 1 for best reply)
        val posteriors = mapOf(
            COOP_TYPE_ID to coopPrior,
            COMP_TYPE_ID to compPrior
        )

        val reasoning = buildString {
            append("Bayesian NE with info asymmetry score ${String.format("%.2f", infoAsymmetryScore)}. ")
            append("Opponent type priors: cooperative=${String.format("%.2f", coopPrior)}, ")
            append("competitive=${String.format("%.2f", compPrior)}. ")
            append("Best response strategy index: $bestStrategy ")
            append("(expected payoff ${String.format("%.3f", userExpected[bestStrategy])}).")
        }

        return BayesianNEResult(
            strategy = bestStrategy,
            typePosteriors = posteriors,
            reasoning = reasoning
        )
    }

    /**
     * Computes infoAsymmetryScore from a GameIR.
     * Lower coveragePct and more signals = higher asymmetry.
     */
    fun computeAsymmetryScore(ir: GameIR): Double {
        val coverageContrib = (100 - ir.coveragePct.coerceIn(0, 100)) / 100.0
        val signalContrib = when {
            ir.gameClass == GameClass.BAYESIAN -> 0.3
            ir.signalSpace.isNotEmpty()        -> (ir.signalSpace.size.toDouble() / 10.0).coerceAtMost(0.2)
            else                               -> 0.0
        }
        return (coverageContrib * 0.7 + signalContrib).coerceIn(0.0, 1.0)
    }
}
