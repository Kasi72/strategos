package com.drkkr.strategos.domain.game

import kotlin.math.abs

/**
 * Finds Nash equilibria in games with information asymmetry (GameClass.BAYESIAN).
 *
 * Strategy:
 *   1. Run standard support enumeration on the already Bayesian-adjusted IR —
 *      payoffs have been weighted by opponent behavioural priors in
 *      BayesianPayoffAdjuster before this solver is invoked.
 *   2. Augment the result with a signalling-risk score: how much does the
 *      equilibrium payoff for the informed party (player 0) exceed the
 *      worst-case payoff when the opponent best-responds to the informed
 *      party's NE strategy?  High risk → private information has strategic
 *      value; concealment may help.
 *
 * Signalling risk formula:
 *   nePayoff(P0) − worstCase(P0 | opponent sees P0's mix and best-responds)
 * Positive gap = P0 benefits from opponent NOT knowing their strategy.
 * Near zero = private information is strategically worthless here.
 */
object BayesianGameSolver : GameSolver {

    override fun capabilities() = SolverCapabilities(
        id = "bayesian-game-v1",
        supportedClasses = setOf(GameClass.BAYESIAN),
        maxPlayers = SolverLimits.ON_DEVICE.maxPlayers,
        maxActionsPerPlayer = SolverLimits.ON_DEVICE.maxActionsPerPlayer,
        supportsMixedStrategies = true,
        supportsUncertainty = true
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        // Delegate equilibrium search to the standard NxM mixed-NE solver.
        // Bayesian payoff adjustment has already been applied by BayesianPayoffAdjuster.
        val delegate: GameSolver = if (ir.playerCount == 2 && ir.strategyCounts.all { it == 2 })
            MixedNash2x2Solver else MixedNashNxMSolver
        val base = delegate.solve(ir, limits)

        val signalingNotes = if (ir.playerCount == 2) {
            val risk = computeSignalingRisk(ir, when (base) {
                is SolverOutcome.Solved -> base.equilibria
                is SolverOutcome.Incomplete -> base.partialEquilibria
                else -> emptyList()
            })
            buildSignalingNotes(risk)
        } else {
            listOf("Signalling risk analysis available for 2-player games only.")
        }

        return when (base) {
            is SolverOutcome.Solved -> base.copy(
                diagnostics = base.diagnostics.copy(
                    method = "bayesian-game-equilibrium (via ${base.diagnostics.method})",
                    limitations = base.diagnostics.limitations + signalingNotes
                )
            )
            is SolverOutcome.Incomplete -> base.copy(
                diagnostics = base.diagnostics.copy(
                    method = "bayesian-game-equilibrium (via ${base.diagnostics.method})",
                    limitations = base.diagnostics.limitations + signalingNotes
                )
            )
            else -> base
        }
    }

    /**
     * Signalling risk = NE payoff for P0 minus their worst-case payoff when the
     * opponent observes P0's NE mixed strategy and best-responds to it.
     * Positive = P0 benefits from concealment; near zero = no informational advantage.
     */
    private fun computeSignalingRisk(ir: GameIR, equilibria: List<EquilibriumProfile>): Double {
        if (ir.playerCount != 2 || equilibria.isEmpty()) return 0.0
        val eq = equilibria.first()
        val p1Mix = eq.mixedStrategies[0]
        val p2Mix = eq.mixedStrategies[1]
        val m = ir.strategyCounts[0]
        val n = ir.strategyCounts[1]

        var nePayoff = 0.0
        for (i in 0 until m) for (j in 0 until n) {
            nePayoff += p1Mix[i] * p2Mix[j] * ir.utility[0][ir.profileIndex(intArrayOf(i, j))]
        }

        // Opponent sees P0's mix and picks the j that minimises P0's expected payoff.
        var worstPayoff = Double.MAX_VALUE
        for (j in 0 until n) {
            var util = 0.0
            for (i in 0 until m) util += p1Mix[i] * ir.utility[0][ir.profileIndex(intArrayOf(i, j))]
            if (util < worstPayoff) worstPayoff = util
        }
        if (worstPayoff == Double.MAX_VALUE) return 0.0
        return (nePayoff - worstPayoff).coerceAtLeast(0.0)
    }

    private fun buildSignalingNotes(risk: Double): List<String> {
        val baseNote = "Payoffs reflect Bayesian adjustment for opponent behavioural priors."
        val riskNote = when {
            risk > 2.0 ->
                "High signalling risk (gap=%.2f): private information has strategic value. " +
                "Revealing your strategy weakens your position — maintain ambiguity.".format(risk)
            risk > 0.5 ->
                "Moderate signalling risk (gap=%.2f): partial concealment may help. " +
                "Consider mixing actions to prevent the opponent reading your type.".format(risk)
            else ->
                "Low signalling risk (gap=%.2f): private information has little strategic value here. " +
                "Playing the equilibrium strategy is safe regardless of disclosure.".format(risk)
        }
        return listOf(baseNote, riskNote)
    }
}
