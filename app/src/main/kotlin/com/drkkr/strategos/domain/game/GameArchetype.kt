package com.drkkr.strategos.domain.game

/**
 * Named game-theoretic archetypes recognised in the normal-form analysis.
 *
 * Detection is limited to 2-player 2×2 games (the most common in practice).
 * All other games return [GENERIC].
 */
enum class GameArchetype {
    /** Classic social-dilemma: mutual defection is dominant but Pareto-inferior. */
    PRISONERS_DILEMMA,
    /** Anti-coordination (Hawk-Dove): two pure NE at off-diagonal profiles. */
    CHICKEN,
    /** Assurance / Stag Hunt: two pure NE on diagonal, one Pareto-dominates the other. */
    STAG_HUNT,
    /** Coordination with conflicting preferences (players prefer different equilibria). */
    BATTLE_OF_SEXES,
    /** Pure conflict — payoffs sum to zero at every profile. */
    ZERO_SUM,
    /** Symmetric coordination — both players prefer the same equilibrium. */
    PURE_COORDINATION,
    /** Harmony — mutual cooperation is a dominant-strategy equilibrium. */
    HARMONY,
    /** No recognised pattern. */
    GENERIC
}

/**
 * Detects the [GameArchetype] of a compiled [GameIR].
 *
 * Only 2-player 2×2 normal-form games can be identified as a named archetype;
 * zero-sum games of any size return [GameArchetype.ZERO_SUM].
 * Everything else returns [GameArchetype.GENERIC].
 */
object GameArchetypeDetector {

    fun detect(ir: GameIR): GameArchetype {
        if (ir.gameClass == GameClass.ZERO_SUM_2P) return GameArchetype.ZERO_SUM
        if (ir.playerCount != 2) return GameArchetype.GENERIC
        if (ir.strategyCounts[0] != 2 || ir.strategyCounts[1] != 2) return GameArchetype.GENERIC
        return detect2x2(ir)
    }

    private fun detect2x2(ir: GameIR): GameArchetype {
        fun u1(i: Int, j: Int) = ir.utility[0][ir.profileIndex(intArrayOf(i, j))]
        fun u2(i: Int, j: Int) = ir.utility[1][ir.profileIndex(intArrayOf(i, j))]

        if (isPrisonersDilemma(::u1, ::u2)) return GameArchetype.PRISONERS_DILEMMA
        if (isHarmony(::u1, ::u2))           return GameArchetype.HARMONY

        val pureNEs = pure2x2NEs(ir)

        if (pureNEs.size == 2) {
            val profiles = pureNEs.map { it.pureProfile!! }
            val diagonal = profiles.any { it == listOf(0, 0) } && profiles.any { it == listOf(1, 1) }
            val antiDiag  = profiles.any { it == listOf(0, 1) } && profiles.any { it == listOf(1, 0) }

            if (diagonal) {
                return if (isBattleOfSexes(::u1, ::u2)) GameArchetype.BATTLE_OF_SEXES
                       else GameArchetype.STAG_HUNT
            }
            if (antiDiag) return GameArchetype.CHICKEN
        }

        if (pureNEs.size == 1) {
            val p = pureNEs.first().pureProfile!!
            // Single coordination NE where both players get same payoff
            if ((p == listOf(0, 0) || p == listOf(1, 1)) &&
                kotlin.math.abs(u1(p[0], p[1]) - u2(p[0], p[1])) < 1e-9
            ) return GameArchetype.PURE_COORDINATION
        }

        return GameArchetype.GENERIC
    }

    /** True when each player has a strictly dominant strategy whose NE is Pareto-dominated. */
    private fun isPrisonersDilemma(u1: (Int, Int) -> Double, u2: (Int, Int) -> Double): Boolean {
        for (d1 in 0..1) {
            val c1 = 1 - d1
            if (u1(d1, 0) <= u1(c1, 0) || u1(d1, 1) <= u1(c1, 1)) continue
            for (d2 in 0..1) {
                val c2 = 1 - d2
                if (u2(0, d2) <= u2(0, c2) || u2(1, d2) <= u2(1, c2)) continue
                if (u1(c1, c2) > u1(d1, d2) && u2(c1, c2) > u2(d1, d2)) return true
            }
        }
        return false
    }

    /** True when both players have a jointly dominant cooperative outcome. */
    private fun isHarmony(u1: (Int, Int) -> Double, u2: (Int, Int) -> Double): Boolean {
        for (d1 in 0..1) {
            val c1 = 1 - d1
            if (u1(d1, 0) <= u1(c1, 0) || u1(d1, 1) <= u1(c1, 1)) continue
            for (d2 in 0..1) {
                val c2 = 1 - d2
                if (u2(0, d2) <= u2(0, c2) || u2(1, d2) <= u2(1, c2)) continue
                if (u1(d1, d2) >= u1(c1, c2) && u2(d1, d2) >= u2(c1, c2)) return true
            }
        }
        return false
    }

    /** True when players prefer different diagonal NE (Battle of Sexes). */
    private fun isBattleOfSexes(u1: (Int, Int) -> Double, u2: (Int, Int) -> Double): Boolean {
        val p1Prefers00 = u1(0, 0) > u1(1, 1)
        val p2Prefers11 = u2(1, 1) > u2(0, 0)
        val p1Prefers11 = u1(1, 1) > u1(0, 0)
        val p2Prefers00 = u2(0, 0) > u2(1, 1)
        return (p1Prefers00 && p2Prefers11) || (p1Prefers11 && p2Prefers00)
    }

    private fun pure2x2NEs(ir: GameIR): List<EquilibriumProfile> {
        val result = mutableListOf<EquilibriumProfile>()
        for (i in 0..1) for (j in 0..1) {
            val profile = intArrayOf(i, j)
            if (isPureNash(ir, profile)) {
                result += EquilibriumProfile(
                    mixedStrategies = listOf(
                        doubleArrayOf(if (i == 0) 1.0 else 0.0, if (i == 1) 1.0 else 0.0),
                        doubleArrayOf(if (j == 0) 1.0 else 0.0, if (j == 1) 1.0 else 0.0)
                    ),
                    pureProfile = profile.toList()
                )
            }
        }
        return result
    }

    private fun isPureNash(ir: GameIR, profile: IntArray): Boolean {
        for (p in 0 until ir.playerCount) {
            val current = ir.utility[p][ir.profileIndex(profile)]
            for (alt in 0 until ir.strategyCounts[p]) {
                if (alt == profile[p]) continue
                val dev = profile.copyOf().also { it[p] = alt }
                if (ir.utility[p][ir.profileIndex(dev)] > current) return false
            }
        }
        return true
    }
}
