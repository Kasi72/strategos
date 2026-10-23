package com.drkkr.strategos.llm

/** USD per 1M tokens. VERIFY before release against https://docs.x.ai/docs/models. */
object PriceTable {
    data class Price(val input: Double, val cached: Double, val output: Double)

    private val prices = mapOf(
        "grok-4.7" to Price(input = 2.00, cached = 0.50, output = 6.00),
        "grok-4.3" to Price(input = 1.25, cached = 0.20, output = 2.50)
    )

    fun forModel(id: String): Price? = prices[id]

    /** Estimate only. Cached tokens are part of the input count and billed at the cached rate. */
    // BLUEPRINT-QUESTION: output tokens are assumed to already include reasoning tokens (Responses API). Check with spike S5.
    fun estimateUsd(model: String, input: Int, cached: Int, output: Int): Double? {
        val p = forModel(model) ?: return null
        val uncached = (input - cached).coerceAtLeast(0)
        return (uncached * p.input + cached * p.cached + output * p.output) / 1_000_000.0
    }
}
