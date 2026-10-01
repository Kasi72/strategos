package com.drkkr.strategos.nlp

import com.drkkr.strategos.domain.model.*

/**
 * NLP Scenario Parser stub.
 *
 * Production version should call an LLM (Anthropic / OpenAI) with a structured
 * extraction prompt. This stub returns a partially-structured draft so the user
 * can complete the model in the editor, demonstrating the contract.
 *
 * The real implementation should:
 * 1. Send the text + language tag to an LLM with a system prompt requesting
 *    JSON conforming to [Scenario].
 * 2. Mark each extracted field with the appropriate [FactStatus].
 * 3. Surface clarification questions for under-specified fields.
 */
object ScenarioParser {

    data class ParseResult(
        val draft: Scenario,
        val clarificationQuestions: List<String>,
        val language: String
    )

    fun parse(rawText: String, existingId: String? = null): ParseResult {
        val language = detectLanguage(rawText)
        val title = extractTitle(rawText)
        val questions = mutableListOf<String>()

        // Stub: build a minimal scenario from the raw text.
        if (rawText.length < 30) questions += "Please describe the situation in more detail."
        questions += "Who are the key stakeholders and what do they want?"
        questions += "What are the available actions for each party?"
        questions += "What constraints (legal, ethical, safety) are non-negotiable?"

        val scenario = Scenario(
            id = existingId ?: java.util.UUID.randomUUID().toString(),
            title = title,
            description = rawText,
            language = language,
            assumptionLog = listOf(
                AssumptionEntry("Scenario parsed from free text — all fields require review", FactStatus.AI_INFERRED),
                AssumptionEntry("Stakeholders, actions, and payoffs not yet populated", FactStatus.UNKNOWN)
            )
        )
        return ParseResult(scenario, questions, language)
    }

    private fun detectLanguage(text: String): String {
        val tamilCodes = listOf('஀'..'௿')
        val hasTamil = text.any { ch -> tamilCodes.any { ch in it } }
        val hasEnglish = text.any { it.isLetter() && it.code < 0x200 }
        return when {
            hasTamil && hasEnglish -> "ta-en"
            hasTamil -> "ta"
            else -> "en"
        }
    }

    private fun extractTitle(text: String): String =
        text.lines().firstOrNull { it.isNotBlank() }?.take(60) ?: "Unnamed scenario"
}
