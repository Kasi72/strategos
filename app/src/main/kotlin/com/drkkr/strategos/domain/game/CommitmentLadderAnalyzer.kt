package com.drkkr.strategos.domain.game

/** E9: Minimal commitment ladder — identifies the earliest binding action on the backward-induction equilibrium path. */
object CommitmentLadderAnalyzer {

    data class CommitmentStep(
        val actionLabel: String,
        /** True when this action forecloses meaningful alternatives (opening move or terminal move). */
        val isBinding: Boolean,
        val reason: String
    )

    data class CommitmentLadder(
        val steps: List<CommitmentStep>,
        /** Index of the first binding step, or null when the path is a single step. */
        val firstBindingIndex: Int?
    )

    /**
     * @param equilibriumPath SPE path from backward induction (non-empty only for game-tree scenarios).
     * @param syntheticPath   Fallback path derived from the focal pure Nash equilibrium action labels,
     *                        used when no game tree exists (normal-form / matrix games).
     */
    fun analyze(equilibriumPath: List<String>, syntheticPath: List<String>? = null): CommitmentLadder? {
        val usingSynthetic = equilibriumPath.isEmpty() && syntheticPath != null
        val path = equilibriumPath.ifEmpty { syntheticPath ?: return null }
        val steps = path.mapIndexed { idx, label ->
            val isBinding = idx == 0 || idx == path.size - 1
            // SCOPE-1: simultaneous (synthetic) paths represent negotiation positions, not sequential moves.
            val reason = if (usingSynthetic) {
                when (idx) {
                    0             -> "Anchor position — sets the reference point for negotiation; costly to concede from"
                    path.size - 1 -> "Response envelope — constrains acceptable counter-offers and signals your limit"
                    else          -> "Intermediate position — adjustable within your negotiation range"
                }
            } else {
                when (idx) {
                    0             -> "Opening move — sets the frame and signals intent; reversible but costly to walk back"
                    path.size - 1 -> "Terminal commitment — determines final outcome directly"
                    else          -> "Intermediate step — depends on prior moves; changing it requires renegotiation"
                }
            }
            CommitmentStep(label, isBinding, reason)
        }
        val firstBindingIdx = steps.indexOfFirst { it.isBinding }.takeIf { it >= 0 && steps.size > 1 }
        return CommitmentLadder(steps, firstBindingIdx)
    }
}
