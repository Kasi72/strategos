package com.drkkr.strategos.llm

/** USD per 1M tokens. Values from Groq's GET /models `pricing` on 2026-10-23. VERIFY before release against https://console.groq.com/docs/models. */
object PriceTable {
    data class Price(val input: Double, val cached: Double, val output: Double)

    private val prices = mapOf(
        "openai/gpt-oss-20b" to Price(input = 0.075, cached = 0.0375, output = 0.30),
        "openai/gpt-oss-120b" to Price(input = 0.15, cached = 0.075, output = 0.60)
    )

    fun forModel(id: String): Price? = prices[id]

    /** Estimate only. Cached tokens are part of the input count and billed at the cached rate. */
    // BLUEPRINT-QUESTION: output tokens are assumed to already include reasoning tokens (Responses API). Groq usage shows reasoning_tokens inside output_tokens details.
    fun estimateUsd(model: String, input: Int, cached: Int, output: Int): Double? {
        val p = forModel(model) ?: return null
        val uncached = (input - cached).coerceAtLeast(0)
        return (uncached * p.input + cached * p.cached + output * p.output) / 1_000_000.0
    }
}
