package com.drkkr.strategos.domain.game

/**
 * Named game-theoretic archetypes recognised in normal-form analysis.
 *
 * Detection covers all 2-player 2×2 games comprehensively (Rapoport-Guyer / Robinson-Goforth
 * taxonomy), plus zero-sum games of any 2-player size.  All other games return [GENERIC].
 */
enum class GameArchetype {
    /** Both dominant strategies; NE Pareto-dominated by mutual cooperation — classic social dilemma. */
    PRISONERS_DILEMMA,
    /** Anti-coordination (Hawk-Dove / Snowdrift): two pure NE at off-diagonal profiles. */
    CHICKEN,
    /** Assurance game: two diagonal NE, one Pareto-dominates (players share the same preference). */
    STAG_HUNT,
    /** Coordination with conflicting preferences — players prefer opposite diagonal equilibria. */
    BATTLE_OF_SEXES,
    /** Pure conflict — payoffs sum to zero at every profile. */
    ZERO_SUM,
    /** Single diagonal NE; no conflicting preferences — obvious focal point. */
    PURE_COORDINATION,
    /** Mutual cooperation is the dominant-strategy joint optimum for both players. */
    HARMONY,
    /** Both players have dominant strategies; NE is NOT Pareto-inferior — no cooperation dilemma. */
    DEADLOCK,
    /** One player has a dominant strategy that leads to their preferred outcome; other player adapts. */
    LEADER,
    /** One player's dominant strategy yields to exploitation by the other (selfless cooperation). */
    HERO,
    /** No recognised pattern. */
    GENERIC
}

/**
 * Detects the [GameArchetype] of a compiled [GameIR].
 *
 * Coverage:
 * - Any 2-player zero-sum game → [GameArchetype.ZERO_SUM].
 * - 2-player 2×2 non-zero-sum games → full ordinal taxonomy (all 12 Rapoport-Guyer families
 *   collapsed into the named archetypes above).
 * - Everything else → [GameArchetype.GENERIC].
 */
object GameArchetypeDetector {

    fun detect(ir: GameIR): GameArchetype {
        if (ir.gameClass == GameClass.ZERO_SUM_2P) return GameArchetype.ZERO_SUM
        if (ir.playerCount != 2) return GameArchetype.GENERIC
        if (ir.strategyCounts[0] == 2 && ir.strategyCounts[1] == 2) return detect2x2(ir)
        return detectNxM(ir)
    }

    /**
     * Pattern detection for 2-player n×m games (n or m > 2).
     * Checks structural signatures that generalise beyond 2×2:
     * - PD-like: both players have a dominant strategy and the NE is Pareto-dominated.
     * - Coordination: every pure NE lies on the main diagonal.
     * Zero-sum already handled before this point.
     */
    private fun detectNxM(ir: GameIR): GameArchetype {
        val m = ir.strategyCounts[0]; val n = ir.strategyCounts[1]

        // Check for a dominant strategy for each player over all opponent profiles.
        fun dominantIndex(player: Int): Int? {
            val count = ir.strategyCounts[player]
            outer@ for (d in 0 until count) {
                for (alt in 0 until count) {
                    if (alt == d) continue
                    // d must beat alt for every opponent pure-strategy profile.
                    val profile = IntArray(2)
                    val oppCount = ir.strategyCounts[1 - player]
                    for (opp in 0 until oppCount) {
                        profile[1 - player] = opp
                        profile[player] = d
                        val payD = ir.utility[player][ir.profileIndex(profile)]
                        profile[player] = alt
                        val payAlt = ir.utility[player][ir.profileIndex(profile)]
                        if (payD <= payAlt) continue@outer
                    }
                    return d  // d strictly dominates alt for all opponent profiles
                }
            }
            return null
        }

        val dom0 = dominantIndex(0)
        val dom1 = dominantIndex(1)

        // PD-like: both have dominant strategies AND the NE is Pareto-dominated
        if (dom0 != null && dom1 != null) {
            val neProfile = intArrayOf(dom0, dom1)
            val neU0 = ir.utility[0][ir.profileIndex(neProfile)]
            val neU1 = ir.utility[1][ir.profileIndex(neProfile)]
            // Check if any other profile Pareto-dominates the NE
            val pardDominated = (0 until m).any { i ->
                (0 until n).any { j ->
                    if (i == dom0 && j == dom1) false
                    else {
                        val p = intArrayOf(i, j)
                        ir.utility[0][ir.profileIndex(p)] > neU0 &&
                        ir.utility[1][ir.profileIndex(p)] > neU1
                    }
                }
            }
            if (pardDominated) return GameArchetype.PRISONERS_DILEMMA
        }

        // Coordination: all pure NEs are on the main diagonal (i == j)
        val pureNEs = mutableListOf<IntArray>()
        for (i in 0 until m) for (j in 0 until n) {
            val profile = intArrayOf(i, j)
            if (isPureNash(ir, profile)) pureNEs += profile
        }
        if (pureNEs.isNotEmpty() && pureNEs.all { it[0] == it[1] }) {
            return GameArchetype.PURE_COORDINATION
        }

        return GameArchetype.GENERIC
    }

