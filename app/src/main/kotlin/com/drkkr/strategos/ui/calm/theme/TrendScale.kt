package com.drkkr.strategos.ui.calm.theme

/**
 * The vertical window both the home-card spark and the project's momentum chart draw in, so the same history reads the same in
 * both places. It follows the data but is never tighter than [MIN_SPAN] points, so a few points of movement stays small instead
 * of being stretched to fill the chart, and trust and tension share one scale so their lines can't appear to cross by accident.
 */
object TrendScale {
    private const val MIN_SPAN = 40f

    /** The (low, high) values to draw between, inside 0..100. */
    fun window(vararg series: List<Float>): Pair<Float, Float> {
        val all = series.flatMap { it }
        if (all.isEmpty()) return 0f to 100f
        val lo = all.min(); val hi = all.max()
        val span = maxOf(hi - lo, MIN_SPAN)
        val start = ((lo + hi) / 2 - span / 2).coerceIn(0f, 100f - span)
        return start to start + span
    }
}
