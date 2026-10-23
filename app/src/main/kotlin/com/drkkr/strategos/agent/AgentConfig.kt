package com.drkkr.strategos.agent

enum class EchoStrategy { VERBATIM, STRIP_IDS, CALLS_ONLY }

/**
 * Provider: Groq, Responses API (user decision 2026-10-23, replacing the blueprint's xAI).
 * Class and setting names that still say "Xai" (XaiResponsesClient, putXaiKey, secret.xai_api_key) mean "the configured LLM provider".
 */
object AgentConfig {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"
    val MODEL_OPTIONS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")
    const val MAX_TOOL_ROUNDS = 8
    const val MAX_TURN_MS = 120_000L
    const val MAX_OUTPUT_TOKENS = 4_000
    const val TEMPERATURE = 0.3
    const val HISTORY_TOKEN_BUDGET = 24_000
    const val HISTORY_TOKEN_TARGET = 12_000
    const val MAX_PENDING_CHANGE_SETS = 2
    const val CONSENT_VERSION = 1
    const val ENGINE_VERSION = "2.0"
    var ECHO_STRATEGY = EchoStrategy.VERBATIM     // spike S2 on Groq: VERBATIM works
    var CONTEXT_ROLE = "system"                   // spike S3 on Groq: a mid-input system message works

    /** Spike S4 on Groq: `tool_choice:"none"` returns 400 tool_use_failed, so the client omits `tools` instead. */
    const val TOOL_CHOICE_NONE_SUPPORTED = false
}
