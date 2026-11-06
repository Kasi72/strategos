package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.WhatIfRunner
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.HumanConstants
import com.drkkr.strategos.domain.human.HumanSensitivity
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One side of the comparison: the move, and their likely reply in words. */
data class ComparedMove(val move: String, val theirLikelyReply: String, val band: String, val risk: String? = null)

/** The `compare` block (BLUEPRINT §5.2). No number anywhere. */
data class CompareBlock(val better: String, val a: ComparedMove, val b: ComparedMove, val why: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("better", better)
        fun side(k: String, m: ComparedMove) = putJsonObject(k) {
            put("move", m.move); put("their_likely_reply", m.theirLikelyReply); put("band", m.band); m.risk?.let { put("risk", it) }
        }
        side("a", a); side("b", b)
        put("why", why)
    }
}

/**
 * ID3.2: move A against move B. The expected value under [ResponseModel] only orders them (never shown). A move that isn't
 * saved is added on a copy from the hypothetical's ops. "too_close" when the halved/doubled sensitivity runs (the Human Layer's
 * constants, the fallback shift and the label spacing, as in [HumanSensitivity]) flip the order, or on a tie.
 */
object Compare {
    const val A = "A"
    const val B = "B"
    const val TOO_CLOSE = "too_close"

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!')

    /** The existing loose matcher's rule: exact (normalised) first, else the one option whose wording contains, or is contained in, it. */
    fun match(view: Scenario, ownerId: String, label: String): Action? {
        val mine = view.actionsFor(ownerId)
        val n = norm(label)
        mine.firstOrNull { norm(it.label) == n }?.let { return it }
        return mine.filter { val o = norm(it.label); o.isNotEmpty() && n.isNotEmpty() && (o.contains(n) || n.contains(o)) }.singleOrNull()
    }

    /** The fixed `why` lines, chosen by what the comparison found. */
    fun why(winner: String?, loser: String?, winnerLikelier: Boolean): String = when {
        winner == null || loser == null -> "These two are too close to call: a small change in how they'd react flips the order."
        winnerLikelier -> "$winner is the likelier to get a yes, and it serves you better overall than $loser."
        else -> "$winner is less likely to get a yes than $loser, but it pays off enough more when it works to come out ahead."
    }

    suspend fun run(input: RouteInput, pipeline: AnalysisPipeline = AnalysisPipeline()): IntentResult {
        val wantA = input.detail?.moveA?.trim()?.takeIf { it.isNotEmpty() } ?: return IntentResult.None
        val wantB = input.detail?.moveB?.trim()?.takeIf { it.isNotEmpty() } ?: return IntentResult.None
        val ownerId = IntakeChecklist.self(input.view)?.id ?: return IntentResult.None
        val la = WalkAwayMask.text(wantA, input.deal); val lb = WalkAwayMask.text(wantB, input.deal)
        // Saved moves first; a move that isn't saved comes from the hypothetical, on a copy (I-D3).
        var raw = input.base
        var view = input.view
        var a = match(view, ownerId, la); var b = match(view, ownerId, lb)
        if ((a == null || b == null) && input.whatIfOps.isNotEmpty()) {
            raw = WhatIfRunner.run(input.base, input.whatIfOps, pipeline, "compare").variant
            view = input.prepare(raw)
            a = match(view, ownerId, la); b = match(view, ownerId, lb)
        }
        if (a == null || b == null || a.id == b.id) return IntentResult.None

        fun ev(v: Scenario, id: String) = ResponseModel.expectedValue(v, ownerId, id, input.beta)
        fun order(v: Scenario): Int? {
            val x = ev(v, a.id) ?: return null; val y = ev(v, b.id) ?: return null
            return x.compareTo(y)
        }
        val base = order(view) ?: return IntentResult.None
        val runs = listOf(0.5, 2.0).map { f -> TurnChain.prepare(input.deal, input.sides, HumanConstants.DEFAULT.scaled(f))(raw) } +
            listOf(0.5, 2.0).map { f -> input.prepare(HumanSensitivity.labelSpaced(raw, f)) }
        val flips = base == 0 || runs.any { v -> order(v)?.let { it != base } == true }

        fun side(m: Action): ComparedMove {
            val likely = ResponseModel.likelyReply(view, ownerId, m.id, input.beta)
            return ComparedMove(m.label, likely?.replyLabel.orEmpty(), likely?.let { ReplyBands.of(it.pReceptive) } ?: ReplyBands.EITHER_WAY)
        }
        val sa = side(a); val sb = side(b)
        val better = if (flips) TOO_CLOSE else if (base > 0) A else B
        val (w, l) = when (better) { A -> a to b; B -> b to a; else -> null to null }
        val likelier = if (w == null || l == null) false else
            (ResponseModel.receptiveProbability(view, ownerId, w.id, input.beta) ?: 0.0) >= (ResponseModel.receptiveProbability(view, ownerId, l.id, input.beta) ?: 0.0)
        return IntentResult.Compare(CompareBlock(better, sa, sb, why(w?.label, l?.label, likelier)))
    }
}
