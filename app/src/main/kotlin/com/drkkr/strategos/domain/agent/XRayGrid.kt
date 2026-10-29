package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/** One of your moves in the X-ray grid: its score if he takes it well, if badly, and the blend at the current odds. */
data class XRayRow(val label: String, val well: Int, val bad: Int, val expected: Int)

/**
 * The X-ray's 3x2 grid (board 07): your moves down, his two likely reactions across, a 0..100 score per
 * cell, and the expected score per row at the chosen odds. [robust] means the best row's worst cell is 50+.
 */
data class XRayGrid(
    val rows: List<XRayRow>,
    val bestIndex: Int,
    val robust: Boolean,
    val wellLabel: String,
    val badLabel: String,
    val odds: Double
) {
    val best: XRayRow get() = rows[bestIndex]
}

/**
 * Builds the grid from the saved scenario's own payoffs: no model call, no new ranking engine.
 * "Takes it well" is the counterpart reply that is best for you on average, "badly" the worst; scores are
 * your weighted payoffs scaled to 0..100 across the whole scenario. Null means not enough input yet
 * (use [XRayNotEnough]).
 */
object XRayGridBuilder {
    const val MAX_ROWS = 3
    const val ROBUST_FLOOR = 50

    /**
     * [pinnedLabel] is the engine's own pick: when given, that row is always shown and carries the ring, so the
     * sheet can never contradict the chat. The caller passes it only while the odds are the coach's; once the
     * user drags a slider the ring follows the grid's own best row.
     */
    fun build(s: Scenario, odds: Double, pinnedLabel: String? = null): XRayGrid? {
        val me = IntakeChecklist.self(s) ?: s.players().firstOrNull() ?: return null
        val him = XRayPriors.counterpart(s) ?: return null
        val mine = s.actionsFor(me.id)
        val his = s.actionsFor(him.id)
        if (mine.isEmpty() || his.size < 2) return null

        fun raw(myId: String, hisId: String): Double? =
            s.payoffs.firstOrNull { it.stakeholderId == me.id && it.actionProfile[me.id] == myId && it.actionProfile[him.id] == hisId }
                ?.payoff?.values?.entries?.sumOf { (dim, v) -> v * (s.weights[dim] ?: 1.0) }

        val cells = mine.associate { a -> a.id to his.associate { b -> b.id to raw(a.id, b.id) } }
        val all = cells.values.flatMap { it.values }.filterNotNull()
        if (all.isEmpty()) return null
        val lo = all.min(); val hi = all.max()
        fun score(v: Double) = if (hi == lo) 50 else (((v - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)

        // His replies ranked by how good they are for you, averaged over your moves that have a value.
        val avg = his.associate { b -> b.id to mine.mapNotNull { cells.getValue(it.id)[b.id] }.average() }
        val usable = his.filter { !avg.getValue(it.id).isNaN() }
        if (usable.size < 2) return null
        val wellAction = usable.maxByOrNull { avg.getValue(it.id) }!!
        val badAction = usable.filter { it.id != wellAction.id }.minByOrNull { avg.getValue(it.id) }!!

        val p = odds.coerceIn(0.0, 1.0)
        val ranked = mine.mapNotNull { a ->
            val w = cells.getValue(a.id)[wellAction.id] ?: return@mapNotNull null
            val b = cells.getValue(a.id)[badAction.id] ?: return@mapNotNull null
            val ws = score(w); val bs = score(b)
            XRayRow(a.label, ws, bs, Math.round(p * ws + (1 - p) * bs).toInt())
        }.sortedByDescending { it.expected }
        if (ranked.isEmpty()) return null
        var rows = ranked.take(MAX_ROWS)
        if (pinnedLabel != null && rows.none { it.label.equals(pinnedLabel, true) }) {
            ranked.firstOrNull { it.label.equals(pinnedLabel, true) }?.let { rows = rows.take(MAX_ROWS - 1) + it }
        }
        val best = pinnedLabel?.let { l -> rows.indexOfFirst { it.label.equals(l, true) } }?.takeIf { it >= 0 } ?: 0
        return XRayGrid(rows, best, rows[best].let { minOf(it.well, it.bad) >= ROBUST_FLOOR }, wellAction.label, badAction.label, p)
    }
}
