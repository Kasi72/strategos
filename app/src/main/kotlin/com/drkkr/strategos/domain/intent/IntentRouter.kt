package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.IntentDetail
import com.drkkr.strategos.domain.model.Scenario

/**
 * What an intent handler produced for the turn (BLUEPRINT §4.2). [None] for `standard`, for a support turn, and for an
 * intent whose handler can't answer; one subtype per intent is added with that intent's step (ID3–ID4).
 */
sealed class IntentResult {
    object None : IntentResult()
}

/**
 * Everything a handler may read. [view] is the main turn's analysis view (walk-away, fallback and Human Layer applied), a
 * value the handlers only copy; [whatIfOps] are the hypothetical's ops, converted by the agent layer and never saved (I-D3).
 */
data class RouteInput(
    val intent: Intent,
    val view: Scenario,
    val whatIfOps: List<ChangeOp> = emptyList(),
    val detail: IntentDetail? = null,
    /** A support or safety turn: no handler runs (I-D4). */
    val support: Boolean = false
)

/** One handler per non-standard intent. Each returns [IntentResult.None] until its step (ID3.1–ID4.1) fills it in. */
interface IntentHandlers {
    fun whatIf(input: RouteInput): IntentResult = IntentResult.None
    fun compare(input: RouteInput): IntentResult = IntentResult.None
    fun topOptions(input: RouteInput): IntentResult = IntentResult.None
    fun theirReaction(input: RouteInput): IntentResult = IntentResult.None
    fun planAhead(input: RouteInput): IntentResult = IntentResult.None
}

/**
 * Calls exactly one handler for the turn's intent; `standard` calls none (I-D1: the intent only selects which deterministic
 * Kotlin function runs; the engine still chooses the move). Pure: handlers work on copies and nothing is saved.
 */
class IntentRouter(private val handlers: IntentHandlers = EngineTools.handlers) {
    fun route(input: RouteInput): IntentResult {
        if (input.support) return IntentResult.None
        return when (input.intent) {
            Intent.STANDARD -> IntentResult.None
            Intent.WHAT_IF -> handlers.whatIf(input)
            Intent.COMPARE -> handlers.compare(input)
            Intent.TOP_OPTIONS -> handlers.topOptions(input)
            Intent.THEIR_REACTION -> handlers.theirReaction(input)
            Intent.PLAN_AHEAD -> handlers.planAhead(input)
        }
    }
}
