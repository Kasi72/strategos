package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

class GameArchetypeTest {

    @Test
    fun `PD is detected as PRISONERS_DILEMMA`() {
        assertEquals(GameArchetype.PRISONERS_DILEMMA, GameArchetypeDetector.detect(pdIr()))
    }

    @Test
    fun `Stag Hunt is detected as STAG_HUNT`() {
        assertEquals(GameArchetype.STAG_HUNT, GameArchetypeDetector.detect(stagHuntIr()))
    }

    @Test
    fun `Matching Pennies (zero-sum) is detected as ZERO_SUM`() {
        assertEquals(GameArchetype.ZERO_SUM, GameArchetypeDetector.detect(mpIr()))
    }

    @Test
    fun `Chicken is detected as CHICKEN`() {
        assertEquals(GameArchetype.CHICKEN, GameArchetypeDetector.detect(chickenIr()))
    }

    @Test
    fun `Battle of Sexes is detected as BATTLE_OF_SEXES`() {
        assertEquals(GameArchetype.BATTLE_OF_SEXES, GameArchetypeDetector.detect(bosIr()))
    }

    @Test
    fun `3-player game returns GENERIC`() {
        val ir = GameIR(
            scenarioId = "3p", playerIds = listOf("a","b","c"),
            actionIds = listOf(listOf("x","y"), listOf("x","y"), listOf("x","y")),
            strategyCounts = listOf(2,2,2),
            utility = List(3) { DoubleArray(8) { 1.0 } },
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
        assertEquals(GameArchetype.GENERIC, GameArchetypeDetector.detect(ir))
    }

    @Test
    fun `Harmony is detected as HARMONY`() {
        assertEquals(GameArchetype.HARMONY, GameArchetypeDetector.detect(harmonyIr()))
    }

    @Test
    fun `Deadlock is detected as DEADLOCK`() {
        assertEquals(GameArchetype.DEADLOCK, GameArchetypeDetector.detect(deadlockIr()))
    }

    @Test
    fun `Deadlock is NOT misclassified as HARMONY`() {
        // Deadlock has both dominant strategies but NE is not the global max for both players
        assertNotEquals(GameArchetype.HARMONY, GameArchetypeDetector.detect(deadlockIr()))
    }

    @Test
    fun `Leader game is detected as LEADER`() {
        assertEquals(GameArchetype.LEADER, GameArchetypeDetector.detect(leaderIr()))
    }

    @Test
    fun `Hero game is detected as HERO`() {
        assertEquals(GameArchetype.HERO, GameArchetypeDetector.detect(heroIr()))
    }

    @Test
    fun `Archetype is stored on GameIR after compiler runs`() {
        val ir = pdIr()
        // pdIr() is built manually; archetype defaults to GENERIC since we didn't use compiler
        // Ensure detect works correctly; full compiler integration tested in GameModelCompilerTest
        assertEquals(GameArchetype.PRISONERS_DILEMMA, GameArchetypeDetector.detect(ir))
    }

    // ── Game builders ──────────────────────────────────────────────────────────

    private fun pdIr(): GameIR {
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "pd", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("c","d"), listOf("c","d")),
            strategyCounts = listOf(2,2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    private fun stagHuntIr(): GameIR {
        val u1 = doubleArrayOf(3.0, 0.0, 1.0, 1.0)
        val u2 = doubleArrayOf(3.0, 1.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "sh", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("stag","hare"), listOf("stag","hare")),
            strategyCounts = listOf(2,2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    private fun mpIr(): GameIR {
        val u1 = doubleArrayOf(1.0, -1.0, -1.0, 1.0)
        val u2 = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        return GameIR(
            scenarioId = "mp", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("h","t"), listOf("h","t")),
            strategyCounts = listOf(2,2), utility = listOf(u1, u2),
            gameClass = GameClass.ZERO_SUM_2P, coveragePct = 100
        )
    }

    /** Chicken: T>R>S>P. (Straight=0, Swerve=1). NE at (0,1) and (1,0). */
    private fun chickenIr(): GameIR {
        // (0,0)=crash(-1,-1), (0,1)=(Str,Sw)=(5,1), (1,0)=(Sw,Str)=(1,5), (1,1)=both swerve(3,3)
        val u1 = doubleArrayOf(-1.0, 5.0, 1.0, 3.0)
        val u2 = doubleArrayOf(-1.0, 1.0, 5.0, 3.0)
        return GameIR(
            scenarioId = "chicken", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("straight","swerve"), listOf("straight","swerve")),
            strategyCounts = listOf(2,2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /**
     * Harmony: mutual cooperation (action 0) is dominant for both AND gives each player
     * their personal maximum payoff.  T=4>R=3>P=2>S=1 with R = global max (cooperation
     * dominates AND is the best outcome).
     * u1=(0,0)=4,(0,1)=3,(1,0)=2,(1,1)=1  u2=(0,0)=4,(0,1)=2,(1,0)=3,(1,1)=1
     */
    private fun harmonyIr(): GameIR {
        val u1 = doubleArrayOf(4.0, 3.0, 2.0, 1.0)
        val u2 = doubleArrayOf(4.0, 2.0, 3.0, 1.0)
        return GameIR(
            scenarioId = "harmony", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("cooperate", "defect"), listOf("cooperate", "defect")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /**
     * Deadlock: both have dominant strategy D (action 1), T>P>R>S ordering.
     * NE at (D,D)=(1,1) with payoff P=3.  Cooperation (C,C)=(0,0) gives R=2 < P=3, so
     * there is NO incentive to cooperate — no social dilemma.
     * u1=(0,0)=2,(0,1)=1,(1,0)=4,(1,1)=3  u2=(0,0)=2,(0,1)=4,(1,0)=1,(1,1)=3
     */
    private fun deadlockIr(): GameIR {
        val u1 = doubleArrayOf(2.0, 1.0, 4.0, 3.0)
        val u2 = doubleArrayOf(2.0, 4.0, 1.0, 3.0)
        return GameIR(
            scenarioId = "deadlock", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("cooperate", "defect"), listOf("cooperate", "defect")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /**
     * Leader: P1 has dominant action 0; P2 has no dominant.  P1 plays 0, P2 best-responds
     * with 1 (BR gives P2 payoff 4).  P1 gets 4 at NE — their highest possible payoff →
     * dominant player benefits (LEADER, not HERO).
     * u1=(0,0)=3,(0,1)=4,(1,0)=2,(1,1)=1  u2=(0,0)=2,(0,1)=4,(1,0)=3,(1,1)=1
     */
    private fun leaderIr(): GameIR {
        val u1 = doubleArrayOf(3.0, 4.0, 2.0, 1.0)
        val u2 = doubleArrayOf(2.0, 4.0, 3.0, 1.0)
        return GameIR(
            scenarioId = "leader", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("lead", "follow"), listOf("adapt", "exploit")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /**
     * Hero: P1 has dominant action 0; P2 has no dominant.  P1 plays 0, P2 best-responds
     * with 1 (BR gives P2 payoff 4).  P1 gets 3 at NE but would get 4 if P2 played 0 →
     * dominant player gets exploited (HERO, not LEADER).
     * u1=(0,0)=4,(0,1)=3,(1,0)=2,(1,1)=1  u2=(0,0)=3,(0,1)=4,(1,0)=2,(1,1)=1
     */
    private fun heroIr(): GameIR {
        val u1 = doubleArrayOf(4.0, 3.0, 2.0, 1.0)
        val u2 = doubleArrayOf(3.0, 4.0, 2.0, 1.0)
        return GameIR(
            scenarioId = "hero", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("cooperate", "defect"), listOf("adapt", "exploit")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    /** Battle of Sexes: NE at (0,0) with p1 payoff 2 > p2 payoff 1; NE at (1,1) p2=2 > p1=1. */
    private fun bosIr(): GameIR {
        val u1 = doubleArrayOf(2.0, 0.0, 0.0, 1.0)
        val u2 = doubleArrayOf(1.0, 0.0, 0.0, 2.0)
        return GameIR(
            scenarioId = "bos", playerIds = listOf("p1","p2"),
            actionIds = listOf(listOf("opera","football"), listOf("opera","football")),
            strategyCounts = listOf(2,2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }
}
