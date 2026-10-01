package com.drkkr.strategos.framework.dominant

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class DominantStrategyModule : FrameworkModule {
    override val id = "dominant-strategy"
    override val name = "Dominant Strategy Analysis"
    override val version = "1.0"
    override val category = "Strategic form"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("Need ≥2 players with actions and payoffs"))
        return Applicability(
            score = if (sf.players.size in 2..4) 0.9 else 0.5,
            reasons = listOf("Normal-form game with ${sf.players.size} players, checks strictly and weakly dominated strategies.")
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val dominant = mutableMapOf<String, String>()
        val dominated = mutableMapOf<String, MutableList<String>>()
        val findings = mutableListOf<Finding>()

        for ((pi, sid) in sf.players.withIndex()) {
            val strats = sf.strategies[sid]!!
            for (aIdx in strats.indices) {
                var dominatedByAny = false
                for (bIdx in strats.indices) if (aIdx != bIdx) {
                    if (strictlyDominates(sf, pi, bIdx, aIdx)) { dominatedByAny = true; break }
                }
                if (dominatedByAny) dominated.getOrPut(sid) { mutableListOf() }.add(strats[aIdx])
            }
            // dominant strategy: strategy that weakly dominates all others (strict against at least one)
            for (aIdx in strats.indices) {
                val weaklyDominatesAll = strats.indices.filter { it != aIdx }.all { weaklyDominates(sf, pi, aIdx, it) }
                val strictAgainstOne = strats.indices.filter { it != aIdx }.any { strictlyDominates(sf, pi, aIdx, it) }
                if (weaklyDominatesAll && strictAgainstOne) {
                    dominant[sid] = strats[aIdx]
                    findings += Finding("Dominant strategy for ${nameOf(model, sid)}", "${labelOf(model, strats[aIdx])} weakly dominates all alternatives.")
                    break
                }
            }
        }
        if (dominant.isEmpty() && dominated.isEmpty()) findings += Finding("No dominance found", "No player has a dominant or dominated strategy.")

        val recs = dominant.map { (sid, aid) ->
            RecommendedAction(sid, "Play ${labelOf(model, aid)}", "Weakly dominates every alternative.", lens = RecommendedAction.Lens.EXPECTED)
        }

        val viz = VizSpec(VizSpec.Kind.MATRIX, "Payoff Matrix (scalarized)", mapOf(
            "players" to sf.players.map { nameOf(model, it) },
            "strategies" to sf.strategies.mapKeys { nameOf(model, it.key) }.mapValues { it.value.map { aid -> labelOf(model, aid) } },
            "utility" to sf.utility.map { it.toList() }
        ))

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings,
            recommendedActions = recs,
            assumptions = listOf(Assumption("Missing profiles treated as payoff 0"), Assumption("Payoffs scalarized via dimension weights")),
            calculations = mapOf("dominant" to dominant, "dominated" to dominated),
            confidence = if (dominant.isNotEmpty()) 0.8 else 0.5,
            visualizations = listOf(viz),
            explanation = if (dominant.isNotEmpty())
                "Found dominant strategies: ${dominant.entries.joinToString { "${nameOf(model, it.key)} → ${labelOf(model, it.value)}" }}."
            else "No dominant strategies exist. Move to Nash / minimax analysis."
        )
    }

    private fun strictlyDominates(sf: MatrixSupport.StrategicForm, pi: Int, a: Int, b: Int): Boolean {
        val strats = sf.strategies[sf.players[pi]]!!.size
        val otherIndices = otherProfileIndices(sf, pi)
        for (idx in otherIndices) {
            val uA = sf.utility[pi][idx + a * blockSize(sf, pi)]
            val uB = sf.utility[pi][idx + b * blockSize(sf, pi)]
            if (uA <= uB) return false
        }
        return strats > 1
    }

    private fun weaklyDominates(sf: MatrixSupport.StrategicForm, pi: Int, a: Int, b: Int): Boolean {
        val otherIndices = otherProfileIndices(sf, pi)
        for (idx in otherIndices) {
            val uA = sf.utility[pi][idx + a * blockSize(sf, pi)]
            val uB = sf.utility[pi][idx + b * blockSize(sf, pi)]
            if (uA < uB) return false
        }
        return true
    }

    // Layout of profile index: index = a_0 * prod_sizes_after_0 + a_1 * ... + a_{n-1}
    // For player pi we vary its strategy component; the "block size" and other indices reflect that.
    private fun blockSize(sf: MatrixSupport.StrategicForm, pi: Int): Int {
        var mul = 1
        for (i in pi + 1 until sf.players.size) mul *= sf.strategies[sf.players[i]]!!.size
        return mul
    }

    private fun otherProfileIndices(sf: MatrixSupport.StrategicForm, pi: Int): List<Int> {
        val bs = blockSize(sf, pi)
        val playerSize = sf.strategies[sf.players[pi]]!!.size
        val before = sf.utility[pi].size / (bs * playerSize)
        // "outer" contribution index j in [0, before) and "inner" index k in [0, bs)
        val res = mutableListOf<Int>()
        for (j in 0 until before) for (k in 0 until bs) res += j * playerSize * bs + k
        return res
    }

    private fun nameOf(s: Scenario, sid: String) = s.stakeholders.first { it.id == sid }.name
    private fun labelOf(s: Scenario, aid: String) = s.actions.first { it.id == aid }.label
}