    private fun detect2x2(ir: GameIR): GameArchetype {
        fun u1(i: Int, j: Int) = ir.utility[0][ir.profileIndex(intArrayOf(i, j))]
        fun u2(i: Int, j: Int) = ir.utility[1][ir.profileIndex(intArrayOf(i, j))]

        // Dominant-strategy index for each player (-1 = none).
        // For P1: u(action, opponentAction) = u1(action, opponentAction)
        // For P2: u(action, opponentAction) = u2(opponentAction, action) — columns are P2's actions
        val dom1 = dominantAction { a, o -> u1(a, o) }
        val dom2 = dominantAction { a, o -> u2(o, a) }

        // ── Both players have dominant strategies ────────────────────────────
        if (dom1 >= 0 && dom2 >= 0) {
            if (isPrisonersDilemma(::u1, ::u2)) return GameArchetype.PRISONERS_DILEMMA
            if (isHarmony(::u1, ::u2))           return GameArchetype.HARMONY
            return GameArchetype.DEADLOCK          // both dominant, NE not Pareto-inferior
        }

        // ── Exactly one player has a dominant strategy ───────────────────────
        if (dom1 >= 0) return detectHeroLeader(domAction = dom1, p1Dominant = true,  ::u1, ::u2)
        if (dom2 >= 0) return detectHeroLeader(domAction = dom2, p1Dominant = false, ::u1, ::u2)

        // ── No dominant strategies — classify by pure-NE structure ───────────
        val pureNEs = pure2x2NEs(ir)

        if (pureNEs.size == 2) {
            val profiles = pureNEs.map { it.pureProfile!! }
            val diagonal = profiles.any { it == listOf(0, 0) } && profiles.any { it == listOf(1, 1) }
            val antiDiag = profiles.any { it == listOf(0, 1) } && profiles.any { it == listOf(1, 0) }
            if (diagonal) return when {
                isBattleOfSexes(::u1, ::u2) -> GameArchetype.BATTLE_OF_SEXES
                isStagHunt(::u1, ::u2)      -> GameArchetype.STAG_HUNT
                else                         -> GameArchetype.GENERIC  // equal diagonal payoffs — no preference alignment or conflict
            }
            if (antiDiag) return GameArchetype.CHICKEN
        }

        if (pureNEs.size == 1) {
            val p = pureNEs.first().pureProfile!!
            // Single diagonal NE with no dominant strategies = pure coordination focal point.
            if (p == listOf(0, 0) || p == listOf(1, 1)) return GameArchetype.PURE_COORDINATION
        }

        return GameArchetype.GENERIC
    }

    /**
     * Returns the strictly dominant action index (0 or 1) for a player in a 2×2 game, or -1.
     * [u] takes (playerAction, opponentAction) and returns the player's payoff.
     */
    private fun dominantAction(u: (Int, Int) -> Double): Int {
        for (d in 0..1) {
            val alt = 1 - d
            if (u(d, 0) > u(alt, 0) && u(d, 1) > u(alt, 1)) return d
        }
        return -1
    }

    /**
     * Classifies one-dominant-strategy games.
     *
     * HERO  — dominant player gets a WORSE payoff at the NE than they would if the other player
     *          had chosen differently; they are exploited by their own dominance.
     * LEADER — dominant player lands their best available payoff; they benefit from committing.
     */
    private fun detectHeroLeader(
        domAction: Int, p1Dominant: Boolean,
        u1: (Int, Int) -> Double, u2: (Int, Int) -> Double
    ): GameArchetype {
        val nePayoff: Double
        val altPayoff: Double          // dominant player's payoff if opponent played differently

        if (p1Dominant) {
            val br2 = if (u2(domAction, 0) >= u2(domAction, 1)) 0 else 1
            nePayoff  = u1(domAction, br2)
            altPayoff = u1(domAction, 1 - br2)
        } else {
            val br1 = if (u1(0, domAction) >= u1(1, domAction)) 0 else 1
            nePayoff  = u2(br1, domAction)
            altPayoff = u2(1 - br1, domAction)
        }
        return if (altPayoff > nePayoff) GameArchetype.HERO else GameArchetype.LEADER
    }

    /** True when both players have strictly dominant strategies and the NE is Pareto-dominated. */
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

    /**
     * True when both players' dominant strategies lead to the globally optimal joint outcome
     * (NE gives each player their personal maximum over all profiles).
     *
     * This correctly distinguishes Harmony from Deadlock: in Deadlock the NE is not the
     * global maximum for at least one player even though it dominates the cooperative profile.
     */
    private fun isHarmony(u1: (Int, Int) -> Double, u2: (Int, Int) -> Double): Boolean {
        for (d1 in 0..1) {
            val c1 = 1 - d1
            if (u1(d1, 0) <= u1(c1, 0) || u1(d1, 1) <= u1(c1, 1)) continue
            for (d2 in 0..1) {
                val c2 = 1 - d2
                if (u2(0, d2) <= u2(0, c2) || u2(1, d2) <= u2(1, c2)) continue
                val u1Max = maxOf(u1(0, 0), u1(0, 1), u1(1, 0), u1(1, 1))
                val u2Max = maxOf(u2(0, 0), u2(0, 1), u2(1, 0), u2(1, 1))
                if (u1(d1, d2) >= u1Max - 1e-9 && u2(d1, d2) >= u2Max - 1e-9) return true
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

    /** True when both players strictly prefer the same diagonal NE (Stag Hunt). */
    private fun isStagHunt(u1: (Int, Int) -> Double, u2: (Int, Int) -> Double): Boolean {
        val bothPrefer00 = u1(0, 0) > u1(1, 1) && u2(0, 0) > u2(1, 1)
        val bothPrefer11 = u1(1, 1) > u1(0, 0) && u2(1, 1) > u2(0, 0)
        return bothPrefer00 || bothPrefer11
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
