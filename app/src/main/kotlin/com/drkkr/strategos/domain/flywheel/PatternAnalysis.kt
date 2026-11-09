package com.drkkr.strategos.domain.flywheel

data class Heuristic(
    val condition: String,
    val successRate: Double,  // 0..1; for shapley_delta and timing_trend, 0.5 = neutral
    val sampleSize: Int
)

data class PatternAnalysis(
    val heuristics: List<Heuristic>
)
