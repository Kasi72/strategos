package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import com.drkkr.strategos.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * PROPOSAL for c5d4ca3 Issue 2 (partial payoff tables). With a 0.0 fill, an option missing one cell on the weighted 0–10 scale
 * looks strictly dominated and is pruned. Proposed: fill with the player's own mean, and never claim dominance through a filled cell.
 */
class ProxyFillProposalTest {
    private val unit = mapOf(PayoffDimension.LONG_TERM_VALUE to 1.0)
    private fun pay(sid: String, you: String, them: String, v: Double) =
        PayoffEntry(sid, mapOf("you" to you, "them" to them), MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v)))

    /**
     * You: Talk (7, ?), Push (8, 6). Their payoffs are complete. Talk's cell against Refuse is the only gap.
     * With 0.0 there, Push "strictly dominates" Talk (8 > 7, 6 > 0) and Talk is pruned, on a number nobody gave.
     */
    private fun gapGame(talkRefuse: Double?): Scenario = Scenario(
        id = "gap", title = "Gap",
        stakeholders = listOf(Stakeholder("you", "You"), Stakeholder("them", "Them")),
        actions = listOf(Action("talk", "you", "Talk"), Action("push", "you", "Push"), Action("agree", "them", "Agree"), Action("refuse", "them", "Refuse")),
        payoffs = listOfNotNull(
            pay("you", "talk", "agree", 7.0), talkRefuse?.let { pay("you", "talk", "refuse", it) },
            pay("you", "push", "agree", 8.0), pay("you", "push", "refuse", 6.0),
            pay("them", "talk", "agree", 8.0), pay("them", "talk", "refuse", 4.0),
            pay("them", "push", "agree", 5.0), pay("them", "push", "refuse", 6.0)
        ),
        weights = unit
    )

    private fun ir(s: Scenario) = (GameModelCompiler.compile(s, weights = unit) as CompileResult.Success).ir

    @Test fun TC_ID_RUB_05_aMissingCellIsFilledWithThePlayersOwnMeanAndMarked() {
        val g = ir(gapGame(talkRefuse = null))
        assertTrue(g.coveragePct < 100)
        val cell = g.profileIndex(intArrayOf(0, 1))   // Talk / Refuse
        assertEquals((7.0 + 8.0 + 6.0) / 3, g.utility[0][cell], 1e-9)
        assertTrue(g.isProxy(0, cell))
        assertFalse(g.isProxy(1, cell))
    }

    @Test fun TC_ID_RUB_06_anOptionWithAGapIsNotPrunedThroughTheFilledCell() {
        val pr = IteratedDominancePruner.prune(ir(gapGame(talkRefuse = null)))
        assertFalse("Talk must not be pruned on a comparison with a filled cell: ${pr.eliminated}", "talk" in pr.eliminated["you"].orEmpty())
    }

    @Test fun TC_ID_RUB_06_aRealDominanceOnACompleteTableStays() {
        // with a real 5 there, Push (8, 6) truly dominates Talk (7, 5)
        val full = ir(gapGame(talkRefuse = 5.0))
        assertEquals(100, full.coveragePct)
        assertTrue(full.proxy.isEmpty())
        assertTrue("talk" in IteratedDominancePruner.prune(full).eliminated["you"].orEmpty())
    }

    @Test fun TC_ID_RUB_05_aPlayerWithNoKnownCellGetsNoDominanceClaimsAndAnEmptyTableFails() {
        val s = gapGame(talkRefuse = null).let { it.copy(payoffs = it.payoffs.filter { p -> p.stakeholderId != "you" }) }
        val g = ir(s)
        assertTrue((0 until g.profileCount).all { g.isProxy(0, it) })
        assertTrue(IteratedDominancePruner.prune(g).eliminated["you"].orEmpty().isEmpty())
        assertTrue(GameModelCompiler.compile(s.copy(payoffs = emptyList()), weights = unit) is CompileResult.Failure)
    }
}
