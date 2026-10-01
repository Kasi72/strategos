package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class LevelKModule : FrameworkModule {
    override val id = "level-k"
    override val name = "Level-K Thinking (Cognitive Hierarchy)"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix — Level-K analysis requires utility data."))
        val text = "${model.title} ${model.description}".lowercase()
        val competitive = model.zeroSum || listOf("compet", "rival", "auction", "bid", "versus", "market", "race", "guess", "beat").count { text.contains(it) } >= 2
        val score = if (competitive) 0.78 else 0.48
        val reason = if (competitive) "Competitive context detected — Level-K reasoning is most predictively powerful in novel competitive games."
        else "Level-K applicable as bounded rationality model for ${sf.players.size}-player interaction."
        return Applicability(score, listOf(reason, "Iterates best-responses from Level-0 (uniform random) to Level-3 to model actual human strategic thinking."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val findings = mutableListOf<Finding>()

        // Level-0 belief: uniform random over each player's strategies
        val initialBeliefs: MutableMap<String, DoubleArray> = sf.players.associateWith { pid ->
            val nActions = sf.strategies[pid]!!.size
            DoubleArray(nActions) { 1.0 / nActions }
        }.toMutableMap()

        // Compute best response for player pi given beliefs over others
        fun bestResponse(playerIdx: Int, beliefs: Map<String, DoubleArray>): Int {
            val pid = sf.players[playerIdx]
            val nActions = sf.strategies[pid]!!.size
            var bestAction = 0; var bestEU = Double.NEGATIVE_INFINITY
            for (a in 0 until nActions) {
                var eu = 0.0
                val count = sf.profileCount()
                for (profileIdx in 0 until count) {
                    val profile = sf.profileFor(profileIdx)
                    if (profile[playerIdx] != a) continue
                    var weight = 1.0
                    for ((otherIdx, otherPid) in sf.players.withIndex()) {
                        if (otherIdx == playerIdx) continue
                        val mix = beliefs[otherPid]
                        val nOther = sf.strategies[otherPid]?.size ?: 1
                        weight *= mix?.getOrNull(profile[otherIdx]) ?: (1.0 / nOther)
                    }
                    eu += weight * sf.utility[playerIdx][profileIdx]
                }
                if (eu > bestEU) { bestEU = eu; bestAction = a }
            }
            return bestAction
        }

        val levelActions = mutableMapOf<Int, List<Int>>()
        var currentBeliefs = initialBeliefs

        for (level in 1..3) {
            val actions = sf.players.indices.map { pi -> bestResponse(pi, currentBeliefs) }
            levelActions[level] = actions
            // Next level believes opponent is exactly at this level
            currentBeliefs = sf.players.indices.associate { pi ->
                val pid = sf.players[pi]
                val nActions = sf.strategies[pid]!!.size
                val mix = DoubleArray(nActions)
                mix[actions[pi]] = 1.0
                pid to mix
            }.toMutableMap()
        }

        for (level in 1..3) {
            val actions = levelActions[level] ?: continue
            val prevLevel = level - 1
            val label = sf.players.mapIndexed { pi, pid ->
                val playerName = model.stakeholders.firstOrNull { it.id == pid }?.name ?: pid
                val actionLabel = model.actionsFor(pid).getOrNull(actions[pi])?.label ?: "action-${actions[pi]}"
                "$playerName: $actionLabel"
            }.joinToString(", ")
            findings += Finding(
                "Level-$level response (assumes opponent is L$prevLevel)",
                label,
                Finding.Severity.INFO
            )
        }

        val l2 = levelActions[2]
        val l3 = levelActions[3]
        val converged = l2 != null && l3 != null && l2 == l3

        findings += if (converged)
            Finding(
                "Level-K convergence at L2 — stable prediction",
                "Best responses stabilise from Level-2 onward. This is the predicted equilibrium for boundedly rational players — most humans play L1–L2 in experimental studies.",
                Finding.Severity.INFO
            )
        else
            Finding(
                "No Level-K convergence by L3",
                "Strategic responses keep cycling through L3. Game likely has no dominant strategy — mixed strategy equilibrium or very high strategic depth required.",
                Finding.Severity.NOTE
            )

        findings += Finding(
            "Empirical distribution: ~40% L1, ~35% L2, ~10% L3+ (Camerer, Ho & Chong 2004)",
            "Assuming a Level-1 opponent is optimal in most one-shot competitive interactions. Against sophisticated players (institutional, expert), Level-2 or Level-3 is warranted.",
            Finding.Severity.NOTE
        )

        val recs = mutableListOf<RecommendedAction>()
        l2?.let { actions ->
            sf.players.forEachIndexed { pi, pid ->
                val actionLabel = model.actionsFor(pid).getOrNull(actions[pi])?.label ?: "action-${actions[pi]}"
                recs += RecommendedAction(pid, actionLabel,
                    "Level-2 best response — assumes opponent is Level-1 (best response to random play). Modal strategic sophistication in behavioral experiments.",
                    lens = RecommendedAction.Lens.EXPECTED)
            }
        }
        recs += RecommendedAction(null, "Calibrate Level-K to opponent profile",
            "Naive/time-pressured opponent: play L2 (assume they play L1). Experienced/expert opponent: play L3 (assume they play L2). Look for cues: domain expertise, deliberation time, past strategic choices.",
            lens = RecommendedAction.Lens.LONG_TERM)

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Level-0: uniform random play — no strategic reasoning."),
                Assumption("Level-k: best response to a belief that opponent plays level-(k-1)."),
                Assumption("Empirical distribution: Camerer, Ho & Chong (2004) cognitive hierarchy model — most humans are L1–L2.")
            ),
            calculations = mapOf(
                "level1" to (levelActions[1]?.joinToString() ?: ""),
                "level2" to (levelActions[2]?.joinToString() ?: ""),
                "level3" to (levelActions[3]?.joinToString() ?: ""),
                "converged" to converged
            ),
            confidence = if (converged) 0.75 else 0.52,
            visualizations = emptyList(),
            explanation = "Level-K / Cognitive Hierarchy model: iterates best responses from naive L0 play. Predicts actual human behavior better than Nash equilibrium in novel one-shot games. ${if (converged) "Convergence at L2 gives a stable, empirically supported prediction." else "No convergence by L3 — mixed strategies or deeper analysis recommended."}"
        )
    }
}
