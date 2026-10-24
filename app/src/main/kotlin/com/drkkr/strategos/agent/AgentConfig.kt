package com.drkkr.strategos.agent

enum class EchoStrategy { VERBATIM, STRIP_IDS, CALLS_ONLY }

/**
 * Provider: DeepSeek, Chat Completions (Argon High blueprint §3.1). Groq's gpt-oss ids stay selectable so older settings keep working.
 * Class and setting names that still say "Xai" (XaiResponsesClient, putXaiKey, secret.xai_api_key) mean "the configured LLM provider".
 */
object AgentConfig {
    const val BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    // BLUEPRINT-QUESTION: §3.1 lists only the two DeepSeek ids; the gpt-oss ids stay so the existing AiSettingsTest cases keep passing (R1).
    val MODEL_OPTIONS = listOf("deepseek-chat", "deepseek-reasoner", "openai/gpt-oss-20b", "openai/gpt-oss-120b")
    const val PROVIDER_NAME = "DeepSeek"
    const val CONSOLE_URL = "https://platform.deepseek.com/api_keys"
    /** Hard cap on estimated input tokens per request (blueprint §3.1, §8). */
    const val MAX_REQUEST_TOKEN_BUDGET = 2_800
    const val MAX_TOOL_ROUNDS = 8
    const val MAX_TURN_MS = 120_000L
    // LEAN PROFILE: Groq reserves max_output_tokens against the 8,000 tokens/minute limit, so keep it small.
    const val MAX_OUTPUT_TOKENS = 1_000

    /** SIMPLE PROFILE: the one-shot sketch holds up to ~9 outcome rows, so it needs more room than a tool call. */
    const val ONE_SHOT_MAX_OUTPUT_TOKENS = 2_000
    /** COMPANION REPLY: ~170 words plus a little reasoning. Groq reserves this against its per-minute limit, so no more. */
    const val COACH_MAX_OUTPUT_TOKENS = 700
    const val TEMPERATURE = 0.3
    const val HISTORY_TOKEN_BUDGET = 1_200
    const val HISTORY_TOKEN_TARGET = 600
    const val MAX_PENDING_CHANGE_SETS = 2
    const val CONSENT_VERSION = 1
    const val ENGINE_VERSION = "2.0"
    var ECHO_STRATEGY = EchoStrategy.VERBATIM     // spike S2 on Groq: VERBATIM works
    var CONTEXT_ROLE = "system"                   // spike S3 on Groq: a mid-input system message works

    /** Spike S4 on Groq: `tool_choice:"none"` returns 400 tool_use_failed, so the client omits `tools` instead. */
    const val TOOL_CHOICE_NONE_SUPPORTED = false

    /** LEAN PROFILE: gpt-oss reasoning effort; null leaves it to the provider's default. */
    val REASONING_EFFORT: String? = "low"

    /** LEAN PROFILE: Guided requests send only the tools the interview uses. Expert mode sends all of them. */
    val GUIDED_TOOLS = setOf("propose_changes", "run_analysis", "update_memory", "show_understanding", "show_support")
}
