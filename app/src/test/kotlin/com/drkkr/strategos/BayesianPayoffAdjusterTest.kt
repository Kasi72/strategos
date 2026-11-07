package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import com.drkkr.strategos.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers two bugs fixed in BayesianPayoffAdjuster and OpponentModelBuilder.
 *
 * Bug 1: Multiple same-tagged opponent actions each claimed full probability mass
 *         instead of splitting it. With 2 RECEPTIVE actions, each got pReceptive
 *         instead of pReceptive/2, causing scale > MAX_SCALE and over-weighting.
 *
 * Bug 2: OpponentModelBuilder returned null when probSoftensOnRepair was null
 *         even if other priors were learned, inconsistent with BayesianPayoffAdjuster
 *         which only skips when ALL three are null.
 */
class BayesianPayoffAdjusterTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Build a minimal 2-player scenario with posture-tagged actions. */
    private fun scenario(
        userActions: List<Pair<String, String?>>,  // id to posture tag
        oppActions: List<Pair<String, String?>>,
        pSoftens: Double? = null,
        pBoundary: Double? = null,
        pEscalates: Double? = null
    ): Scenario {
        val userId = "user"
        val oppId  = "opp"
        val userStakeholder = Stakeholder(id = userId, name = "User", gameRole = GameRole.PLAYER)
        val oppStakeholder  = Stakeholder(
            id = oppId, name = "Opp", gameRole = GameRole.PLAYER,
            probSoftensOnRepair    = pSoftens,
            probRespectsBoundary   = pBoundary,
            probEscalatesOnReactive = pEscalates
        )
        val actions = userActions.map { (id, posture) ->
            Action(
                id = id, stakeholderId = userId, label = id,
                description = if (posture != null) "[posture:$posture]" else ""
            )
        } + oppActions.map { (id, posture) ->
            Action(
                id = id, stakeholderId = oppId, label = id,
                description = if (posture != null) "[posture:$posture]" else ""
            )
        }
        return Scenario(
            title = "test",
            stakeholders = listOf(userStakeholder, oppStakeholder),
            actions = actions
        )
    }

    /** Build a GameIR with uniform payoffs of 5.0, player order matches scenario. */
    private fun uniformIr(
        userActionIds: List<String>,
        oppActionIds: List<String>
    ): GameIR {
        val nUser = userActionIds.size
        val nOpp  = oppActionIds.size
        val total = nUser * nOpp
        return GameIR(
            scenarioId = "t",
            playerIds  = listOf("user", "opp"),
            actionIds  = listOf(userActionIds, oppActionIds),
            strategyCounts = listOf(nUser, nOpp),
            utility = listOf(
                DoubleArray(total) { 5.0 },
                DoubleArray(total) { 5.0 }
            ),
            gameClass  = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )
    }

    // ── Bug 1: probability normalisation ──────────────────────────────────────

    /**
     * With 1 RECEPTIVE action: scale = pReceptive / (1/nOpp) = pReceptive * nOpp.
     * With 2 RECEPTIVE actions each should get pReceptive/2 so scale is halved.
     * Both must stay within [MIN_SCALE=0.5, MAX_SCALE=2.0].
     */
    @Test
    fun `two RECEPTIVE opp actions each receive half probability — scale bounded`() {
        // User plays REPAIR (pReceptive = pSoftens = 0.9)
        // Opp has 2 RECEPTIVE actions + 0 DEFENSIVE
        // Before fix: each RECEPTIVE got 0.9, scale = 0.9/(1/2) = 1.8
        //   Two slots × 1.8 = total mass 3.6, more than 1 — wrong
        // After fix: each RECEPTIVE gets 0.9/2 = 0.45, scale = 0.45/(1/2) = 0.9
        val scen = scenario(
            userActions = listOf("u_repair" to "repair"),
            oppActions  = listOf("o_recv1" to "receptive", "o_recv2" to "receptive"),
            pSoftens    = 0.9
        )
        val ir  = uniformIr(listOf("u_repair"), listOf("o_recv1", "o_recv2"))
        val adj = BayesianPayoffAdjuster.adjust(ir, scen)

        // Both adjusted utilities should be the same (symmetric split)
        val u00 = adj.utility[0][adj.profileIndex(intArrayOf(0, 0))]  // u_repair vs o_recv1
        val u01 = adj.utility[0][adj.profileIndex(intArrayOf(0, 1))]  // u_repair vs o_recv2
        assertEquals("Both RECEPTIVE actions get equal share", u00, u01, 1e-9)

        // scale = (0.9/2) / (1/2) = 0.9 → adjusted util = 5.0 * 0.9 = 4.5
        assertEquals(4.5, u00, 1e-9)
    }

    @Test
    fun `single RECEPTIVE opp action gets full probability mass`() {
        // pReceptive = pSoftens = 0.8, nOpp = 1 → scale = 0.8/(1/1) = 0.8 → util = 5.0 * 0.8 = 4.0
        val scen = scenario(
            userActions = listOf("u_repair" to "repair"),
            oppActions  = listOf("o_recv" to "receptive"),
            pSoftens    = 0.8
        )
        val ir  = uniformIr(listOf("u_repair"), listOf("o_recv"))
        val adj = BayesianPayoffAdjuster.adjust(ir, scen)
        val u = adj.utility[0][adj.profileIndex(intArrayOf(0, 0))]
        assertEquals(4.0, u, 1e-9)
    }

    @Test
    fun `probability mass across all opp actions sums near 1 for REPAIR user action`() {
        // 1 RECEPTIVE + 1 DEFENSIVE + 1 untagged, pSoftens=0.7
        // pReceptive=0.7, pDefensive=0.3, untagged=1/3
        // sum = 0.7/1 + 0.3/1 + 1/3 = 1.0 + 1/3 ≠ 1 (untagged = baseline, not complementary)
        // but with 2 RECEPTIVE: 0.7/2 + 0.7/2 + 0.3/1 = 0.35+0.35+0.30 = 1.0 ✓
        val scen = scenario(
            userActions = listOf("u_repair" to "repair"),
            oppActions  = listOf("o_recv1" to "receptive", "o_recv2" to "receptive", "o_def" to "defensive"),
            pSoftens    = 0.7
        )
        val ir  = uniformIr(listOf("u_repair"), listOf("o_recv1", "o_recv2", "o_def"))
        val adj = BayesianPayoffAdjuster.adjust(ir, scen)

        // Compute implied probabilities from scale * uniform
        val nOpp    = 3
        val uniform = 1.0 / nOpp
        // scale = adjustedUtil / 5.0 (since base = 5.0)
        val p0 = (adj.utility[0][adj.profileIndex(intArrayOf(0, 0))] / 5.0) * uniform
        val p1 = (adj.utility[0][adj.profileIndex(intArrayOf(0, 1))] / 5.0) * uniform
        val p2 = (adj.utility[0][adj.profileIndex(intArrayOf(0, 2))] / 5.0) * uniform
        assertEquals(1.0, p0 + p1 + p2, 1e-9)
    }

    @Test
    fun `no priors — adjust is no-op`() {
        val scen = scenario(
            userActions = listOf("u" to "repair"),
            oppActions  = listOf("o1" to "receptive", "o2" to "defensive"),
            pSoftens    = null, pBoundary = null, pEscalates = null
        )
        val ir  = uniformIr(listOf("u"), listOf("o1", "o2"))
        val adj = BayesianPayoffAdjuster.adjust(ir, scen)
        // No priors → should return original ir unchanged
        assertArrayEquals(ir.utility[0], adj.utility[0], 1e-9)
    }

    // ── Bug 2: OpponentModelBuilder null guard ─────────────────────────────────

    @Test
    fun `fromScenario returns non-null when only probRespectsBoundary is learned`() {
        val scen = scenario(
            userActions = listOf("u" to null),
            oppActions  = listOf("o" to null),
            pSoftens    = null,
            pBoundary   = 0.75,   // only this prior learned
            pEscalates  = null
        )
        val model = OpponentModelBuilder.fromScenario(scen)
        assertNotNull("Should produce model when any prior is known", model)
    }

    @Test
    fun `fromScenario returns non-null when only probEscalatesOnReactive is learned`() {
        val scen = scenario(
            userActions = listOf("u" to null),
            oppActions  = listOf("o" to null),
            pSoftens    = null,
            pBoundary   = null,
            pEscalates  = 0.80
        )
        val model = OpponentModelBuilder.fromScenario(scen)
        assertNotNull("Should produce model when any prior is known", model)
    }

    @Test
    fun `fromScenario returns non-null when only probSoftensOnRepair is learned`() {
        val scen = scenario(
            userActions = listOf("u" to null),
            oppActions  = listOf("o" to null),
            pSoftens    = 0.70,
            pBoundary   = null,
            pEscalates  = null
        )
        val model = OpponentModelBuilder.fromScenario(scen)
        assertNotNull("Should produce model when any prior is known", model)
    }

    @Test
    fun `fromScenario returns null when ALL priors are null`() {
        val scen = scenario(
            userActions = listOf("u" to null),
            oppActions  = listOf("o" to null),
            pSoftens    = null, pBoundary = null, pEscalates = null
        )
        val model = OpponentModelBuilder.fromScenario(scen)
        assertNull("No priors → no model", model)
    }

    @Test
    fun `fromScenario uses NEUTRAL for unlearned priors in epsilon calculation`() {
        // Only pBoundary = 0.75 learned; pSoftens and pEscalates default to NEUTRAL=0.5
        // irrationality = ((1-0.5) + 0.5 + (1-0.75)) / 3 = (0.5 + 0.5 + 0.25) / 3 = 1.25/3
        // epsilon = (1.25/3) * 0.5 = 0.2083
        val expectedEpsilon = (1.25 / 3.0) * 0.5
        val scen = scenario(
            userActions = listOf("u" to null),
            oppActions  = listOf("o" to null),
            pSoftens    = null, pBoundary = 0.75, pEscalates = null
        )
        val model = OpponentModelBuilder.fromScenario(scen)!!
        assertEquals(expectedEpsilon, model.epsilon, 1e-9)
        assertTrue(model.discountFactor in 0.0..1.0)
    }
}
