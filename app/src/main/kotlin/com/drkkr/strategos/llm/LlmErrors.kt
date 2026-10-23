package com.drkkr.strategos.llm

/** Short, plain-language text for a failed call. Never includes request or response content. */
object LlmErrors {
    const val NOT_ENOUGH_CREDITS = "Not enough credits."

    private val creditCodes = setOf("insufficient_quota", "insufficient_credits", "quota_exceeded", "billing_not_active", "payment_required")

    /** Credit / quota / rate-limit exhaustion. User decision 2026-10-23: no spending cap in the app, just report this. */
    fun isCreditProblem(r: LlmResult): Boolean = when (r) {
        is LlmResult.RateLimited -> true
        is LlmResult.HttpError -> r.status == 402 || r.code in creditCodes
        else -> false
    }

    fun userMessage(r: LlmResult): String = when {
        isCreditProblem(r) -> NOT_ENOUGH_CREDITS
        r is LlmResult.Unauthorized -> "The API key was not accepted. Check it in Settings."
        r is LlmResult.Network -> if (r.kind == "offline") "You're offline. Try again when you're connected." else "The connection to the AI service failed. Try again."
        r is LlmResult.HttpError -> "The AI service returned an error (${r.status}). Try again."
        else -> "Something went wrong. Try again."
    }
}
