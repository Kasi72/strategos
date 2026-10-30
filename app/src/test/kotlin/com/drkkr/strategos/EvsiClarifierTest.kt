package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.Clarification
import com.drkkr.strategos.domain.agent.EvsiClarifier
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies EvsiClarifier EVSI-lite behaviour.
 *
 * Uses Fx.loanFamily() (full payoffs) and stripped variants for missing-payoff cases.
 */
class EvsiClarifierTest {

    // ── null when all payoffs present ──────────────────────────────────────────

    @Test
    fun `clarify returns null when no payoffs are missing`() {
        val s = Fx.loanFamily()
        // loanFamily has full 3x3 payoffs — coverage should be complete.
        assertNull(EvsiClarifier.clarify(s))
    }

    // ── single missing profile ─────────────────────────────────────────────────

    @Test
    fun `clarify returns non-null for scenario with one missing profile`() {
        val s = oneMissing()
        assertNotNull(EvsiClarifier.clarify(s))
    }

    @Test
    fun `remainingCount equals total missing profiles`() {
        val s = twoMissing()
        val cov = PayoffCoverage.of(s)
        val c = EvsiClarifier.clarify(s)!!
        assertEquals(cov.missingProfiles.size, c.remainingCount)
    }

    @Test
    fun `clarify for single missing profile returns remainingCount of 1`() {
        val s = oneMissing()
        assertEquals(1, EvsiClarifier.clarify(s)!!.remainingCount)
    }

    // ── question content ───────────────────────────────────────────────────────

    @Test
    fun `question contains player names`() {
        val s = oneMissing()
        val q = EvsiClarifier.clarify(s)!!.question
        assertTrue("question must mention 'You'", q.contains("You"))
        assertTrue("question must mention 'Brother'", q.contains("Brother"))
    }

    @Test
    fun `question contains action labels`() {
        val s = oneMissing()
        val q = EvsiClarifier.clarify(s)!!.question
        // The only missing profile is cosign+accept; those labels must appear.
        assertTrue("question must contain action label", q.isNotBlank())
        assertTrue("question asks for ratings", q.contains("0–10") || q.contains("0-10") || q.contains("rate"))
    }

    @Test
    fun `profileSummary contains player name and action label`() {
        val s = oneMissing()
        val c = EvsiClarifier.clarify(s)!!
        assertTrue("profileSummary non-blank", c.profileSummary.isNotBlank())
    }

    // ── EVSI-lite ranking: highest-scoring profile returned ───────────────────

    @Test
    fun `top profile is from owner's highest-payoff action when multiple missing`() {
        // Remove all payoffs for "cosign" (You's highest-value action in loanFamily).
        // Remove all payoffs for "wait" (You's lowest-value action).
        // EVSI-lite should prefer "cosign" profiles (owner has high existing payoffs there).
        val base = Fx.loanFamily()
        val withoutCosign = base.copy(
            payoffs = base.payoffs.filter { it.actionProfile["you"] != "cosign" }
        )
        val c = EvsiClarifier.clarify(withoutCosign)
        assertNotNull(c)
        // profileSummary should reference "Co-sign" (the cosign action label)
        assertTrue(
            "Expected top missing profile to reference Co-sign, got: ${c!!.profileSummary}",
            c.profileSummary.contains("Co-sign")
        )
    }

    // ── EngineDirective serialises clarification_question ─────────────────────

    @Test
    fun `EngineDirective with clarification emits clarification_question in toJson`() {
        val clarification = Clarification(
            question = "What if You Co-signs and Brother Accepts?",
            profileSummary = "You: Co-sign / Brother: Accept",
            remainingCount = 1
        )
        val d = com.drkkr.strategos.domain.agent.EngineDirective(
            ready = false, recommendedMove = null, recommendedWhy = "",
            supportingFrameworks = emptyList(), safeFallbackMove = null, complementaryMove = null,
            blockedOrDominatedMoves = emptyList(), dominantDynamic = emptyList(), dynamicLabel = null,
            influencersInPlay = emptyList(), stressVerdict = "ROBUST", stressFlip = null,
            moveToImplement = null,
            clarification = clarification
        )
        val json = d.toJson()
        assertEquals(
            "What if You Co-signs and Brother Accepts?",
            json["clarification_question"]?.toString()?.trim('"')
        )
    }

    @Test
    fun `EngineDirective without clarification omits clarification_question from toJson`() {
        val d = com.drkkr.strategos.domain.agent.EngineDirective(
            ready = false, recommendedMove = null, recommendedWhy = "",
            supportingFrameworks = emptyList(), safeFallbackMove = null, complementaryMove = null,
            blockedOrDominatedMoves = emptyList(), dominantDynamic = emptyList(), dynamicLabel = null,
            influencersInPlay = emptyList(), stressVerdict = "ROBUST", stressFlip = null,
            moveToImplement = null,
            clarification = null
        )
        assertNull(d.toJson()["clarification_question"])
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** loanFamily with exactly the (cosign, accept) profile payoffs removed. */
    private fun oneMissing() = Fx.loanFamily().let { s ->
        s.copy(payoffs = s.payoffs.filter { p ->
            !(p.actionProfile["you"] == "cosign" && p.actionProfile["bro"] == "accept")
        })
    }

    /** loanFamily with (cosign, accept) and (cosign, refuse) profiles removed. */
    private fun twoMissing() = Fx.loanFamily().let { s ->
        s.copy(payoffs = s.payoffs.filter { p ->
            val you = p.actionProfile["you"]
            val bro = p.actionProfile["bro"]
            !((you == "cosign" && bro == "accept") || (you == "cosign" && bro == "refuse"))
        })
    }
}
