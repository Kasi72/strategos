package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.SequenceNode

/**
 * Backward induction solver for extensive-form games expressed as a [SequenceNode] tree.
 *
 * Algorithm (recursive bottom-up):
 *   - Leaf (no children, non-empty terminalPayoffs) → return its terminal payoffs.
 *   - Chance node (stakeholderId == null) → return probability-weighted expectation over children.
 *   - Decision node → active player picks the child that maximises their own weighted payoff scalar;
 *     returns that child's payoffs and prepends this node's actionLabel to the path.
 *
 * Returns the unique sub-game perfect equilibrium (SPE) path and expected payoffs at the root.
 * Returns null when the tree is null, contains no terminal nodes, or all recursive branches fail.
 */
object BackwardInductionSolver {

    data class Result(
        /** Ordered action labels along the SPE path from root to (first) terminal node. */
        val equilibriumPath: List<String>,
        /** Expected payoffs at the root under SPE, keyed by stakeholder id. */
        val payoffs: Map<String, MultiPayoff>
    )

    fun solve(root: SequenceNode?, weights: Map<PayoffDimension, Double>): Result? {
        root ?: return null
        val (path, payoffMap) = backward(root, weights) ?: return null
        return Result(path, payoffMap)
    }

    private fun backward(
        node: SequenceNode,
        weights: Map<PayoffDimension, Double>
    ): Pair<List<String>, Map<String, MultiPayoff>>? {
        // Leaf node
        if (node.children.isEmpty()) {
            if (node.terminalPayoffs.isEmpty()) return null
            return Pair(listOf(node.actionLabel), node.terminalPayoffs)
        }

        val childSolutions = node.children.mapNotNull { child ->
            backward(child, weights)?.let { child to it }
        }
        if (childSolutions.isEmpty()) return null

        return if (node.stakeholderId == null) {
            // Chance node: expectation weighted by child probability
            val totalProb = node.children.sumOf { it.probability }.coerceAtLeast(1e-9)
            val expectedPayoffs = mutableMapOf<String, MultiPayoff>()
            for ((child, solution) in childSolutions) {
                val w = child.probability / totalProb
                for ((pid, mp) in solution.second) {
                    val scaled = MultiPayoff(mp.values.mapValues { (_, v) -> v * w })
                    expectedPayoffs[pid] = (expectedPayoffs[pid] ?: MultiPayoff()) + scaled
                }
            }
            Pair(listOf(node.actionLabel), expectedPayoffs)
        } else {
            // Decision node: active player picks the child maximising their payoff
            val activeId = node.stakeholderId
            val best = childSolutions.maxByOrNull { (_, sol) ->
                (sol.second[activeId] ?: MultiPayoff()).weighted(weights)
            } ?: return null
            val (_, bestSol) = best
            Pair(listOf(node.actionLabel) + bestSol.first, bestSol.second)
        }
    }
}
