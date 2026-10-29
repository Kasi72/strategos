package com.drkkr.strategos.domain.analysis

/**
 * The one soft word shown on a project card and in the Overview header ("improving | steady | tense").
 * Pure Kotlin so it stays testable and android-free (invariant: nothing under domain/ touches android.*).
 * No numbers ever leak out of here; the scores themselves live only in the X-ray.
 */
enum class MomentumStatus(val word: String) {
    IMPROVING("improving"),
    STEADY("steady"),
    TENSE("tense");

    companion object {
        /** How much net change per step counts as more than noise before the word moves off "steady". */
        const val THRESHOLD = 0.5

        /**
         * Net health = how fast trust is rising minus how fast tension is rising.
         * Trust up and tension easing reads as improving; the reverse reads as tense; a flat line is steady.
         */
        fun from(trustSlope: Double, tensionSlope: Double): MomentumStatus {
            val net = trustSlope.finiteOr(0.0) - tensionSlope.finiteOr(0.0)
            return when {
                net > THRESHOLD -> IMPROVING
                net < -THRESHOLD -> TENSE
                else -> STEADY
            }
        }

        /** Convenience over two recent series (oldest first), as the trajectory points come out of the DB. */
        fun of(trustSeries: List<Double>, tensionSeries: List<Double>): MomentumStatus =
            from(slope(trustSeries), slope(tensionSeries))

        /**
         * Least-squares slope per step over a short recent series (x = 0..n-1).
         * Returns 0 for fewer than two points or a degenerate series, so a brand-new project reads as steady.
         */
        fun slope(series: List<Double>): Double {
            val ys = series.map { it.finiteOr(50.0) }
            val n = ys.size
            if (n < 2) return 0.0
            val meanX = (n - 1) / 2.0
            val meanY = ys.average()
            var num = 0.0
            var den = 0.0
            for (i in ys.indices) {
                val dx = i - meanX
                num += dx * (ys[i] - meanY)
                den += dx * dx
            }
            return if (den == 0.0) 0.0 else num / den
        }

        private fun Double.finiteOr(d: Double) = if (isNaN() || isInfinite()) d else this
    }
}
