package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.human.HumanVocab

/**
 * Intent Dispatch (BLUEPRINT §4.1): what the user asks for this turn. Call 1 only names it from this closed list; Kotlin
 * decides what runs and the engine still chooses the move (I-D1). Per turn, never stored.
 */
enum class Intent(val word: String) {
    STANDARD("standard"), WHAT_IF("what_if"), COMPARE("compare"), TOP_OPTIONS("top_options"),
    THEIR_REACTION("their_reaction"), PLAN_AHEAD("plan_ahead");

    companion object {
        /** Case-insensitive; anything unknown or missing is [STANDARD]. */
        fun of(raw: String?): Intent = HumanVocab.clean(raw, HumanVocab.INTENTS)?.let { w -> entries.firstOrNull { it.word == w } } ?: STANDARD
    }
}
