package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class IteratedDominanceModule : FrameworkModule {
    override val id = "iterated-dominance"
    override val name = "Iterated Dominance & Rationalizability"
    override val version = "1.0"
    override val category = "Strategic Form"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix — IDSDS requires utility data."))
        if (sf.players.size < 2) return Applicability(0.0, listOf("IDSDS requires ≥2 players."))
        val totalStrategies = sf.players.sumOf { sf.strategies[it]?.size ?: 0 }
        if (totalStrategies < 4) return Applicability(0.30, listOf("Matrix too small for meaningful IDSDS."))
        return Applicability(
            0.82,
            listOf(
                "IDSDS applies to all finite strategic-form games.",
                "Identifies rationalizable strategies — those consistent with common knowledge of rationality (Bernheim 1984, Pearce 1984)."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.buildOrThrow(model)
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        // surviving[pid] = mutable set of surviving action indices for that player
        val surviving: MutableMap<String, MutableSet<Int>> = sf.players.associateWith { pid ->
            (0 until (sf.strategies[pid]?.size ?: 0)).toMutableSet()
        }.toMutableMap()

        var round = 0
        var changed = true
        val eliminationLog = mutableListOf<String>()

        while (changed) {
            changed = false
            round++
            for ((playerIdx, pid) in sf.players.withIndex()) {
                val survSet = surviving[pid] ?: continue
                if (survSet.size <= 1) continue
                val toRemove = mutableSetOf<Int>()
                val survList = survSet.toList()
                for (dominated in survList) {
                    val candidates = survList.filter { it != dominated }
                    if (candidates.isEmpty()) continue
                    if (isStrictlyDominated(sf, playerIdx, dominated, candidates, surviving)) {
                        toRemove += dominated
                    }
                }
                if (toRemove.isNotEmpty()) {
                    changed = true
                    val playerName = model.stakeholders.firstOrNull { it.id == pid }?.name ?: pid
                    toRemove.forEach { idx ->
                        val actionLabel = model.actionsFor(pid).getOrNull(idx)?.label ?: "action-$idx"
                        eliminationLog += "Round $round — ${playerName}: '$actionLabel' eliminated (strictly dominated)"
                        survSet.remove(idx)
                    }
                }
            }
        }

        val totalEliminated = sf.players.sumOf { pid ->
            (sf.strategies[pid]?.size ?: 0) - (surviving[pid]?.size ?: 0)
        }

        if (totalEliminated == 0) {
            findings += Finding(
                "No strategies eliminated by IDSDS — all strategies are rationalizable",
                "Every strategy survives iterated strict dominance. No action is strictly dominated even after removing other dominated actions. The full strategy space is consistent with common knowledge of rationality.",
                Finding.Severity.INFO
            )
        } else {
            findings += Finding(
                "$totalEliminated strategies eliminated over ${round - 1} rounds of IDSDS",
                eliminationLog.joinToString("\n"),
                Finding.Severity.INFO
            )
        }

        // Report surviving (rationalizable) strategies per player
        var uniqueForAll = true
        sf.players.forEach { pid ->
            val survSet = surviving[pid] ?: return@forEach
            val playerName = model.stakeholders.firstOrNull { it.id == pid }?.name ?: pid
            val survLabels = survSet.sorted().map { idx ->
                model.actionsFor(pid).getOrNull(idx)?.label ?: "action-$idx"
            }
            if (survLabels.size == 1) {
                findings += Finding(
                    "$playerName: unique rationalizable strategy — '${survLabels[0]}'",
                    "IDSDS uniquely determines this action. It is $playerName's only strategy consistent with common knowledge of rationality — effectively a dominant strategy solution.",
                    Finding.Severity.NOTE
                )
                recs += RecommendedAction(
                    pid, survLabels[0],
                    "Unique IDSDS survivor — the only action consistent with common knowledge of rationality for $playerName.",
                    lens = RecommendedAction.Lens.EXPECTED
                )
            } else {
                uniqueForAll = false
                findings += Finding(
                    "$playerName: ${survLabels.size} rationalizable strategies — ${survLabels.joinToString(", ")}",
                    "Multiple strategies survive IDSDS for $playerName. Final choice depends on higher-order beliefs about opponents' strategies.",
                    Finding.Severity.NOTE
                )
            }
        }

        if (uniqueForAll && sf.players.size >= 2) {
            findings += Finding(
                "IDSDS yields unique rationalizable outcome",
                "All players have a unique surviving strategy — IDSDS uniquely solves the game. This outcome is the only one consistent with common knowledge of rationality among all players.",
                Finding.Severity.INFO
            )
        }

        // Contrast with IDWDS
        findings += Finding(
            "IDWDS (weak dominance) may eliminate further strategies",
            "Iterated deletion of WEAKLY dominated strategies can reduce the space beyond IDSDS. However, IDWDS is order-dependent and may eliminate Nash equilibria — it is a stronger but less robust refinement. IDSDS (strict) is always order-independent and preserves every Nash equilibrium.",
            Finding.Severity.INFO
        )

        findings += Finding(
            "Foundation: Bernheim (1984) & Pearce (1984) rationalizability",
            "A strategy is rationalizable if and only if it survives IDSDS. Rationalizability is the weakest solution concept grounded in common knowledge of rationality — weaker than Nash equilibrium but conceptually more transparent.",
            Finding.Severity.NOTE
        )

        val totalSurvivors = sf.players.sumOf { surviving[it]?.size ?: 0 }
        val totalStrategies = sf.players.sumOf { sf.strategies[it]?.size ?: 0 }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("IDSDS uses pure-strategy strict dominance only (mixed-strategy dominance not checked — conservative)."),
                Assumption("IDSDS is order-independent: the surviving set is identical regardless of elimination order (Gilboa et al. 1990)."),
                Assumption("Every Nash equilibrium consists of rationalizable strategies — IDSDS never eliminates any NE.")
            ),
            calculations = mapOf(
                "roundsNeeded" to (round - 1),
                "eliminatedStrategies" to totalEliminated,
                "survivingStrategies" to totalSurvivors,
                "totalStrategies" to totalStrategies,
                "rationalizableSetSize" to totalSurvivors
            ),
            confidence = 0.88,
            visualizations = emptyList(),
            explanation = "Iterated Deletion of Strictly Dominated Strategies (IDSDS / Bonanno Ch 2.5): $totalEliminated strategies eliminated, $totalSurvivors rationalizable strategies remain. The surviving set = rationalizable strategies (Bernheim & Pearce 1984) — exactly those consistent with common knowledge of rationality."
        )
    }

    private fun isStrictlyDominated(
        sf: MatrixSupport.StrategicForm,
        playerIdx: Int,
        dominated: Int,
        candidates: List<Int>,
        surviving: Map<String, MutableSet<Int>>
    ): Boolean {
        val otherProfiles = getAllOtherProfiles(sf, playerIdx, surviving)
        if (otherProfiles.isEmpty()) return false
        for (dominator in candidates) {
            var strictlyDominates = true
            for (otherActions in otherProfiles) {
                val profileDominated = buildProfile(sf.players.size, playerIdx, dominated, otherActions)
                val profileDominator = buildProfile(sf.players.size, playerIdx, dominator, otherActions)
                val uDominated = sf.utility[playerIdx][sf.profileIndex(profileDominated)]
                val uDominator = sf.utility[playerIdx][sf.profileIndex(profileDominator)]
                if (uDominated >= uDominator) {
                    strictlyDominates = false
                    break
                }
            }
            if (strictlyDominates) return true
        }
        return false
    }

    private fun buildProfile(numPlayers: Int, playerIdx: Int, action: Int, otherActions: List<Int>): List<Int> {
        val profile = MutableList(numPlayers) { 0 }
        profile[playerIdx] = action
        var otherIdx = 0
        for (i in profile.indices) {
            if (i != playerIdx) profile[i] = otherActions[otherIdx++]
        }
        return profile
    }

    private fun getAllOtherProfiles(
        sf: MatrixSupport.StrategicForm,
        playerIdx: Int,
        surviving: Map<String, MutableSet<Int>>
    ): List<List<Int>> {
        val otherPlayerIndices = sf.players.indices.filter { it != playerIdx }
        val otherSets = otherPlayerIndices.map { pi ->
            (surviving[sf.players[pi]] ?: setOf()).toList()
        }
        return cartesianProduct(otherSets)
    }

    private fun cartesianProduct(lists: List<List<Int>>): List<List<Int>> {
        if (lists.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        val rest = cartesianProduct(lists.drop(1))
        for (item in lists[0]) {
            for (others in rest) {
                result += listOf(item) + others
            }
        }
        return result
    }
}
