package com.drkkr.strategos.domain.game

import kotlin.math.abs

/**
 * Finds all mixed-strategy Nash equilibria in a 2-player NxM normal-form game
 * via support enumeration (Porter–Nudelman–Shoham, 2008).
 *
 * Algorithm:
 *  1. Enumerate all pairs of non-empty action supports (S1 ⊆ A1, S2 ⊆ A2) with |S1|=|S2|=k.
 *  2. For each support pair, solve the (k+1)×(k+1) linear system that enforces
 *     player indifference across support actions and probability normalisation.
 *  3. Accept a solution only if all support probabilities are non-negative and
 *     no outside-support action yields a profitable deviation (verified by
 *     [EquilibriumVerifier]).
 *  4. De-duplicate equilibria that differ by less than [TOLERANCE].
 *
 * Complexity: O(C(m,k)·C(n,k)·k²) per support size k. Bounded by [MAX_SUPPORT]
 * and the runtime limit in [SolverLimits]. Falls back to [PureNashSolver] for
 * k=1 (pure strategies).
 */
object MixedNashNxMSolver : GameSolver {

    private const val TOLERANCE   = 1e-9
    private const val MAX_SUPPORT = 6   // per player; >6 is rarely needed on-device

    override fun capabilities() = SolverCapabilities(
        id                    = "mixed-nash-nxm-v1",
        supportedClasses      = setOf(GameClass.FINITE_NORMAL_FORM, GameClass.ZERO_SUM_2P),
        maxPlayers            = 2,
        maxActionsPerPlayer   = SolverLimits.ON_DEVICE.maxActionsPerPlayer,
        supportsMixedStrategies = true,
        supportsUncertainty   = false
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        if (ir.playerCount != 2)
            return SolverOutcome.Unsupported("NxM solver requires exactly 2 players")

        val t0   = System.currentTimeMillis()
        val m    = ir.strategyCounts[0]
        val n    = ir.strategyCounts[1]

        // Pre-compute payoff matrices
        val u1 = Array(m) { i -> DoubleArray(n) { j -> ir.utility[0][ir.profileIndex(intArrayOf(i, j))] } }
        val u2 = Array(m) { i -> DoubleArray(n) { j -> ir.utility[1][ir.profileIndex(intArrayOf(i, j))] } }
        // Transposed u2 for player-2 indifference equations (rows = player-2 actions)
        val u2T = Array(n) { j -> DoubleArray(m) { i -> u2[i][j] } }

        val equilibria = mutableListOf<EquilibriumProfile>()
        var timedOut   = false

        val maxSup = minOf(MAX_SUPPORT, m, n)
        for (k in 1..maxSup) {
            if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) { timedOut = true; break }

            for (s1 in subsets(m, k)) {
                if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) { timedOut = true; break }
                for (s2 in subsets(n, k)) {
                    if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) { timedOut = true; break }

                    // q: player-2 mixed strategy restricted to s2 (makes player-1 indifferent over s1)
                    val q = solveIndifference(u1, s1, s2) ?: continue
                    // p: player-1 mixed strategy restricted to s1 (makes player-2 indifferent over s2)
                    val p = solveIndifference(u2T, s2, s1) ?: continue

                    if (q.any { it < -TOLERANCE } || p.any { it < -TOLERANCE }) continue

                    val mixed = buildMixed(p, s1, m, q, s2, n)
                    val residual = EquilibriumVerifier.deviationGainResidual(
                        ir.utility, ir.strategyCounts, mixed
                    )
                    if (residual > TOLERANCE) continue

                    if (equilibria.none { isDuplicate(it.mixedStrategies, mixed) }) {
                        val pureProfile = if (k == 1) listOf(s1[0], s2[0]) else null
                        equilibria += EquilibriumProfile(mixedStrategies = mixed, pureProfile = pureProfile)
                    }
                }
            }
        }

        val elapsed    = System.currentTimeMillis() - t0
        val completion = when {
            timedOut         -> SolverCompletion.TIMEOUT
            equilibria.isEmpty() -> SolverCompletion.PARTIAL
            else             -> SolverCompletion.COMPLETE
        }
        val diagnostics = SolverDiagnostics(
            method         = "support-enumeration mixed NE (NxM)",
            solverVersion  = "1.0",
            converged      = !timedOut,
            residual       = equilibria.firstOrNull()?.let {
                EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, it.mixedStrategies)
            },
            elapsedMs      = elapsed,
            limitations    = buildList {
                if (timedOut) add("Search timed out; some equilibria may be missing")
                val capUsed = minOf(MAX_SUPPORT, m, n)
                if (m > capUsed || n > capUsed) add("Support capped at $MAX_SUPPORT; large supports not searched")
            },
            completion     = completion
        )

        return when {
            timedOut || equilibria.isEmpty() ->
                SolverOutcome.Incomplete(equilibria, "Support enumeration did not find all equilibria", diagnostics)
            else ->
                SolverOutcome.Solved(equilibria, diagnostics)
        }
    }

    // ── Linear algebra helpers ────────────────────────────────────────────────

    /**
     * Solves for the mixed strategy [q] (over [colSupport]) that makes [player]
     * indifferent across [rowSupport].
     *
     * System (k=|rowSupport|=|colSupport|):
     *   [ u[r0][c0] .. u[r0][ck-1] -1 ] [q0]   [0]
     *   [ u[r1][c0] .. u[r1][ck-1] -1 ] [q1] = [0]
     *   ...
     *   [ 1         .. 1            0  ] [v ]   [1]
     *
     * Returns the q portion of the solution (length k), or null if singular.
     */
    private fun solveIndifference(
        u: Array<DoubleArray>,
        rowSupport: IntArray,
        colSupport: IntArray
    ): DoubleArray? {
        val k = colSupport.size
        if (rowSupport.size != k) return null

        // Augmented matrix [A | b], size (k+1) x (k+2)
        val aug = Array(k + 1) { row ->
            DoubleArray(k + 2).also { r ->
                if (row < k) {
                    for (col in 0 until k) r[col] = u[rowSupport[row]][colSupport[col]]
                    r[k] = -1.0
                    r[k + 1] = 0.0
                } else {
                    for (col in 0 until k) r[col] = 1.0
                    r[k] = 0.0
                    r[k + 1] = 1.0
                }
            }
        }

        if (!gaussianElimination(aug, k + 1)) return null

        return DoubleArray(k) { aug[it][k + 1] }
    }

    /** In-place Gaussian elimination with partial pivoting. Returns false if singular. */
    private fun gaussianElimination(aug: Array<DoubleArray>, n: Int): Boolean {
        for (col in 0 until n) {
            // Partial pivot
            var maxRow = col
            for (r in col + 1 until n) {
                if (abs(aug[r][col]) > abs(aug[maxRow][col])) maxRow = r
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            if (abs(aug[col][col]) < TOLERANCE) return false  // singular

            val pivot = aug[col][col]
            for (r in 0 until n) {
                if (r == col) continue
                val factor = aug[r][col] / pivot
                for (c in col until aug[col].size) aug[r][c] -= factor * aug[col][c]
            }
            for (c in col until aug[col].size) aug[col][c] /= pivot
        }
        return true
    }

    // ── Combinatorial helpers ─────────────────────────────────────────────────

    /** All size-[k] subsets of {0 .. n-1} as IntArrays. */
    private fun subsets(n: Int, k: Int): List<IntArray> {
        val result = mutableListOf<IntArray>()
        val buf    = IntArray(k)
        fun gen(start: Int, depth: Int) {
            if (depth == k) { result += buf.copyOf(); return }
            val remaining = n - start
            if (remaining < k - depth) return
            for (i in start until n) { buf[depth] = i; gen(i + 1, depth + 1) }
        }
        gen(0, 0)
        return result
    }

    /** Build full m- and n-length mixed-strategy vectors from support probabilities. */
    private fun buildMixed(
        p: DoubleArray, s1: IntArray, m: Int,
        q: DoubleArray, s2: IntArray, n: Int
    ): List<DoubleArray> {
        val p1 = DoubleArray(m); s1.forEachIndexed { idx, a -> p1[a] = p[idx].coerceIn(0.0, 1.0) }
        val p2 = DoubleArray(n); s2.forEachIndexed { idx, a -> p2[a] = q[idx].coerceIn(0.0, 1.0) }
        return listOf(p1, p2)
    }

    /** Two mixed-strategy profiles are the same if every probability differs by less than TOLERANCE. */
    private fun isDuplicate(a: List<DoubleArray>, b: List<DoubleArray>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.size == y.size && x.zip(y.toTypedArray()).all { (xi, yi) -> abs(xi - yi) < 1e-6 }
        }

    private fun DoubleArray.zip(other: Array<Double>): List<Pair<Double, Double>> =
        indices.map { Pair(this[it], other[it]) }
}
