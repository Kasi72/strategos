package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.WhatIfRunner
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.HumanLens
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The `their_reaction` block (BLUEPRINT §5.4): words only, never a probability. */
data class TheirReactionBlock(val move: String, val band: String, val likelyReply: String, val driver: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("move", move); put("band", band); put("likely_reply", likelyReply); driver?.let { put("driver", it) }
    }
}

/**
 * ID3.4: how they would likely take a move: [ResponseModel] on the humanised view as a [ReplyBands] word band, their likelier
 * reply, and the main driver (the strongest Human Layer effect on that move, a fixed line from [HumanLens.driver]). The named
 * move, or the engine's pick when none is named; a move that isn't saved comes from the hypothetical, on a copy.
 */
object TheirReaction {
    suspend fun run(input: RouteInput, pipeline: AnalysisPipeline = AnalysisPipeline()): IntentResult {
        val ownerId = IntakeChecklist.self(input.view)?.id ?: return IntentResult.None
        val named = input.detail?.move?.trim()?.takeIf { it.isNotEmpty() }?.let { WalkAwayMask.text(it, input.deal) }
        var view = input.view
        var move = (named ?: input.decide(view))?.let { Compare.match(view, ownerId, it) }
        if (move == null && named != null && input.whatIfOps.isNotEmpty()) {
            view = input.prepare(WhatIfRunner.run(input.base, input.whatIfOps, pipeline, "their reaction").variant)
            move = Compare.match(view, ownerId, named)
        }
        move ?: return IntentResult.None
        val likely = ResponseModel.likelyReply(view, ownerId, move.id, input.beta) ?: return IntentResult.None
        return IntentResult.TheirReaction(TheirReactionBlock(
            move = move.label, band = ReplyBands.of(likely.pReceptive), likelyReply = likely.replyLabel,
            driver = HumanLens.driver(view.humanContext, move)
        ))
    }
}
