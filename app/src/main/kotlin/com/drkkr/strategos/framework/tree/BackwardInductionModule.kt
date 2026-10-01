package com.drkkr.strategos.framework.tree

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.SequenceNode

class BackwardInductionModule : FrameworkModule {
    override val id = "backward-induction"
    override val name = "Backward Induction (Sequential / SPNE)"
    override val version = "1.0"
    override val category = "Extensive form"

    override fun canAnalyze(model: Scenario): Applicability =
        if (model.gameTree != null) Applicability(0.9, listOf("Game tree present; computes SPE via backward induction."))
        else Applicability(0.0, listOf("No game tree defined"))

    override fun analyze(model: Scenario): AnalysisResult {
        val tree = model.gameTree!!
        val trace = mutableListOf<String>()
        val optimal = solve(tree, model, trace, mutableSetOf())

        val findings = listOf(
            Finding("Subgame-perfect outcome", trace.joinToString(" → ")),
            Finding("Expected payoffs at root", optimal.entries.joinToString { "${nameOf(model, it.key)}:${"%.3f".format(it.value.scalar())}" })
        )
        val viz = VizSpec(VizSpec.Kind.TREE, "Game tree with SPE path", mapOf(
            "tree" to serializeTree(tree, model),
            "trace" to trace
        ))
        return AnalysisResult(
            id, name, canAnalyze(model), findings,
            recommendedActions = listOf(RecommendedAction(null, trace.firstOrNull() ?: "—", "SPE-optimal first move.")),
            assumptions = listOf(Assumption("Players are sequentially rational"), Assumption("Common knowledge of rationality")),
            calculations = mapOf("trace" to trace, "rootPayoffs" to optimal.mapValues { it.value.scalar() }),
            confidence = 0.8,
            visualizations = listOf(viz),
            explanation = "Backward induction rolls back from terminal nodes, selecting each player's best response."
        )
    }

    private fun solve(node: SequenceNode, model: Scenario, trace: MutableList<String>, visited: MutableSet<String>): Map<String, MultiPayoff> {
        if (!visited.add(node.id)) return emptyMap()
        if (node.children.isEmpty()) return node.terminalPayoffs
        if (node.stakeholderId == null) {
            // Chance node: expected payoffs over children
            val childPayoffs = node.children.map { it.probability to solve(it, model, trace, visited) }
            val all = childPayoffs.flatMap { it.second.keys }.distinct()
            return all.associateWith { sid ->
                childPayoffs.fold(MultiPayoff()) { acc, (p, m) -> acc + (m[sid]?.scaleBy(p) ?: MultiPayoff()) }
            }
        }
        var bestPayoff: Map<String, MultiPayoff>? = null
        var bestNode: SequenceNode? = null
        for (child in node.children) {
            val payoffs = solve(child, model, trace, visited)
            val focalPayoff = payoffs[node.stakeholderId]?.scalar() ?: 0.0
            val bp = bestPayoff
            if (bp == null || focalPayoff > (bp[node.stakeholderId]?.scalar() ?: 0.0)) {
                bestPayoff = payoffs; bestNode = child
            }
        }
        trace.add(0, bestNode!!.actionLabel)
        return bestPayoff!!
    }

    private fun MultiPayoff.scaleBy(p: Double) = MultiPayoff(values.mapValues { it.value * p })
    private fun serializeTree(n: SequenceNode, model: Scenario): Map<String, Any?> = mapOf(
        "id" to n.id,
        "player" to (n.stakeholderId?.let { nameOf(model, it) } ?: "Chance"),
        "action" to n.actionLabel,
        "prob" to n.probability,
        "terminal" to n.terminalPayoffs.mapValues { it.value.scalar() },
        "children" to n.children.map { serializeTree(it, model) }
    )

    private fun nameOf(s: Scenario, sid: String) = s.stakeholders.firstOrNull { it.id == sid }?.name ?: sid
}
