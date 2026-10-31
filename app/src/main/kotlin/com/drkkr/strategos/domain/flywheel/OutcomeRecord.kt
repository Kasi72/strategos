package com.drkkr.strategos.domain.flywheel

/**
 * Captures what actually happened after the engine recommended a move — the raw signal
 * for the data flywheel. Pure domain: no android.* or androidx.* imports.
 *
 * @param scenarioId  The scenario this record belongs to.
 * @param recommendedMove  The move the engine told the user to take.
 * @param actualMove  What the user actually did (may differ from the recommendation).
 * @param outcomeRating  User's self-reported outcome: 1 (terrible) to 5 (excellent).
 * @param opponentReaction  Free-text description of how the other party responded.
 * @param archetypeName  The game archetype at the time of the recommendation (e.g. "PRISONERS_DILEMMA").
 * @param recordedAt  Epoch-millis when the record was saved.
 */
data class OutcomeRecord(
    val scenarioId: String,
    val recommendedMove: String,
    val actualMove: String,
    val outcomeRating: Int,              // 1..5
    val opponentReaction: String? = null,
    val archetypeName: String? = null,
    val recordedAt: Long = System.currentTimeMillis()
) {
    init {
        require(outcomeRating in 1..5) { "outcomeRating must be 1..5" }
    }

    /** True when the user followed the engine's recommendation. */
    val followed: Boolean get() = actualMove.trim().equals(recommendedMove.trim(), ignoreCase = true)
}
