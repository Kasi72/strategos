package com.drkkr.strategos.framework.nash

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class PureNashModule : FrameworkModule {
    override val id = "pure-nash"
    override val name = "Pure-Strategy Nash Equilibrium"
    override val version = "1.0"
    override val category = "Strategic form"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("Need normal-form payoffs"))
        return Applicability(0.95, listOf("Enumerate all strategy profiles and best responses across ${sf.players.size} players."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val count = sf.profileCount()
        val equilibria = mutableListOf<List<Int>>()
        for (idx in 0 until count) {
            val profile = sf.profileFor(idx)
            if (isNash(sf, profile)) equilibria += profile
        }
        val findings = if (equilibria.isEmpty())
            listOf(Finding("No pure Nash equilibrium", "No profile is a mutual best response. Consider mixed strategies.", Finding.Severity.NOTE))
        else equilibria.map { profile ->
            val label = sf.players.mapIndexed { i, sid -> "${nameOf(model, sid)}:${labelOfIdx(model, sid, profile[i])}" }.joinToString(", ")
            val payoffs = sf.players.indices.joinToString(", ") { pi -> "%.2f".format(sf.utility[pi][sf.profileIndex(profile)]) }
            Finding("Pure Nash equilibrium", "$label — payoffs (${payoffs})")
        }
        val recs = if (equilibria.size == 1) {
            val p = equilibria.first()
            sf.players.mapIndexed { i, sid ->
                RecommendedAction(sid, "Play ${labelOfIdx(model, sid, p[i])}", "Best response to others' equilibrium play.", lens = RecommendedAction.Lens.EXPECTED)
            }
        } else emptyList()

        val viz = VizSpec(VizSpec.Kind.MATRIX, "Nash Equilibria", mapOf(
            "players" to sf.players.map { nameOf(model, it) },
            "strategies" to sf.strategies.mapKeys { nameOf(model, it.key) }.mapValues { it.value.map { aid -> labelOf(model, aid) } },
            "utility" to sf.utility.map { it.toList() },
            "equilibria" to equilibria
        ))
        return AnalysisResult(
            id, name, canAnalyze(model), findings, recs,
            assumptions = listOf(Assumption("Complete information; simultaneous move")),
            calculations = mapOf("equilibria" to equilibria),
            confidence = if (equilibria.isNotEmpty()) 0.85 else 0.5,
            visualizations = listOf(viz),
            explanation = if (equilibria.size == 1) "Unique pure Nash equilibrium identified."
            else if (equilibria.size > 1) "Multiple equilibria — coordination or refinements needed."
            else "No pure equilibrium; try mixed Nash."
        )
    }

    private fun isNash(sf: MatrixSupport.StrategicForm, profile: List<Int>): Boolean {
        val base = sf.profileIndex(profile)
        for ((pi, _) in sf.players.withIndex()) {
            val size = sf.strategies[sf.players[pi]]!!.size
            val current = sf.utility[pi][base]
            for (alt in 0 until size) if (alt != profile[pi]) {
                val newProfile = profile.toMutableList().also { it[pi] = alt }
                val payoff = sf.utility[pi][sf.profileIndex(newProfile)]
                if (payoff > current + 1e-9) return false
            }
        }
        return true
    }

    private fun nameOf(s: Scenario, sid: String) = s.stakeholders.first { it.id == sid }.name
    private fun labelOf(s: Scenario, aid: String) = s.actions.first { it.id == aid }.label
    private fun labelOfIdx(s: Scenario, sid: String, idx: Int): String {
        val actions = s.actionsFor(sid)
        return actions[idx].label
    }
}
