package com.drkkr.strategos.domain.game

import kotlin.math.abs

/**
 * Finds the mixed-strategy Nash equilibrium in a 2-player 2×2 normal-form game.
 *
 * Each player's mixing probability is derived by making the opponent indifferent
 * between their two actions (the standard indifference principle):
 *
 *   p * u2(a1=0,a2=0) + (1-p) * u2(a1=1,a2=0) = p * u2(a1=0,a2=1) + (1-p) * u2(a1=1,a2=1)
 *
 * Solving for p gives:
 *   p = (u2[1,1] - u2[1,0]) / (u2[0,0] - u2[0,1] - u2[1,0] + u2[1,1])
 *
 * Edge cases:
 *  - If the denominator is 0, player 2 is indifferent regardless of player 1's
 *    mix → player 1 has a dominant strategy or player 2 is already indifferent.
 *    In that case we check for a pure NE and delegate to [PureNashSolver].
 *  - A mixed probability outside [0,1] means no interior mixed NE; we fall
 *    back to pure-strategy check.
 *
 * Verification: the mixed profile is passed to [EquilibriumVerifier]; residual
 * ≤ 1e-9 promotes to [SolverOutcome.Solved], otherwise [SolverOutcome.Incomplete].
 */
object MixedNash2x2Solver : GameSolver {

    private const val TOLERANCE = 1e-9

    override fun capabilities() = SolverCapabilities(
        id = "mixed-nash-2x2-v1",
        supportedClasses = setOf(GameClass.FINITE_NORMAL_FORM, GameClass.ZERO_SUM_2P),
        maxPlayers = 2,
        maxActionsPerPlayer = 2,
        supportsMixedStrategies = true,
        supportsUncertainty = false
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        if (ir.playerCount != 2) return SolverOutcome.Unsupported("MixedNash2x2Solver requires exactly 2 players")
        if (ir.strategyCounts.any { it != 2 }) return SolverOutcome.Unsupported("MixedNash2x2Solver requires exactly 2 actions per player")

        val t0 = System.currentTimeMillis()

        // u1[r][c] and u2[r][c] — row = player 1 action, col = player 2 action
        fun u(player: Int, a1: Int, a2: Int) = ir.utility[player][ir.profileIndex(intArrayOf(a1, a2))]

        // p = prob player 1 plays action 0; q = prob player 2 plays action 0
        val denomP = u(1, 0, 0) - u(1, 0, 1) - u(1, 1, 0) + u(1, 1, 1)
        val denomQ = u(0, 0, 0) - u(0, 1, 0) - u(0, 0, 1) + u(0, 1, 1)

        val elapsed = { System.currentTimeMillis() - t0 }

        // If either denominator is ~0, there is no interior mixed NE → pure check
        if (abs(denomP) < TOLERANCE || abs(denomQ) < TOLERANCE) {
            return pureOrIncomplete(ir, limits, elapsed(), "Degenerate payoffs: no interior mixed NE; fell back to pure-strategy check")
        }

        val p = (u(1, 1, 1) - u(1, 1, 0)) / denomP
        val q = (u(0, 1, 1) - u(0, 0, 1)) / denomQ

        // Out-of-range → no interior mixed NE
        if (p < -TOLERANCE || p > 1 + TOLERANCE || q < -TOLERANCE || q > 1 + TOLERANCE) {
            return pureOrIncomplete(ir, limits, elapsed(), "Indifference equations yield p=$p q=$q (out of [0,1]); no interior mixed NE")
        }

        val pClamped = p.coerceIn(0.0, 1.0)
        val qClamped = q.coerceIn(0.0, 1.0)

        val mixed = listOf(
            doubleArrayOf(pClamped, 1.0 - pClamped),
            doubleArrayOf(qClamped, 1.0 - qClamped)
        )

        val residual = EquilibriumVerifier.deviationGainResidual(
            ir.utility,
            ir.strategyCounts,
            mixed
        )

        val elapsedMs = elapsed()
        val diagnostics = SolverDiagnostics(
            method = "indifference-equation mixed NE",
            solverVersion = "1.0",
            converged = residual <= TOLERANCE,
            residual = residual,
            elapsedMs = elapsedMs,
            limitations = emptyList(),
            completion = if (residual <= TOLERANCE) SolverCompletion.COMPLETE else SolverCompletion.PARTIAL
        )

        val profile = EquilibriumProfile(mixedStrategies = mixed)

        return if (residual <= TOLERANCE) {
            SolverOutcome.Solved(listOf(profile), diagnostics)
        } else {
            SolverOutcome.Incomplete(
                listOf(profile),
                "Mixed NE computed but residual $residual exceeds tolerance",
                diagnostics,
                AnalysisIntegrity.INCOMPLETE
            )
        }
    }

    private fun pureOrIncomplete(ir: GameIR, limits: SolverLimits, elapsedMs: Long, fallbackReason: String): SolverOutcome {
        return when (val pure = PureNashSolver.solve(ir, limits)) {
            is SolverOutcome.Solved -> pure
            else -> SolverOutcome.Incomplete(
                emptyList(),
                fallbackReason,
                SolverDiagnostics(
                    method = "indifference-equation mixed NE (fallback)",
                    solverVersion = "1.0",
                    converged = false,
                    residual = null,
                    elapsedMs = elapsedMs,
                    limitations = listOf(fallbackReason),
                    completion = SolverCompletion.PARTIAL
                ),
                AnalysisIntegrity.INCOMPLETE
            )
        }
    }
}
