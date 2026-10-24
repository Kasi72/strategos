package com.drkkr.strategos.framework.minimax

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class MinimaxModule : FrameworkModule {
    override val id = "minimax-regret"
    override val name = "Minimax / Maximin / Minimax-Regret"
    override val version = "1.0"
    override val category = "Robust decision"

    override fun canAnalyze(model: Scenario): Applicability {
        MatrixSupport.build(model) ?: return Applicability(0.0, listOf("Requires payoff matrix"))
        return Applicability(0.75, listOf("Provides worst-case-safe (maximin) and lowest-regret options for focal player ${model.stakeholders[0].name}."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.buildOrThrow(model)
        val focal = 0
        val playerSid = sf.players[focal]
        val strats = sf.strategies[playerSid]!!
        val nStr = strats.size

        // Group utility[focal] by focal's strategy over opponent profiles.
        val grouped = Array(nStr) { mutableListOf<Double>() }
        for (idx in sf.utility[focal].indices) {
            val prof = sf.profileFor(idx)
            grouped[prof[focal]].add(sf.utility[focal][idx])
        }

        val maximin = grouped.mapIndexed { i, list -> strats[i] to list.min() }.maxBy { it.second }
        val minimax = grouped.mapIndexed { i, list -> strats[i] to list.max() }.minBy { it.second } // for a hostile player picking worst-for-us

        // Regret table: regret(a, state) = max_a' payoff(a', state) - payoff(a, state)
        val stateCount = grouped[0].size
        val bestPerState = DoubleArray(stateCount) { st -> (0 until nStr).maxOf { grouped[it][st] } }
        val maxRegret = DoubleArray(nStr) { a -> (0 until stateCount).maxOf { st -> bestPerState[st] - grouped[a][st] } }
        val minimaxRegret = maxRegret.withIndex().minBy { it.value }
        val minimaxRegretStrat = strats[minimaxRegret.index]

        val findings = listOf(
            Finding("Maximin (worst-case safe)", "${labelOf(model, maximin.first)} — guaranteed at least ${"%.3f".format(maximin.second)}"),
            Finding("Minimax", "${labelOf(model, minimax.first)} — even a hostile environment caps you at ${"%.3f".format(minimax.second)}"),
            Finding("Minimax-regret", "${labelOf(model, minimaxRegretStrat)} — max regret ${"%.3f".format(minimaxRegret.value)}")
        )
        val recs = listOf(
            RecommendedAction(playerSid, "Play ${labelOf(model, maximin.first)}", "Highest guaranteed payoff.", lens = RecommendedAction.Lens.RISK_AVERSE),
            RecommendedAction(playerSid, "Play ${labelOf(model, minimaxRegretStrat)}", "Minimizes worst-case regret.", lens = RecommendedAction.Lens.LOW_REGRET)
        )
        val viz = VizSpec(VizSpec.Kind.TABLE, "Worst / best / regret per action", mapOf(
            "labels" to strats.map { labelOf(model, it) },
            "worst" to grouped.map { it.min() },
            "best" to grouped.map { it.max() },
            "maxRegret" to maxRegret.toList()
        ))
        return AnalysisResult(
            id, name, canAnalyze(model), findings, recs,
            assumptions = listOf(Assumption("States = opponents' joint strategy profiles"),
                Assumption("Regret = ex-post gap vs best action for that state")),
            calculations = mapOf(
                "maximin" to maximin, "minimax" to minimax, "minimaxRegret" to minimaxRegretStrat,
                "maxRegret" to maxRegret.toList()
            ),
            confidence = 0.7,
            visualizations = listOf(viz),
            explanation = "Maximin and minimax-regret surface robust choices when opponent behavior is uncertain."
        )
    }

    private fun labelOf(s: Scenario, aid: String) = s.actions.first { it.id == aid }.label
}
