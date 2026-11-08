package com.drkkr.strategos.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RouterDecision(
    /** "followup" | "thin" | "proceed" */
    @SerialName("route") val route: String,
    /** 1-2 questions when route == "thin" */
    @SerialName("clarify") val clarify: List<String> = emptyList()
)

/**
 * Pre-routing prompt and parser. Called before the full 3,000-token extraction (Call 1) to avoid
 * wasting tokens on followup questions and too-thin inputs. Output is ~50 tokens.
 *
 * No Android imports — pure Kotlin, lives in domain/.
 */
object PreRouter {

    const val MAX_TOKENS = 80
    const val TEMPERATURE = 0.0

    // ~120-token system prompt; small input = fast, cheap call.
    const val PROMPT = """Classify the message below. Reply with ONE JSON object and nothing else.
Keys: route (string), clarify (string array — only when route is "thin").
route values:
  "followup"  — asks about or reacts to the assistant's previous reply ("what did you mean", "why", "say more", "ok")
  "thin"      — too sparse to build a decision model: fewer than 2 people named, no situation, or a greeting/acknowledgement
  "proceed"   — a situation, update, or new question with enough detail to run a full extraction
When route is "thin", include clarify: 1-2 short, targeted questions whose answers would most help.
Examples:
  "ok" → {"route":"thin","clarify":["What's the situation you're facing?"]}
  "What did you mean by that?" → {"route":"followup"}
  "My boss wants me to work every weekend and I don't know how to say no" → {"route":"proceed"}"""

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun tryParse(text: String): RouterDecision? = runCatching {
        val body = text.trim().let {
            if (it.startsWith("```")) it.lines().drop(1).dropLast(1).joinToString("\n").trim() else it
        }
        json.decodeFromString<RouterDecision>(body)
    }.getOrNull()
}
