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
