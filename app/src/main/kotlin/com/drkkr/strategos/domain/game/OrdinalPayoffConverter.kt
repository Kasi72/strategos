package com.drkkr.strategos.domain.game

/**
 * Converts ordinal outcome labels ("best", "good", "ok", "bad", "worst") into
 * canonical cardinal payoffs on a 0–10 scale.
 *
 * The extraction LLM may receive rank-ordered descriptions instead of numeric
 * payoffs. This converter assigns a reproducible cardinal value so the engine
 * can compute equilibria.
 *
 * Canonical mapping (4-outcome):
 *   rank 1 (best)  → 10.0
 *   rank 2 (good)  →  7.0
 *   rank 3 (ok)    →  3.0
 *   rank 4 (worst) →  0.0
 *
 * For 2-outcome, 3-outcome, and 5-outcome variants the same linear spacing is
 * used (anchored at 0 and 10, evenly spaced).
 */
object OrdinalPayoffConverter {

    private val FOUR_OUTCOME = listOf(10.0, 7.0, 3.0, 0.0)

    private val LABEL_MAP: Map<String, Int> = mapOf(
        "best" to 0, "great" to 0, "win" to 0,
        "good" to 1, "better" to 1,
        "ok" to 2, "okay" to 2, "neutral" to 2, "medium" to 2, "fair" to 2,
        "bad" to 3, "poor" to 3, "worst" to 3, "lose" to 3
    )

    /**
     * Convert an ordinal label string to a cardinal payoff.
     * Returns null if the label is not recognised.
     */
    fun fromLabel(label: String): Double? {
        val rank = LABEL_MAP[label.trim().lowercase()] ?: return null
        return FOUR_OUTCOME[rank]
    }

    /**
     * Convert an ordinal rank (0 = best) to a cardinal payoff for [n] total outcomes.
     * rank must be in 0..(n-1). Returns the evenly-spaced value on [0, 10].
     */
    fun fromRank(rank: Int, n: Int): Double {
        require(n >= 2) { "Need at least 2 outcomes" }
        require(rank in 0 until n) { "rank $rank out of range [0, $n)" }
        return when (n) {
            4 -> FOUR_OUTCOME[rank]
            else -> 10.0 - rank * (10.0 / (n - 1))
        }
    }

    /** True iff [label] is a recognised ordinal label. */
    fun isOrdinal(label: String) = LABEL_MAP.containsKey(label.trim().lowercase())
}
