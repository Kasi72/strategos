package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.a2ui.TurnCards
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.intent.IdentifyGaps
import kotlinx.serialization.Serializable

@Serializable data class QuickAnswer(val label: String, val text: String)
@Serializable data class OptionCard(val role: String, val move: String, val tradeoff: String = "")

/**
 * The cards under a reply, by the app's TurnCards rules (no model involved): quick answers to the engine's closing question
 * only when the shown reply asks it, and the ranked options only when the reply names every one of them. Tapping either
 * sends a message, exactly as in the app.
 */
@Serializable
data class ReplyCards(
    val quickAnswers: List<QuickAnswer> = emptyList(),
    val options: List<OptionCard> = emptyList(),
    /** The reply as a strategy brief; when present it carries the options itself, so [options] stays empty. */
    val brief: StrategyBrief? = null,
    /** The reply as a direct answer (ANSWER mode). */
    val answer: AnswerCard? = null,
    /** The reply's shape: "brief", "compare", "answer" or "script" (the classic card); empty on old turns and non-advice turns. */
    val mode: String = "",
) {
    val isEmpty: Boolean get() = quickAnswers.isEmpty() && options.isEmpty() && brief == null && answer == null && mode.isEmpty()

    companion object {
        fun of(shownReply: String, d: EngineDirective?, otherName: String?, brief: StrategyBrief? = null,
               answer: AnswerCard? = null, mode: String = ""): ReplyCards {
            if (d == null) return ReplyCards(mode = mode)
            // A brief lays out the options itself, and its follow-up is the closing question.
            if (brief != null) return of(brief.followUp, d, otherName).copy(options = emptyList(), brief = brief, mode = mode)
            if (answer != null) return of(answer.followUp, d, otherName).copy(options = emptyList(), answer = answer, mode = mode)
            if (mode.isNotEmpty()) return of(shownReply, d, otherName).copy(mode = mode)
            val q = d.nextQuestion?.takeIf { shownReply.contains(it.text) }
            val name = otherName?.takeIf { it.isNotBlank() }
            val answers = if (q == null || name == null) emptyList() else when (q.id) {
                IdentifyGaps.THEIR_REPLY -> listOf(
                    QuickAnswer("Usually engages", "$name usually engages when I push on something like this."),
                    QuickAnswer("Usually digs in", "$name usually digs in when I push on something like this."),
                    QuickAnswer("It varies", "It varies: sometimes $name engages, sometimes $name digs in."))
                IdentifyGaps.DECIDER -> listOf(
                    QuickAnswer("$name decides", "$name makes the final call."),
                    QuickAnswer("Someone else", "Someone else makes the final call, not $name."),
                    QuickAnswer("Not sure", "I'm not sure who makes the final call."))
                else -> emptyList()
            }
            val top = d.topOptions?.filter { it.move.isNotBlank() }?.take(3)
                ?.takeIf { it.size >= 2 && it.all { o -> shownReply.contains(o.move, ignoreCase = true) } }
            return ReplyCards(answers, top.orEmpty().map { OptionCard(TurnCards.ROLE_LABELS[it.role] ?: "Option", it.move, it.tradeoff) })
        }
    }
}
