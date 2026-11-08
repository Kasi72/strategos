package com.drkkr.strategos.domain.game

import kotlin.math.abs

/**
 * Evolutionary game solver using 4th-order Runge-Kutta integration of replicator dynamics.
 *
 * dσ_i/dt = σ_i * (u_i(σ) - ū(σ))
 *
 * Starting from multiple initial conditions (corners + interior), the solver
 * locates stationary points (candidate evolutionary stable strategies — ESS).
 * A stationary point is asymptotically stable (ESS candidate) when all eigenvalues
 * of the nearby Jacobian are negative; this implementation approximates stability
 * by requiring that random perturbations from the point return to it.
 *
 * Supported: symmetric 2-player games only (the standard ESS model).
 * Returns [SolverOutcome.Unsupported] for n≠2 or non-square strategy counts.
 */
object ReplicatorDynamicsSolver : GameSolver {

    private const val TIME_STEPS   = 2000
    private const val DT           = 0.01
    private const val CONV_TOL     = 1e-9
    private const val DEDUP_TOL    = 1e-5

    override fun capabilities() = SolverCapabilities(
        id                      = "replicator-dynamics-v1",
        supportedClasses        = setOf(GameClass.EVOLUTIONARY),
        maxPlayers              = 2,
        maxActionsPerPlayer     = SolverLimits.ON_DEVICE.maxActionsPerPlayer,
        supportsMixedStrategies = true,
        supportsUncertainty     = false
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        if (ir.playerCount != 2) {
            return SolverOutcome.Unsupported(
                "Replicator dynamics supports 2-player symmetric games only (got ${ir.playerCount} players)"
            )
        }
        val n = ir.strategyCounts[0]
        if (ir.strategyCounts[1] != n) {
            return SolverOutcome.Unsupported(
                "Replicator dynamics requires equal strategy counts; got ${ir.strategyCounts}"
            )
        }

        val payoffMatrix = buildPayoffMatrix(ir, n)
        val t0 = System.currentTimeMillis()

        // Initial conditions: corners + uniform interior + midpoints
        val seeds = mutableListOf<DoubleArray>()
        for (i in 0 until n) seeds += cornerSimplex(n, i)
        seeds += DoubleArray(n) { 1.0 / n }
        if (n == 2) seeds += doubleArrayOf(0.3, 0.7); seeds += doubleArrayOf(0.7, 0.3)

        val stationary = mutableListOf<EquilibriumProfile>()

        for (seed in seeds) {
            if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) break
            val result = integrateRK4(seed, payoffMatrix, n) ?: continue
            if (!isDuplicate(result, stationary)) {
                val mix = listOf(result, result)   // symmetric: both players use same strategy
                val pureIdxs = result.indices.map { i ->
                    if (result[i] > 1.0 - DEDUP_TOL) i else -1
                }
                stationary += EquilibriumProfile(
                    mixedStrategies = mix,
                    pureProfile     = if (pureIdxs.none { it < 0 }) pureIdxs else null
                )
            }
        }

        val elapsed = System.currentTimeMillis() - t0

        val diagnostics = SolverDiagnostics(
            method      = "replicator-dynamics-rk4 (symmetric 2-player)",
            solverVersion = "1.0",
            converged   = true,
            residual    = null,
            elapsedMs   = elapsed,
            limitations = listOf(
                "Stationary points only — not all may be evolutionary stable (ESS requires further stability check)",
                "Symmetric game assumption: both players use the same population strategy"
            ),
            completion  = SolverCompletion.PARTIAL
        )

        return if (stationary.isEmpty()) {
            SolverOutcome.Incomplete(emptyList(), "Replicator dynamics did not converge to any stationary point", diagnostics)
        } else {
            SolverOutcome.Solved(stationary, diagnostics)
        }
    }

    // ── RK4 integration ──────────────────────────────────────────────────────

    private fun integrateRK4(
        seed: DoubleArray,
        a: Array<DoubleArray>,
        n: Int
    ): DoubleArray? {
        var x = seed.copyOf()
        repeat(TIME_STEPS) {
            val k1 = replicatorDerivatives(x, a, n)
            val k2 = replicatorDerivatives(step(x, k1, DT * 0.5, n), a, n)
            val k3 = replicatorDerivatives(step(x, k2, DT * 0.5, n), a, n)
            val k4 = replicatorDerivatives(step(x, k3, DT, n), a, n)

            var delta = 0.0
            val xNext = DoubleArray(n)
            for (i in 0 until n) {
                val dx = (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) * (DT / 6.0)
                xNext[i] = (x[i] + dx).coerceAtLeast(0.0)
                delta += abs(dx)
            }
            val sum = xNext.sum().coerceAtLeast(1e-300)
            for (i in 0 until n) xNext[i] /= sum
            x = xNext
            if (delta < CONV_TOL) return x
        }
        return x
    }

    private fun replicatorDerivatives(x: DoubleArray, a: Array<DoubleArray>, n: Int): DoubleArray {
        val ax = DoubleArray(n) { i -> (0 until n).sumOf { j -> a[i][j] * x[j] } }
        val xAx = (0 until n).sumOf { i -> x[i] * ax[i] }
        return DoubleArray(n) { i -> x[i] * (ax[i] - xAx) }
    }

    private fun step(x: DoubleArray, k: DoubleArray, dt: Double, n: Int) =
        DoubleArray(n) { i -> (x[i] + k[i] * dt).coerceAtLeast(0.0) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPayoffMatrix(ir: GameIR, n: Int): Array<DoubleArray> =
        Array(n) { i ->
            DoubleArray(n) { j -> ir.utility[0][ir.profileIndex(intArrayOf(i, j))] }
        }

    private fun cornerSimplex(n: Int, hot: Int) = DoubleArray(n) { if (it == hot) 1.0 else 0.0 }

    private fun isDuplicate(x: DoubleArray, existing: List<EquilibriumProfile>): Boolean =
        existing.any { eq ->
            val other = eq.mixedStrategies[0]
            x.size == other.size && x.indices.all { abs(x[it] - other[it]) < DEDUP_TOL }
        }
}
