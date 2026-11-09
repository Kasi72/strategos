package com.drkkr.strategos.domain.agent

/**
 * What the opponent likely believes about the user's strategy, inferred from outcome history.
 * Labels: "tit-for-tat" | "cooperative" | "aggressive" | "unpredictable" | "unknown"
 */
data class SelfModel(
    val inferredStrategyLabel: String,
    val confidence: Double,              // 0.0–1.0
    val lastUpdated: Long = System.currentTimeMillis()
)
