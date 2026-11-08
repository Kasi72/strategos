package com.drkkr.strategos.domain.game

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Finds Nash equilibria (pure and mixed) in n-player normal-form games.
 *
 * Algorithm:
 *   1. Pure NE: delegate to [PureNashSolver] for complete pure-strategy
 *      enumeration (exact).
 *   2. Mixed NE: iterated best response (IBR) with temperature-annealed
 *      softmax best response and [MAX_RESTARTS] random initialisations.
 *      Candidates are verified via [EquilibriumVerifier] before acceptance.
 *
 * For 2-player games, delegates entirely to [MixedNashNxMSolver] (support
 * enumeration is complete; IBR adds nothing there).
 *
 * Completeness:
 *   Pure NE search is provably complete for the game class.
 *   Mixed NE search is heuristic — not complete — so the returned
 *   [SolverCompletion] is PARTIAL when any mixed NE are included.
 */
object MixedNashNPlayerSolver : GameSolver {

    private const val MAX_RESTARTS    = 50
    private const val MAX_ITERS       = 500
    private const val INITIAL_TEMP    = 10.0
    private const val TEMP_DECAY      = 0.99     // after 500 iters: 10 * 0.99^500 ≈ 0.066
    private const val CONVERGENCE_TOL = 1e-9
    private const val DEDUP_TOL       = 1e-6

    override fun capabilities() = SolverCapabilities(
        id = "mixed-nash-nplayer-v1",
        supportedClasses = setOf(
            GameClass.FINITE_NORMAL_FORM,
            GameClass.ZERO_SUM_2P,
            GameClass.COOPERATIVE,
            GameClass.DYNAMIC,
            GameClass.BAYESIAN,
            GameClass.REPEATED_FINITE,
            GameClass.REPEATED_INFINITE,
            GameClass.UNKNOWN
        ),
        maxPlayers            = SolverLimits.ON_DEVICE.maxPlayers,
        maxActionsPerPlayer   = SolverLimits.ON_DEVICE.maxActionsPerPlayer,
        supportsMixedStrategies = true,
        supportsUncertainty   = false
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        // 2-player: existing support enumeration is complete; nothing to add.
        if (ir.playerCount == 2) return MixedNashNxMSolver.solve(ir, limits)

        val t0 = System.currentTimeMillis()

        // ── Step 1: Pure NE (complete enumeration) ────────────────────────────
        val pureEquilibria = mutableListOf<EquilibriumProfile>()
        when (val pureOutcome = PureNashSolver.solve(ir, limits)) {
            is SolverOutcome.Solved     -> pureEquilibria += pureOutcome.equilibria
            is SolverOutcome.Incomplete -> pureEquilibria += pureOutcome.partialEquilibria
            else                        -> { /* Unsupported/Invalid — fall through to IBR */ }
        }

        // ── Step 2: Mixed NE via IBR with random restarts ─────────────────────
        val mixedEquilibria = mutableListOf<EquilibriumProfile>()
        val rng = Random(12345L)   // deterministic seed for reproducibility
        var timedOut = false

        for (restart in 0 until MAX_RESTARTS) {
            if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) { timedOut = true; break }

            var strategies: List<DoubleArray> =
                List(ir.playerCount) { p -> randomMix(ir.strategyCounts[p], rng) }
            var temp = INITIAL_TEMP
            var converged = false

            for (iter in 0 until MAX_ITERS) {
                var maxChange = 0.0
                val newStrategies = ArrayList<DoubleArray>(ir.playerCount)

                for (p in 0 until ir.playerCount) {
                    val utils = DoubleArray(ir.strategyCounts[p]) { a ->
                        expectedUtilPure(ir, strategies, p, a)
                    }
                    val newMix = softmax(utils, temp)
                    maxChange = maxOf(maxChange, maxDiff(strategies[p], newMix))
                    newStrategies.add(newMix)
                }

                strategies = newStrategies
                temp *= TEMP_DECAY
                if (maxChange < CONVERGENCE_TOL) { converged = true; break }
            }

            if (!converged) continue

            val tol      = EquilibriumVerifier.adaptiveTolerance(ir.utility)
            val residual = EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, strategies)
            if (residual > tol) continue

