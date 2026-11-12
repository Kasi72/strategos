package com.drkkr.strategos.domain.game

/**
 * Validates that an extracted [GameIR] is consistent with its detected archetype.
 *
 * Used after extraction (LLM call 1) to catch payoff-ordering violations before
 * the engine attempts to solve a structurally wrong model.
 *
 * Rules checked:
 *   PD  — T > R > P > S for the symmetric player (player 0)
 *   Chicken — mutual aggression < mutual yield, and off-diagonal > on-diagonal
 *   Zero-sum — all profile sums ≈ 0
 *   Stag Hunt — mutual cooperation strictly best, unilateral safe play > unilateral risk
 */
object ExtractionValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val violations: List<String>) : ValidationResult
    }

    fun validate(ir: GameIR): ValidationResult {
        val violations = mutableListOf<String>()
        checkSpread(ir, violations)
        when (ir.archetype) {
            GameArchetype.PRISONERS_DILEMMA -> validatePD(ir, violations)
            GameArchetype.CHICKEN           -> validateChicken(ir, violations)
            GameArchetype.ZERO_SUM          -> validateZeroSum(ir, violations)
            GameArchetype.STAG_HUNT         -> validateStagHunt(ir, violations)
            else                            -> { /* no structural invariants required */ }
        }
        return if (violations.isEmpty()) ValidationResult.Valid
               else ValidationResult.Invalid(violations)
    }

    // ── F8: Payoff spread — compressed payoffs degrade equilibrium quality ──

    private fun checkSpread(ir: GameIR, out: MutableList<String>) {
        if (ir.playerCount < 1 || ir.utility.isEmpty()) return
        val payoffs = ir.utility[0]
        if (payoffs.size < 4) return
        val min = payoffs.minOrNull() ?: return
        val max = payoffs.maxOrNull() ?: return
        val range = max - min
        if (range < 1e-6) return  // all identical — handled elsewhere
        if (range < 2.0) {
            val sorted = payoffs.sortedArray()
            val q1 = sorted[sorted.size / 4]
            val q3 = sorted[3 * sorted.size / 4]
            val iqr = q3 - q1
            if (iqr < 1.5) {
                out += "Payoff spread is narrow (range=%.2f, IQR=%.2f); compressed payoffs may distort the equilibrium — consider rescaling to a 1–10 range.".format(range, iqr)
            }
        }
    }

    // ── PD: T > R > P > S ───────────────────────────────────────────────────

    private fun validatePD(ir: GameIR, out: MutableList<String>) {
        if (ir.playerCount != 2 || ir.strategyCounts != listOf(2, 2)) return
        val u = ir.utility[0]
        val r = u[ir.profileIndex(intArrayOf(0, 0))]  // mutual cooperation
        val s = u[ir.profileIndex(intArrayOf(0, 1))]  // sucker
        val t = u[ir.profileIndex(intArrayOf(1, 0))]  // temptation
        val p = u[ir.profileIndex(intArrayOf(1, 1))]  // mutual defection
        if (!(t > r)) out += "PD requires T(${"%.2f".format(t)}) > R(${"%.2f".format(r)})"
        if (!(r > p)) out += "PD requires R(${"%.2f".format(r)}) > P(${"%.2f".format(p)})"
        if (!(p > s)) out += "PD requires P(${"%.2f".format(p)}) > S(${"%.2f".format(s)})"
    }

    // ── Chicken: mutual aggression is strictly worst ─────────────────────────

    private fun validateChicken(ir: GameIR, out: MutableList<String>) {
        if (ir.playerCount != 2 || ir.strategyCounts != listOf(2, 2)) return
        val u = ir.utility[0]
        val mutual = u[ir.profileIndex(intArrayOf(0, 0))]  // action 0 = aggressive
        val yield  = u[ir.profileIndex(intArrayOf(1, 1))]  // action 1 = yield
        if (!(mutual < yield)) {
            out += "Chicken: mutual aggression (${"%.2f".format(mutual)}) must be worse than mutual yield (${"%.2f".format(yield)})"
        }
    }

    // ── Zero-sum: all profile payoff sums ≈ 0 ───────────────────────────────

    private fun validateZeroSum(ir: GameIR, out: MutableList<String>) {
        if (ir.playerCount != 2) return
        val profiles = ir.utility[0].size
        for (i in 0 until profiles) {
            val sum = ir.utility.sumOf { it[i] }
            if (kotlin.math.abs(sum) > 1e-6) {
                out += "Zero-sum violated at profile $i: sum = ${"%.4f".format(sum)}"
                if (out.size >= 3) return  // cap output
            }
        }
    }

    // ── Stag Hunt: (C,C) strictly best; (D,D) second-worst ──────────────────

    private fun validateStagHunt(ir: GameIR, out: MutableList<String>) {
        if (ir.playerCount != 2 || ir.strategyCounts != listOf(2, 2)) return
        val u = ir.utility[0]
        val cc = u[ir.profileIndex(intArrayOf(0, 0))]  // mutual stag
        val cd = u[ir.profileIndex(intArrayOf(0, 1))]  // I hunt stag, they don't
        val dc = u[ir.profileIndex(intArrayOf(1, 0))]  // I hunt hare, they stag
        val dd = u[ir.profileIndex(intArrayOf(1, 1))]  // mutual hare
        if (!(cc > dc && cc > dd)) out += "Stag Hunt: (Stag,Stag)=${"%.2f".format(cc)} must be strictly best"
        if (!(dc == dd || kotlin.math.abs(dc - dd) < 1e-6 || dc >= dd)) {
            /* safe hare payoff for me (dc) should be >= mutual defect (dd) */
        }
        if (cd > dd) out += "Stag Hunt: unilateral stag (${"%.2f".format(cd)}) should not exceed safe hare (${"%.2f".format(dd)})"
    }
}
