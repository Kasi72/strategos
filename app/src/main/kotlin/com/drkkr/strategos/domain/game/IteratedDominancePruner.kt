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

    fun prune(ir: GameIR): PruneResult {
        var current = ir
        val eliminated: MutableMap<String, MutableList<String>> =
            ir.playerIds.associateWith { mutableListOf<String>() }.toMutableMap()
        var rounds = 0

        while (true) {
            val step = eliminateOneRound(current) ?: break
            val (reduced, elim) = step
            elim.forEach { (pid, aids) -> eliminated.getOrPut(pid) { mutableListOf() } += aids }
            current = reduced
            rounds++
        }

        return PruneResult(current, eliminated, rounds)
    }

    // Returns null when nothing was eliminated this round.
    private fun eliminateOneRound(ir: GameIR): Pair<GameIR, Map<String, List<String>>>? {
        val toEliminate = mutableMapOf<Int, List<Int>>()

        for (p in 0 until ir.playerCount) {
            val dominated = strictlyDominated(ir, p)
            if (dominated.isNotEmpty()) toEliminate[p] = dominated
        }
        if (toEliminate.isEmpty()) return null
        return buildReducedIR(ir, toEliminate)
    }

    /** Returns indices of strictly dominated actions for [player]. */
    private fun strictlyDominated(ir: GameIR, player: Int): List<Int> {
        val count = ir.strategyCounts[player]
        val dominated = mutableListOf<Int>()
        for (a in 0 until count) {
            if (dominated.contains(a)) continue
            for (b in 0 until count) {
                if (a == b || dominated.contains(b)) continue
                if (strictlyDominates(ir, player, dominator = b, dominated = a)) {
                    dominated += a
                    break
                }
            }
        }
        return dominated
    }

    /**
     * True when [dominator] strictly dominates [dominated] for [player]:
     * for every pure-strategy combination of opponents, [dominator] gives
     * strictly higher payoff than [dominated].
     */
    private fun strictlyDominates(
        ir: GameIR,
        player: Int,
        dominator: Int,
        dominated: Int
    ): Boolean {
        val profile = IntArray(ir.playerCount)
        return opponentProfilesAll(ir, player, profile) { opponentProfile ->
            opponentProfile[player] = dominator
            val payD = ir.utility[player][ir.profileIndex(opponentProfile)]
            opponentProfile[player] = dominated
            val payd = ir.utility[player][ir.profileIndex(opponentProfile)]
            payD > payd
        }
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
        val newProfile        = IntArray(ir.playerCount)

        fun fill(p: Int, oldProfile: IntArray) {
            if (p == ir.playerCount) {
                val newIdx = profileIndex(newProfile, newStrategyCounts)
                val oldIdx = ir.profileIndex(oldProfile)
                for (pl in 0 until ir.playerCount) newUtility[pl][newIdx] = ir.utility[pl][oldIdx]
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

        val eliminatedNames: Map<String, List<String>> = eliminate.mapKeys { (pi, _) -> ir.playerIds[pi] }
            .mapValues { (pi, elim) -> elim.map { ir.actionIds[ir.playerIds.indexOf(pi)][it] } }

        val newIr = ir.copy(
            actionIds = newActionIds,
            strategyCounts = newStrategyCounts,
            utility = newUtility.map { it }
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
