package com.drkkr.strategos.domain.agent

/** The X-ray's "not enough to rank yet" state: a plain clarifying question and any offered options. */
data class NotEnoughPrompt(val question: String, val options: List<String> = emptyList())

/**
 * Surfaces the engine's existing "exploratory only / no clear pick" skip as a plain question for the
 * X-ray. Decoupled from [com.drkkr.strategos.domain.recommendation.DecisionReport] so it stays easy
 * to test: the view model passes the two signals it already reads plus the first missing fact.
 */
object XRayNotEnough {
    private const val DEFAULT_Q = "Tell me a little more and I can weigh your options."

    fun from(exploratoryOnly: Boolean, hasRecommendedMove: Boolean, firstMissing: String? = null): NotEnoughPrompt? {
        if (hasRecommendedMove && !exploratoryOnly) return null
        return NotEnoughPrompt(firstMissing?.let(::toQuestion) ?: DEFAULT_Q)
    }

    private fun toQuestion(missing: String): String {
        val m = missing.trim()
        return when {
            m.isEmpty() -> DEFAULT_Q
            m.endsWith("?") -> m
            else -> "To weigh this, I need to know: ${m.replaceFirstChar { it.lowercase() }}."
        }
    }
}
