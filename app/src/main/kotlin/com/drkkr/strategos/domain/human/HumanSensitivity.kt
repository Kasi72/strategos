package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Scenario

/**
 * I-H6: every Human Layer number is a judgement call, so a recommendation that only exists because of one of them is fragile.
 * Runs the analysis on the humanised view with the default constants, then with every effect halved and with every effect doubled.
 * If the recommended move differs in either run, the directive is reported FRAGILE.
 */
object HumanSensitivity {
    private const val RECOMMENDED = "Recommended"

    /** [raw] is the analysis view BEFORE [HumanizedView]. */
    fun pickChanges(raw: Scenario, pipeline: AnalysisPipeline): Boolean = pickChanges(raw) { v ->
        pipeline.run(v).report?.pathways?.firstOrNull { it.label == RECOMMENDED }?.actionId
    }

    /** The same check with any way of choosing the recommended move (the engine in the app, a stand-in in tests). */
    fun pickChanges(raw: Scenario, pick: (Scenario) -> String?): Boolean {
        val base = HumanizedView.apply(raw)
        val half = HumanizedView.apply(raw, HumanConstants.DEFAULT.scaled(0.5))
        val double = HumanizedView.apply(raw, HumanConstants.DEFAULT.scaled(2.0))
        if (half === base && double === base) return false                      // the constants change nothing, so there is nothing to compare
        val chosen = pick(base)
        return (half !== base && pick(half) != chosen) || (double !== base && pick(double) != chosen)
    }
}
