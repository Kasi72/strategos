package com.drkkr.strategos.domain.game

object CoalitionEngine {
    data class ShapleyResult(
        val values: Map<String, Double>,
        val coreStable: Boolean,
        val summary: String
    )

    fun compute(ir: GameIR): ShapleyResult? {
        if (ir.playerCount < 3) return null
        val n = ir.playerCount
        if (n > 8) return null
        val charFn = HashMap<Int, Double>(1 shl n)
        for (mask in 0 until (1 shl n)) { charFn[mask] = coalitionValue(ir, mask) }
        val factorial = LongArray(n + 1).also { f ->
            f[0] = 1L
            for (i in 1..n) f[i] = f[i - 1] * i
        }
        val shapley = DoubleArray(n)
        for (i in 0 until n) {
            val others = (0 until n).filter { it != i }
            var value = 0.0
            for (subMask in 0 until (1 shl (n - 1))) {
                val sMask = others.foldIndexed(0) { idx, acc, p ->
                    if ((subMask shr idx) and 1 == 1) acc or (1 shl p) else acc
                }
                val sSize = Integer.bitCount(sMask)
                val weight = factorial[sSize].toDouble() * factorial[n - sSize - 1].toDouble() / factorial[n].toDouble()
                val marginal = (charFn[sMask or (1 shl i)] ?: 0.0) - (charFn[sMask] ?: 0.0)
                value += weight * marginal
            }
            shapley[i] = value
        }
        val grandValue = charFn[(1 shl n) - 1] ?: 0.0
        var coreStable = true
        for (mask in 1 until (1 shl n) - 1) {
            val coalitionShapley = (0 until n).filter { (mask shr it) and 1 == 1 }.sumOf { shapley[it] }
            if (coalitionShapley < (charFn[mask] ?: 0.0) - 1e-9) { coreStable = false; break }
        }
        val ids = ir.playerIds
        val valuesMap = ids.mapIndexed { i, id -> id to shapley[i] }.toMap()
        val topEntry = valuesMap.maxByOrNull { it.value }
        val topShare = if (grandValue > 1e-9 && topEntry != null)
            " (${(topEntry.value / grandValue * 100).toInt()}% of coalition value)" else ""
        val summary = if (topEntry != null)
            "${topEntry.key} holds the largest Shapley share$topShare. Grand coalition is ${if (coreStable) "stable" else "unstable"}."
        else "Coalition analysis complete. Grand coalition is ${if (coreStable) "stable" else "unstable"}."
        return ShapleyResult(valuesMap, coreStable, summary)
    }

    private fun coalitionValue(ir: GameIR, coalitionMask: Int): Double {
        if (coalitionMask == 0) return 0.0
        val members = (0 until ir.playerCount).filter { (coalitionMask shr it) and 1 == 1 }
        var best = Double.NEGATIVE_INFINITY
        val counts = ir.strategyCounts
        val profile = IntArray(ir.playerCount)
        val total = counts.fold(1, Int::times)
        for (profileIdx in 0 until total) {
            var rem = profileIdx
            for (p in counts.indices.reversed()) { profile[p] = rem % counts[p]; rem /= counts[p] }
            val coalitionUtil = members.sumOf { p -> ir.utility[p][ir.profileIndex(profile)] }
            if (coalitionUtil > best) best = coalitionUtil
        }
        return if (best == Double.NEGATIVE_INFINITY) 0.0 else best
    }
}
