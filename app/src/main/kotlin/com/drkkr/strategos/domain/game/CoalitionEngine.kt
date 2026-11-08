package com.drkkr.strategos.domain.game

object CoalitionEngine {
    private const val MC_SAMPLES = 500
    private const val EXACT_LIMIT = 6
    private const val MC_LIMIT = 12

    data class ShapleyResult(
        val values: Map<String, Double>,
        val coreStable: Boolean,
        val summary: String,
        val approximate: Boolean = false
    )

    fun compute(ir: GameIR): ShapleyResult? {
        if (ir.playerCount < 3) return null
        val n = ir.playerCount
        if (n > MC_LIMIT) return null  // TooLarge — caller may surface this to coach

        return if (n <= EXACT_LIMIT) exactShapley(ir, n) else monteCarloShapley(ir, n)
    }

    private fun exactShapley(ir: GameIR, n: Int): ShapleyResult {
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
        return buildResult(ir, shapley, grandValue, coreStable, approximate = false)
    }

    private fun monteCarloShapley(ir: GameIR, n: Int): ShapleyResult {
        val rng = java.util.Random(42L)
        val shapley = DoubleArray(n)
        val perm = IntArray(n) { it }
        repeat(MC_SAMPLES) {
            // Fisher-Yates shuffle
            for (i in n - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
            }
            var mask = 0
            var prev = coalitionValue(ir, 0)
            for (k in 0 until n) {
                val p = perm[k]
                val next = coalitionValue(ir, mask or (1 shl p))
                shapley[p] += next - prev
                prev = next
                mask = mask or (1 shl p)
            }
        }
        for (i in 0 until n) shapley[i] /= MC_SAMPLES.toDouble()
        val grandValue = coalitionValue(ir, (1 shl n) - 1)
        // Core stability: approximate by sampling, not exact enumeration
        var coreStable = true
        for (mask in 1 until (1 shl n) - 1) {
            val cs = (0 until n).filter { (mask shr it) and 1 == 1 }.sumOf { shapley[it] }
            if (cs < coalitionValue(ir, mask) - 1e-9) { coreStable = false; break }
        }
        return buildResult(ir, shapley, grandValue, coreStable, approximate = true)
    }

    private fun buildResult(
        ir: GameIR,
        shapley: DoubleArray,
        grandValue: Double,
        coreStable: Boolean,
        approximate: Boolean
    ): ShapleyResult {
        val valuesMap = ir.playerIds.mapIndexed { i, id -> id to shapley[i] }.toMap()
        val topEntry = valuesMap.maxByOrNull { it.value }
        val topShare = if (grandValue > 1e-9 && topEntry != null)
            " (${(topEntry.value / grandValue * 100).toInt()}% of coalition value)" else ""
        val approxNote = if (approximate) " (MC estimate, ${MC_SAMPLES} samples)" else ""
        val summary = if (topEntry != null)
            "${topEntry.key} holds the largest Shapley share$topShare$approxNote. Grand coalition is ${if (coreStable) "stable" else "unstable"}."
        else "Coalition analysis complete$approxNote. Grand coalition is ${if (coreStable) "stable" else "unstable"}."
        return ShapleyResult(valuesMap, coreStable, summary, approximate)
    }

    /**
     * Alpha-characteristic function: max over coalition strategies of
     * (min over non-member strategies of total coalition utility).
     * This is the standard minimax characteristic function for cooperative game theory.
     * Using best-case (over all profiles) would inflate coalition values and produce
     * incorrect Shapley shares and core stability assessments.
     */
    private fun coalitionValue(ir: GameIR, coalitionMask: Int): Double {
        if (coalitionMask == 0) return 0.0
        val members = (0 until ir.playerCount).filter { (coalitionMask shr it) and 1 == 1 }
        val nonMembers = (0 until ir.playerCount).filter { (coalitionMask shr it) and 1 == 0 }

        val profile = IntArray(ir.playerCount)

        if (nonMembers.isEmpty()) {
            // Grand coalition: max total utility (no adversary to minimize)
            var best = Double.NEGATIVE_INFINITY
            val total = ir.strategyCounts.fold(1, Int::times)
            for (profileIdx in 0 until total) {
                var rem = profileIdx
                for (p in ir.strategyCounts.indices.reversed()) { profile[p] = rem % ir.strategyCounts[p]; rem /= ir.strategyCounts[p] }
                val util = members.sumOf { p -> ir.utility[p][ir.profileIndex(profile)] }
                if (util > best) best = util
            }
            return if (best == Double.NEGATIVE_INFINITY) 0.0 else best
        }

        val memberCounts = members.map { ir.strategyCounts[it] }
        val nonMemberCounts = nonMembers.map { ir.strategyCounts[it] }
        val memberTotal = memberCounts.fold(1, Int::times)
        val nonMemberTotal = nonMemberCounts.fold(1, Int::times)

        var maxMinUtil = Double.NEGATIVE_INFINITY
        for (mIdx in 0 until memberTotal) {
            var rem = mIdx
            for (i in memberCounts.indices.reversed()) { profile[members[i]] = rem % memberCounts[i]; rem /= memberCounts[i] }
            var minUtil = Double.POSITIVE_INFINITY
            for (nIdx in 0 until nonMemberTotal) {
                var nrem = nIdx
                for (i in nonMemberCounts.indices.reversed()) { profile[nonMembers[i]] = nrem % nonMemberCounts[i]; nrem /= nonMemberCounts[i] }
                val util = members.sumOf { p -> ir.utility[p][ir.profileIndex(profile)] }
                if (util < minUtil) minUtil = util
            }
            if (minUtil > maxMinUtil) maxMinUtil = minUtil
        }
        return if (maxMinUtil == Double.NEGATIVE_INFINITY || maxMinUtil == Double.POSITIVE_INFINITY) 0.0 else maxMinUtil
    }
}
