package com.drkkr.strategos.domain.game

/** E5: Belief confidence ratchet — tracks how certainty about the game archetype evolves across turns. */
data class EpistemicRatchet(
    val prevConfidence: Double,
    val currConfidence: Double,
    /** currConfidence - prevConfidence; positive = growing more certain. */
    val delta: Double,
    /** "improving" | "stable" | "degrading" */
    val direction: String
)

/** E6: Minimax regret trend — tracks worst-case downside risk of the recommendation across turns. */
data class RegretArc(
    val prevRegret: Double,
    val currRegret: Double,
    /** "improving" (regret shrinking) | "stable" | "worsening" (regret growing) */
    val trend: String
)

/** E7: Cross-project meta-strategy pattern — dominant strategic style seen across this user's projects. */
data class MetaStrategyProfile(
    /** All strategy labels observed in the rolling window (most recent last). */
    val labels: List<String>,
    /** Most frequent label; null when fewer than 2 turns have been seen. */
    val dominant: String?,
    /** Diversity index: 0.0 = all same label, 1.0 = all different. */
    val diversity: Double
)

/** E10: Metatransparency index — derivative of information asymmetry score across turns. */
data class MetatransparencyResult(
    val prevScore: Double,
    val currScore: Double,
    /** currScore - prevScore; negative = becoming more transparent. */
    val derivative: Double,
    /** "becoming_more_transparent" | "stable" | "becoming_less_transparent" */
    val trend: String
)
