package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.Serializable

/** A move of "You" that was dropped from the options while even its best case left you worse off than today. */
@Serializable
data class RuledOutMove(val label: String, val best: Double)

/**
 * Moves the engine already judged, kept after the options are refreshed (the Bumble project, 7 Nov: "tell her straight away
 * you want to marry her" scored 2/10 on turn 2, was dropped from the options, and came back on turn 13 reworded as "send her one
 * clear message stating your intention" with fresh, high scores). Call 1 says which new option is the same move again
 * (`revisits`) and what changed since (`changed`); Kotlin keeps the old ceiling on its scores unless something did.
 */
object RuledOut {
    const val MAX = 8

    /** "The same as today" on the 0–10 scale (`RubricConstants.same`). A best case under it is worse than today. */
    const val SAME = 5.0

    /** How good [v] is on [dim], 0–10 with higher better (COST is stored as a cost, so it is flipped). */
    fun goodness(dim: PayoffDimension, v: Double): Double = if (dim.higherIsBetter) v else 10.0 - v

    /** [v] held to at most [ceiling] goodness on [dim]. */
    fun cap(dim: PayoffDimension, v: Double, ceiling: Double): Double =
        if (dim.higherIsBetter) minOf(v, ceiling) else maxOf(v, 10.0 - ceiling)

    /** The best outcome for [ownerId] over every reply to [actionId] (mean goodness across the dimensions), or null if unscored. */
    fun bestCase(s: Scenario, ownerId: String, actionId: String): Double? =
        s.payoffs.filter { it.stakeholderId == ownerId && it.actionProfile[ownerId] == actionId }
            .mapNotNull { e -> e.payoff.values.entries.map { (d, v) -> goodness(d, v) }.takeIf { it.isNotEmpty() }?.average() }
            .maxOrNull()

    /** True when even the best case is worse than today. */
    fun isRuledOut(best: Double?): Boolean = best != null && best < SAME

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.')

    /** The saved entry Call 1 named in `revisits`: the same words, or one wording inside the other. */
    fun find(saved: List<RuledOutMove>, named: String?): RuledOutMove? {
        val n = named?.let(::norm)?.takeIf { it.length >= 4 } ?: return null
        return saved.firstOrNull { norm(it.label) == n } ?: saved.firstOrNull { norm(it.label).contains(n) || n.contains(norm(it.label)) }
    }

    /** Newest first, one per wording, at most [MAX]. */
    fun union(newer: List<RuledOutMove>, older: List<RuledOutMove>): List<RuledOutMove> =
        (newer + older).distinctBy { norm(it.label) }.take(MAX)
}
