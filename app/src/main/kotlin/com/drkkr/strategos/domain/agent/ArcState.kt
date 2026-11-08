package com.drkkr.strategos.domain.agent

enum class ArcState { EXPLORING, DECIDING, ACTED, OUTCOME_KNOWN }

object ArcStateMachine {
    private val ACT_SIGNALS     = listOf("i told", "i said", "i did", "i asked", "i sent", "i went", "i tried", "i reached out", "i spoke", "i called", "i messaged", "i emailed", "i talked")
    private val OUTCOME_SIGNALS = listOf("they said", "she said", "he said", "it worked", "it didn't", "they accepted", "they rejected", "they agreed", "they refused", "they responded", "it went well", "it went badly", "outcome was", "result was")
    private val DECIDE_SIGNALS  = listOf("i will", "i'm going to", "i plan to", "i've decided", "i decided", "i intend to", "my plan is", "i'm planning")

    fun advance(current: ArcState, text: String): ArcState {
        val low = text.lowercase()
        return when (current) {
            ArcState.EXPLORING, ArcState.DECIDING -> when {
                OUTCOME_SIGNALS.any { it in low } -> ArcState.OUTCOME_KNOWN
                ACT_SIGNALS.any { it in low }     -> ArcState.ACTED
                DECIDE_SIGNALS.any { it in low }  -> ArcState.DECIDING
                else                              -> current
            }
            ArcState.ACTED -> when {
                OUTCOME_SIGNALS.any { it in low } -> ArcState.OUTCOME_KNOWN
                else                              -> current
            }
            ArcState.OUTCOME_KNOWN -> current
        }
    }
}
