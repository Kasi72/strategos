package com.drkkr.strategos.domain.intent

/**
 * The shape of an advice reply, picked in Kotlin from what Call 1 says the person asked for, never by the model.
 *
 * - [BRIEF]: the default. The moves with the engine's numbers, trade-offs, the pick and how to tackle it. No words to say.
 * - [COMPARE]: they weigh named moves ("A or B?"): the same brief, led by the verdict between them.
 * - [ANSWER]: a direct question (a follow-up, a what-if, how they will take it, what if they push back): prose from the
 *   engine's state with a one-line bottom line, and no script card.
 * - [SCRIPT]: they asked for the words: the classic card (best move, what to say, do and don't).
 */
enum class ReplyMode {
    BRIEF, COMPARE, ANSWER, SCRIPT;

    companion object {
        const val FOLLOWUP_QUESTION = "followup_question"

        fun of(intent: Intent, messageKind: String?): ReplyMode = when {
            intent == Intent.SCRIPT -> SCRIPT
            intent == Intent.COMPARE -> COMPARE
            intent == Intent.WHAT_IF || intent == Intent.THEIR_REACTION || intent == Intent.PLAN_AHEAD -> ANSWER
            messageKind == FOLLOWUP_QUESTION -> ANSWER
            else -> BRIEF
        }
    }
}
