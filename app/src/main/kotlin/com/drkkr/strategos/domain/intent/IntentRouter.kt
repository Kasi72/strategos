package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.IntentDetail
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.human.FallbackShift

/**
 * What an intent handler produced for the turn (BLUEPRINT §4.2). [None] for `standard`, for a support turn, and for an
 * intent whose handler can't answer; one subtype per intent is added with that intent's step (ID3–ID4).
 */
sealed class IntentResult {
    object None : IntentResult()
    /** ID3.1: the hypothetical, run on a copy. */
    data class WhatIf(val block: WhatIfBlock) : IntentResult()
    /** ID3.2: two moves side by side. */
    data class Compare(val block: CompareBlock) : IntentResult()
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
    val support: Boolean = false,
    /** The analysis view BEFORE the walk-away, fallback and Human Layer steps: what a hypothetical's ops apply to. */
    val base: Scenario = view,
    /** The user's figures (the walk-away view, and masking any words a block repeats). */
    val deal: DealFrame? = null,
    /** This turn's fallback shift, so a variant and the sensitivity runs get the same one. */
    val sides: FallbackShift.Sides = FallbackShift.Sides(null, null),
    /** The main turn's own chain (walk-away view and mask, fallback shift, Human Layer), so a variant gets exactly the same steps. */
    val prepare: (Scenario) -> Scenario = TurnChain.prepare(deal, sides),
    /** The engine's pick on a prepared view, through the directive's own guards (walk-away, dominance, stated floor); null = none. */
    val decide: (Scenario) -> String? = { null },
    /** How sharply they pick their best reply ([com.drkkr.strategos.domain.human.ResponseModel.betaFrom]). */
    val beta: Double = 1.0
)

/** One handler per non-standard intent. Each returns [IntentResult.None] until its step (ID3.1–ID4.1) fills it in. */
interface IntentHandlers {
    suspend fun whatIf(input: RouteInput): IntentResult = IntentResult.None
    suspend fun compare(input: RouteInput): IntentResult = IntentResult.None
    suspend fun topOptions(input: RouteInput): IntentResult = IntentResult.None
    suspend fun theirReaction(input: RouteInput): IntentResult = IntentResult.None
    suspend fun planAhead(input: RouteInput): IntentResult = IntentResult.None
}

/**
 * Calls exactly one handler for the turn's intent; `standard` calls none (I-D1: the intent only selects which deterministic
 * Kotlin function runs; the engine still chooses the move). Pure: handlers work on copies and nothing is saved.
 */
class IntentRouter(private val handlers: IntentHandlers = EngineTools.handlers) {
    suspend fun route(input: RouteInput): IntentResult {
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