            // Skip if already found as pure NE or mixed NE
            if (pureEquilibria.none  { isDuplicate(it.mixedStrategies, strategies) } &&
                mixedEquilibria.none { isDuplicate(it.mixedStrategies, strategies) }) {
                val pureIdxs = strategies.map { mix ->
                    val best = mix.indices.maxByOrNull { mix[it] } ?: 0
                    if (mix[best] > 1.0 - DEDUP_TOL) best else -1
                }
                mixedEquilibria += EquilibriumProfile(
                    mixedStrategies = strategies,
                    pureProfile = if (pureIdxs.none { it < 0 }) pureIdxs else null
                )
            }
        }

        val elapsed       = System.currentTimeMillis() - t0
        val allEquilibria = pureEquilibria + mixedEquilibria
        val hasMixed      = mixedEquilibria.isNotEmpty()

        val completion = when {
            timedOut             -> SolverCompletion.TIMEOUT
            allEquilibria.isEmpty() -> SolverCompletion.PARTIAL
            hasMixed             -> SolverCompletion.PARTIAL  // IBR not exhaustive
            else                 -> SolverCompletion.COMPLETE
        }

        val limitations = buildList {
            if (timedOut) add("Search timed out; some equilibria may be missing")
            if (hasMixed) add("Mixed NE found via iterated best response — not guaranteed complete")
            add("N-player mixed equilibria are computed approximately; pure equilibria are exact")
        }

        val diagnostics = SolverDiagnostics(
            method        = "pure-NE enumeration + iterated-best-response (n-player)",
            solverVersion = "1.0",
            converged     = !timedOut,
            residual      = allEquilibria.firstOrNull()?.let {
                EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, it.mixedStrategies)
            },
            elapsedMs     = elapsed,
            limitations   = limitations,
            completion    = completion
        )

        return if (allEquilibria.isEmpty()) {
            SolverOutcome.Incomplete(
                emptyList(),
                "No equilibrium found (no pure NE; IBR did not converge to a valid mixed NE)",
                diagnostics
            )
        } else {
            SolverOutcome.Solved(allEquilibria, diagnostics)
        }
    }

    // ── Core IBR helpers ──────────────────────────────────────────────────────

    /**
     * Expected payoff for [player] when they play pure strategy [action] and
     * all other players play their current mixed strategies.
     */
    private fun expectedUtilPure(
        ir: GameIR,
        strategies: List<DoubleArray>,
        player: Int,
        action: Int
    ): Double {
        var total = 0.0
        val profile = IntArray(ir.playerCount)

        fun recurse(p: Int, prob: Double) {
            if (prob < 1e-15) return
            if (p == ir.playerCount) {
                total += prob * ir.utility[player][ir.profileIndex(profile)]
                return
            }
            if (p == player) {
                profile[p] = action
                recurse(p + 1, prob)
            } else {
                for (a in 0 until ir.strategyCounts[p]) {
                    val pA = strategies[p][a]
                    if (pA > 1e-15) {
                        profile[p] = a
                        recurse(p + 1, prob * pA)
                    }
                }
            }
        }
        recurse(0, 1.0)
        return total
    }

    /** Temperature-scaled softmax — numerically stable (subtract max). */
    private fun softmax(utils: DoubleArray, temperature: Double): DoubleArray {
        val maxU = utils.max()
        val expU = DoubleArray(utils.size) { exp((utils[it] - maxU) / temperature) }
        val sum  = expU.sum().coerceAtLeast(1e-300)
        return DoubleArray(utils.size) { expU[it] / sum }
    }

    private fun randomMix(size: Int, rng: Random): DoubleArray {
        val raw = DoubleArray(size) { rng.nextDouble() + 1e-9 }
        val sum = raw.sum()
        return DoubleArray(size) { raw[it] / sum }
    }

    private fun maxDiff(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0
        for (i in a.indices) d = maxOf(d, abs(a[i] - b[i]))
        return d
    }

    private fun isDuplicate(a: List<DoubleArray>, b: List<DoubleArray>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.size != y.size) return false
            for (j in x.indices) if (abs(x[j] - y[j]) >= DEDUP_TOL) return false
        }
        return true
    }
}
