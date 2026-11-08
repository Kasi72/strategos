package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.model.Scenario

/**
 * Selects the highest-value-of-information structural question for the current [GameArchetype].
 *
 * Unlike [EvsiClarifier] (which asks about missing payoff data), this addresses structural
 * unknowns — repeated-game horizon, commitment devices, coordination signals, outside options —
 * whose answers would most change which equilibrium the engine recommends.
 *
 * Returns null for [GameArchetype.GENERIC] and [GameArchetype.HARMONY] (no targeted question
 * exists), or when the question was already asked in this thread.
 */
object VoiQuestionSelector {

    private data class Entry(val archetype: GameArchetype, val question: String?, val label: String)

    private val TABLE = listOf(
        Entry(GameArchetype.PRISONERS_DILEMMA,
            "Will you interact with this person again after this, or is this a one-time situation?",
            "repeated-game horizon"),
        Entry(GameArchetype.CHICKEN,
            "Has either of you made any public commitments that would be hard to walk back?",
            "commitment device"),
        Entry(GameArchetype.STAG_HUNT,
            "Have you and the other person discussed this openly, or is it implicit so far?",
            "coordination signal"),
        Entry(GameArchetype.BATTLE_OF_SEXES,
            "Who gets to decide if you can't agree, or is there a default outcome?",
            "tie-breaking mechanism"),
        Entry(GameArchetype.ZERO_SUM,
            "If this doesn't work out, what do you think they would do instead?",
            "opponent outside option"),
        Entry(GameArchetype.DEADLOCK,
            "Has anyone proposed a third option or suggested bringing in a mediator?",
            "escape mechanism"),
        Entry(GameArchetype.LEADER,
            "Do you know what their fallback option is if this falls through?",
            "opponent outside option"),
        Entry(GameArchetype.HERO,
            "Is there a way for one side to act first and make the other's choice easier?",
            "sequential commitment"),
        Entry(GameArchetype.PURE_COORDINATION,
            "Is there a natural focal point or precedent you both could rally around?",
            "Schelling focal point"),
        Entry(GameArchetype.HARMONY, null, ""),
        Entry(GameArchetype.DEADLOCK, null, ""),
        Entry(GameArchetype.GENERIC, null, "")
    )

    /**
     * Returns a [Clarification] for the highest-VOI structural question, or null when none
     * applies. [askedQuestions] is a list of prior assistant reply snippets used to avoid
     * repeating a question already surfaced in this thread.
     */
    fun select(
        scenario: Scenario,
        archetype: GameArchetype,
        askedQuestions: List<String> = emptyList()
    ): Clarification? {
        val entry = TABLE.firstOrNull { it.archetype == archetype } ?: return null
        val q = entry.question ?: return null
        val alreadyAsked = askedQuestions.any { prior ->
            prior.contains(q.take(40), ignoreCase = true)
        }
        if (alreadyAsked) return null
        return Clarification(question = q, profileSummary = entry.label, remainingCount = 1)
    }
}
