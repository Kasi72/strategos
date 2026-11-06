package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.game.RubricConstants
import com.drkkr.strategos.domain.game.RubricPayoffs
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.Scenario

/**
 * I-H6: every Human Layer number is a judgement call, so a recommendation that only exists because of one of them is fragile.
 * Runs the analysis on the humanised view with the default constants, then with every effect halved and with every effect doubled,
 * and (ID1.5) with the AI's label steps spaced half and twice as far from "same". The fallback shift (ID1.6) is one of the
 * constants, so it is halved and doubled too. If the recommended move differs in any run, the
 * directive is reported FRAGILE.
 */
object HumanSensitivity {
    private const val RECOMMENDED = "Recommended"

    /** [raw] is the analysis view BEFORE [HumanizedView]. */
    fun pickChanges(raw: Scenario, pipeline: AnalysisPipeline, fallback: FallbackShift.Sides = NO_FALLBACK): Boolean =
        pickChanges(raw, fallback, pick(pipeline))

    /** The engine's recommended move for an already-prepared view. */
    fun pick(pipeline: AnalysisPipeline): (Scenario) -> String? = { v ->
        pipeline.run(v).report?.pathways?.firstOrNull { it.label == RECOMMENDED }?.actionId
    }

    private val NO_FALLBACK = FallbackShift.Sides(null, null)

    /** The same check with any way of choosing the recommended move (the engine in the app, a stand-in in tests). */
    fun pickChanges(raw: Scenario, pick: (Scenario) -> String?): Boolean = pickChanges(raw, NO_FALLBACK, pick)

    /** ID1.6: [fallback]'s refusal-cell shift is applied first in every run, and halved and doubled with the other constants. */
    fun pickChanges(raw: Scenario, fallback: FallbackShift.Sides, pick: (Scenario) -> String?): Boolean {
        fun view(s: Scenario, c: HumanConstants) = HumanizedView.apply(FallbackShift.apply(s, fallback, c), c)
        val base = view(raw, HumanConstants.DEFAULT)
        val half = view(raw, HumanConstants.DEFAULT.scaled(0.5))
        val double = view(raw, HumanConstants.DEFAULT.scaled(2.0))
        // Intent Dispatch ID1.5: the label steps are a judgement call too, so their spacing is halved and doubled around "same".
        val labelHalf = labelSpaced(raw, 0.5)
        val labelDouble = labelSpaced(raw, 2.0)
        val humanMoves = half !== base || double !== base
        if (!humanMoves && labelHalf === raw && labelDouble === raw) return false   // nothing to compare
        val chosen = pick(base)
        return (half !== base && pick(half) != chosen) || (double !== base && pick(double) != chosen) ||
            (labelHalf !== raw && pick(view(labelHalf, HumanConstants.DEFAULT)) != chosen) || (labelDouble !== raw && pick(view(labelDouble, HumanConstants.DEFAULT)) != chosen)
    }

    /**
     * [raw] with the AI's label-written payoffs re-spaced: each value's distance from "same" (5.0) multiplied by [factor], within
     * 0–10. A user's own figure and an older reply's numbers ([RubricPayoffs.AI_SCORED_NUMERIC]) are left as they are. The same
     * instance when nothing would move.
     */
    fun labelSpaced(raw: Scenario, factor: Double): Scenario {
        val mid = RubricConstants.DEFAULT.same
        var moved = false
        val payoffs = raw.payoffs.map { p ->
            if (p.factStatus != FactStatus.AI_INFERRED || RubricPayoffs.AI_SCORED_NUMERIC in p.provenanceNote) p
            else p.copy(payoff = MultiPayoff(p.payoff.values.mapValues { (_, v) -> (mid + (v - mid) * factor).coerceIn(0.0, 10.0).also { n -> if (n != v) moved = true } }))
        }
        return if (moved) raw.copy(payoffs = payoffs) else raw
    }
}
