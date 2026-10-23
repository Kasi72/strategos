package com.drkkr.strategos.agent

enum class EchoStrategy { VERBATIM, STRIP_IDS, CALLS_ONLY }

object AgentConfig {
    const val BASE_URL = "https://api.x.ai/v1"
    const val DEFAULT_MODEL = "grok-4.7"
    val MODEL_OPTIONS = listOf("grok-4.7", "grok-4.3")
    const val MAX_TOOL_ROUNDS = 8
    const val MAX_TURN_MS = 120_000L
    const val MAX_OUTPUT_TOKENS = 4_000
    const val TEMPERATURE = 0.3
    const val HISTORY_TOKEN_BUDGET = 24_000
    const val HISTORY_TOKEN_TARGET = 12_000
    const val MAX_PENDING_CHANGE_SETS = 2
    const val CONSENT_VERSION = 1
    const val ENGINE_VERSION = "2.0"
    var ECHO_STRATEGY = EchoStrategy.VERBATIM     // set from spike S2
    var CONTEXT_ROLE = "system"                   // set from spike S3
}
