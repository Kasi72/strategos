package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable

/**
 * Structured output of LLM extraction (call 1).
 *
 * Forces the model to cite evidence for every payoff claim and declare
 * what is missing, so the engine never operates on hallucinated numbers.
 */
@Serializable
data class ExtractionResult(
    val players: List<ExtractedPlayer> = emptyList(),
    val payoffClaims: List<PayoffClaim> = emptyList(),
    val missingPayoffs: List<MissingPayoffQuery> = emptyList(),
    val hints: GameHints = GameHints()
)

@Serializable
data class ExtractedPlayer(
    val ref: String,            // short id used in payoffClaims
    val name: String,
    val role: String = ""
)

@Serializable
data class PayoffClaim(
    val playerRef: String,
    val actionProfile: Map<String, String>,   // playerRef -> actionLabel
    val value: Double,
    /** Verbatim quote or paraphrase from the user's scenario text that justifies this value. */
    val evidenceBasis: String,
    /** 0.0 = certain (user stated), 1.0 = fully guessed; triggers confirmation prompt above 0.6. */
    val uncertainty: Double = 0.0
)

@Serializable
data class MissingPayoffQuery(
    val playerRef: String,
    val actionProfile: Map<String, String>,
    /** Plain-language question the coach should ask the user to fill this gap. */
    val question: String
)

@Serializable
data class GameHints(
    val likelyRepeated: Boolean = false,
    val likelyZeroSum: Boolean = false,
    val informationAsymmetry: Boolean = false,
    val playerCount: Int = 0
)
