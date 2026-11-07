package com.drkkr.strategos.engine

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterised engine integration test across all normal-form scenarios.
 *
 * Each scenario in ScenarioRegistry.ALL contributes 9 test cases:
 *   1. archetype detected
 *   2. pure NE solver result (Solved vs Incomplete)
 *   3. pure NE count
 *   4. pure NE profiles match
 *   5. all pure NE pass EquilibriumVerifier
 *   6. IEDS strict anyEliminated flag
 *   7. IEDS strict rounds
 *   8. IEDS strict surviving strategies
 *   9. mixed NE probabilities (or absent when null)
 *
 * Corrections, lessons, and payoff-ordering invariants live in ScenarioRegistry.
 * To add a new normal-form scenario: add an entry to ScenarioRegistry.ALL.
 * Sequential (extensive-form) scenarios: see CdrcFundingUltimatumTest.
 */
@RunWith(Parameterized::class)
class EngineScenarioTest(private val spec: GameScenarioSpec) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<GameScenarioSpec> = ScenarioRegistry.ALL
    }

    private var tol: Double = 0.0

    @Before
    fun setup() {
        tol = EquilibriumVerifier.adaptiveTolerance(spec.ir.utility)
    }

    // ── Spec self-validation ────────────────────────────────────────────────

    @Before
    fun validateSpec() {
        // PD: T > R > P > S for player 0 (symmetric games)
        if (spec.expected.archetype == GameArchetype.PRISONERS_DILEMMA) {
            val u = spec.ir.utility[0]
            val r = spec.ir.strategyCounts
            val t = u[spec.ir.profileIndex(intArrayOf(1, 0))]   // defect while other cooperates
            val reward = u[spec.ir.profileIndex(intArrayOf(0, 0))]
            val p = u[spec.ir.profileIndex(intArrayOf(1, 1))]
            val s = u[spec.ir.profileIndex(intArrayOf(0, 1))]
            assertTrue("PD requires T>R: spec ${spec.id}", t > reward)
            assertTrue("PD requires R>P: spec ${spec.id}", reward > p)
            assertTrue("PD requires P>S: spec ${spec.id}", p > s)
        }
        // Chicken: catastrophic mutual defection must be worst outcome
        if (spec.expected.archetype == GameArchetype.CHICKEN &&
            spec.ir.strategyCounts == listOf(2, 2)) {
            val u = spec.ir.utility[0]
            val mutual = u[spec.ir.profileIndex(intArrayOf(0, 0))]
            val yield  = u[spec.ir.profileIndex(intArrayOf(1, 1))]
            assertTrue("Chicken: mutual aggression < mutual yield in spec ${spec.id}",
                mutual < yield)
        }
    }

    // ── 1. Archetype ────────────────────────────────────────────────────────

    @Test
    fun `archetype detected`() {
        assertEquals(
            "Archetype mismatch for ${spec.id}",
            spec.expected.archetype,
            GameArchetypeDetector.detect(spec.ir),
        )
    }

    // ── 2. Pure NE solver result ────────────────────────────────────────────

    @Test
    fun `pure NE solver result`() {
        val outcome = PureNashSolver.solve(spec.ir, SolverLimits.ON_DEVICE)
        if (spec.expected.pureNE.isEmpty()) {
            assertFalse("No pure NE expected for ${spec.id} but solver returned Solved",
                outcome is SolverOutcome.Solved)
        } else {
            assertTrue("Pure NE expected for ${spec.id} but solver did not return Solved",
                outcome is SolverOutcome.Solved)
        }
    }

    // ── 3. Pure NE count ────────────────────────────────────────────────────

    @Test
    fun `pure NE count`() {
        if (spec.expected.pureNE.isEmpty()) return
        val profiles = pureProfiles()
        assertEquals("Pure NE count for ${spec.id}", spec.expected.pureNE.size, profiles.size)
    }

    // ── 4. Pure NE profiles match ───────────────────────────────────────────

    @Test
    fun `pure NE profiles match`() {
        if (spec.expected.pureNE.isEmpty()) return
        val profiles = pureProfiles()
        for (expected in spec.expected.pureNE) {
            assertTrue("NE profile $expected missing in ${spec.id}",
                profiles.any { it == expected })
        }
    }

    // ── 5. All pure NE pass EquilibriumVerifier ─────────────────────────────

    @Test
    fun `all pure NE pass verifier`() {
        for (ne in spec.expected.pureNE) {
            val mixed = ne.mapIndexed { playerIdx, actionIdx ->
                DoubleArray(spec.ir.strategyCounts[playerIdx]) { if (it == actionIdx) 1.0 else 0.0 }
            }
            assertTrue("NE $ne fails EquilibriumVerifier in ${spec.id}",
                EquilibriumVerifier.isNashEquilibrium(
                    spec.ir.utility, spec.ir.strategyCounts, mixed, tol))
        }
    }

    // ── 6. IEDS strict anyEliminated flag ───────────────────────────────────

    @Test
    fun `IEDS strict anyEliminated`() {
        val result = IteratedDominancePruner.prune(spec.ir, weakDominance = false)
        assertEquals("IEDS anyEliminated for ${spec.id}",
            spec.expected.iedsStrictRounds > 0,
            result.anyEliminated)
    }

    // ── 7. IEDS strict rounds ───────────────────────────────────────────────

    @Test
    fun `IEDS strict rounds`() {
        val result = IteratedDominancePruner.prune(spec.ir, weakDominance = false)
        if (spec.expected.iedsStrictRounds == 0) {
            assertEquals("IEDS rounds for ${spec.id}", 0, result.rounds)
        } else {
            assertTrue("IEDS rounds for ${spec.id} must be >= ${spec.expected.iedsStrictRounds}",
                result.rounds >= spec.expected.iedsStrictRounds)
        }
    }

    // ── 8. IEDS strict surviving strategies ─────────────────────────────────

    @Test
    fun `IEDS strict surviving strategies`() {
        val result = IteratedDominancePruner.prune(spec.ir, weakDominance = false)
        assertEquals("IEDS surviving strategyCounts for ${spec.id}",
            spec.expected.iedsStrictSurviving,
            result.ir.strategyCounts)
    }

    // ── 9. Mixed NE probabilities ───────────────────────────────────────────

    @Test
    fun `mixed NE probabilities`() {
        val expected = spec.expected.mixedNE
        val outcome  = MixedNashNxMSolver.solve(spec.ir)
        assertTrue("MixedNashNxMSolver must return Solved for ${spec.id}",
            outcome is SolverOutcome.Solved)
        val equilibria = (outcome as SolverOutcome.Solved).equilibria

        if (expected == null) {
            val interior = equilibria.firstOrNull { eq ->
                eq.pureProfile == null &&
                eq.mixedStrategies.all { p -> p.any { it > 0.01 && it < 0.99 } }
            }
            assertNull("No interior mixed NE expected for ${spec.id} but found one", interior)
            return
        }

        val interior = equilibria.firstOrNull { eq ->
            eq.pureProfile == null &&
            eq.mixedStrategies.all { p -> p.any { it > 0.01 && it < 0.99 } }
        }
        assertNotNull("Interior mixed NE must exist for ${spec.id}", interior)

        for ((playerIdx, expectedProbs) in expected.probs.withIndex()) {
            val actual = interior!!.mixedStrategies[playerIdx]
            for ((actionIdx, ep) in expectedProbs.withIndex()) {
                assertEquals(
                    "${spec.id} P${playerIdx}_A${actionIdx}",
                    ep, actual[actionIdx], expected.tol,
                )
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun pureProfiles(): List<List<Int>> {
        val outcome = PureNashSolver.solve(spec.ir, SolverLimits.ON_DEVICE)
        return if (outcome is SolverOutcome.Solved)
            outcome.equilibria.mapNotNull { it.pureProfile }
        else
            emptyList()
    }
}

// ── Coverage meta-test (non-parameterised) ──────────────────────────────────

class ScenarioRegistryCoverageTest {

    @Test
    fun `registry covers all required archetypes`() {
        val archetypes = ScenarioRegistry.ALL.map { it.expected.archetype }.toSet()
        val corrections = ScenarioRegistry.ALL.flatMap { it.meta.corrections }

        println("""
            ════════════════════════════════════════════════════════
             ScenarioRegistry Coverage Report
            ════════════════════════════════════════════════════════
             Total scenarios : ${ScenarioRegistry.ALL.size}
             Archetypes      : ${archetypes.map { it.name }.sorted().joinToString(", ")}
             Corrections     : ${corrections.size}
            ${if (corrections.isNotEmpty()) " Corrected specs:" else ""}
            ${corrections.joinToString("\n") { "  • ${it.what}" }}
            ════════════════════════════════════════════════════════
        """.trimIndent())

        assertTrue("Must have PRISONERS_DILEMMA scenario",
            GameArchetype.PRISONERS_DILEMMA in archetypes)
        assertTrue("Must have CHICKEN scenario",
            GameArchetype.CHICKEN in archetypes)
        assertTrue("Must have HARMONY scenario",
            GameArchetype.HARMONY in archetypes)
        assertTrue("Must have ZERO_SUM scenario",
            GameArchetype.ZERO_SUM in archetypes)
        assertTrue("Must have at least one scenario with corrections",
            corrections.isNotEmpty())
    }
}
