package com.drkkr.strategos.domain.game

/**
 * Iterated Elimination of Strictly Dominated Strategies (IEDS).
 *
 * Repeatedly removes any action that is strictly dominated (another action
 * yields strictly higher payoff for all opponent strategy combinations) until
 * no dominated action remains. Order of elimination does not affect the result
 * for strict dominance.
 *
 * The returned [PruneResult] contains the reduced [GameIR] and a log of which
 * actions were eliminated from each player.
 *
 * No android imports; pure Kotlin.
 */
object IteratedDominancePruner {

    data class PruneResult(
        val ir: GameIR,
        /** playerId → list of eliminated action ids in elimination order. */
        val eliminated: Map<String, List<String>>,
        val rounds: Int
    ) {
        val anyEliminated: Boolean get() = eliminated.values.any { it.isNotEmpty() }
    }

    /**
     * @param weakDominance if true, also eliminate weakly dominated strategies
     *   (dominator ≥ dominated for all opponent profiles, strict for at least one).
     *   Weak dominance elimination is order-dependent; use with care.
     */
    fun prune(ir: GameIR, weakDominance: Boolean = false): PruneResult {
        var current = ir
        val eliminated: MutableMap<String, MutableList<String>> =
            ir.playerIds.associateWith { mutableListOf<String>() }.toMutableMap()
        var rounds = 0

        while (true) {
            val step = eliminateOneRound(current, weakDominance) ?: break
            val (reduced, elim) = step
            elim.forEach { (pid, aids) -> eliminated.getOrPut(pid) { mutableListOf() } += aids }
            current = reduced
            rounds++
        }

        return PruneResult(current, eliminated, rounds)
    }

    // Returns null when nothing was eliminated this round.
    private fun eliminateOneRound(ir: GameIR, weakDominance: Boolean): Pair<GameIR, Map<String, List<String>>>? {
        val toEliminate = mutableMapOf<Int, List<Int>>()

        for (p in 0 until ir.playerCount) {
            val dominated = findDominated(ir, p, weakDominance)
            if (dominated.isNotEmpty()) toEliminate[p] = dominated
        }
        if (toEliminate.isEmpty()) return null
        // Guard: if elimination would leave any player with 0 strategies, stop IEDS here.
        if (toEliminate.any { (p, elim) -> ir.strategyCounts[p] - elim.size < 1 }) return null
        return buildReducedIR(ir, toEliminate)
    }

    /** Returns indices of dominated actions for [player]. */
    private fun findDominated(ir: GameIR, player: Int, weak: Boolean): List<Int> {
        val count = ir.strategyCounts[player]
        val dominated = mutableListOf<Int>()
        for (a in 0 until count) {
            if (dominated.contains(a)) continue
            for (b in 0 until count) {
                if (a == b || dominated.contains(b)) continue
                if (dominates(ir, player, dominator = b, dominated = a, weak = weak)) {
                    dominated += a
                    break
                }
            }
        }
        return dominated
    }

    /**
     * True when [dominator] (weakly or strictly) dominates [dominated] for [player].
     * Strict: dominator > dominated for every opponent profile.
     * Weak: dominator ≥ dominated for every opponent profile, strict for at least one.
     */
    private fun dominates(
        ir: GameIR,
        player: Int,
        dominator: Int,
        dominated: Int,
        weak: Boolean
    ): Boolean {
        val profile = IntArray(ir.playerCount)
        var strictOnce = false
        val allOk = opponentProfilesAll(ir, player, profile) { opponentProfile ->
            opponentProfile[player] = dominator
            val iD = ir.profileIndex(opponentProfile)
            opponentProfile[player] = dominated
            val id = ir.profileIndex(opponentProfile)
            // A filled-in cell is not evidence: no dominance claim rests on one.
            if (ir.isProxy(player, iD) || ir.isProxy(player, id)) return@opponentProfilesAll false
            val payD = ir.utility[player][iD]
            val payd = ir.utility[player][id]
            if (weak) {
                if (payD > payd) strictOnce = true
                payD >= payd
            } else {
                payD > payd
            }
        }
        return allOk && (!weak || strictOnce)
    }

    /**
     * Iterates all pure-strategy profiles for opponents of [player] and tests
     * [predicate] on each. Returns true iff [predicate] holds for ALL profiles.
     */
    private fun opponentProfilesAll(
        ir: GameIR,
        player: Int,
        profile: IntArray,
        predicate: (IntArray) -> Boolean
    ): Boolean {
        fun rec(p: Int): Boolean {
            if (p == ir.playerCount) return predicate(profile)
            if (p == player) return rec(p + 1)
            for (a in 0 until ir.strategyCounts[p]) {
                profile[p] = a
                if (!rec(p + 1)) return false
            }
            return true
        }
        return rec(0)
    }

    private fun buildReducedIR(
        ir: GameIR,
        eliminate: Map<Int, List<Int>>
    ): Pair<GameIR, Map<String, List<String>>> {
        val surviving = (0 until ir.playerCount).map { p ->
            val elim = eliminate[p] ?: emptyList()
            (0 until ir.strategyCounts[p]).filter { it !in elim }
        }

        val newStrategyCounts = surviving.map { it.size }
        val newProfileCount   = newStrategyCounts.fold(1, Int::times)
        val newUtility        = Array(ir.playerCount) { DoubleArray(newProfileCount) }
        val newProxy          = Array(ir.playerCount) { BooleanArray(newProfileCount) }
        val newProfile        = IntArray(ir.playerCount)

        fun fill(p: Int, oldProfile: IntArray) {
            if (p == ir.playerCount) {
                val newIdx = profileIndex(newProfile, newStrategyCounts)
                val oldIdx = ir.profileIndex(oldProfile)
                for (pl in 0 until ir.playerCount) { newUtility[pl][newIdx] = ir.utility[pl][oldIdx]; newProxy[pl][newIdx] = ir.isProxy(pl, oldIdx) }
                return
            }
            surviving[p].forEachIndexed { newA, oldA ->
                newProfile[p] = newA
                oldProfile[p] = oldA
                fill(p + 1, oldProfile)
            }
        }
        fill(0, IntArray(ir.playerCount))

        val newActionIds = (0 until ir.playerCount).map { p -> surviving[p].map { ir.actionIds[p][it] } }

        val eliminatedNames: Map<String, List<String>> = eliminate.entries.associate { (pi, elim) ->
            ir.playerIds[pi] to elim.map { ir.actionIds[pi][it] }
        }

        val newIr = ir.copy(
            actionIds = newActionIds,
            strategyCounts = newStrategyCounts,
            utility = newUtility.map { it.copyOf() },
            proxy = if (ir.proxy.isEmpty()) emptyList() else newProxy.toList()
        )
        return newIr to eliminatedNames
    }

    private fun profileIndex(profile: IntArray, strategyCounts: List<Int>): Int {
        var idx = 0; var stride = 1
        for (p in strategyCounts.indices.reversed()) {
            idx += profile[p] * stride; stride *= strategyCounts[p]
        }
        return idx
    }
}
