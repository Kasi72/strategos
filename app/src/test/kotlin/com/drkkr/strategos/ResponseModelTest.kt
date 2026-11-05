package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL2.3: how will they react, as a probability (noisy best reply). */
class ResponseModelTest {
    private val LTV = PayoffDimension.LONG_TERM_VALUE
    private val REL = PayoffDimension.RELATIONSHIPS
    private val COST = PayoffDimension.COST

    /** The manager's score for (move, reply): "better for them" is [x] on every dimension (COST inverted). */
    private fun them(s: Scenario, move: String, reply: String, x: Double): Scenario = s.copy(payoffs = s.payoffs.map {
        if (it.stakeholderId == "mgr" && it.actionProfile["you"] == move && it.actionProfile["mgr"] == reply)
            it.copy(payoff = MultiPayoff(mapOf(LTV to x, REL to x, COST to 10 - x))) else it
    })

    private fun you(s: Scenario, move: String, reply: String, x: Double): Scenario = s.copy(payoffs = s.payoffs.map {
        if (it.stakeholderId == "you" && it.actionProfile["you"] == move && it.actionProfile["mgr"] == reply)
            it.copy(payoff = MultiPayoff(mapOf(LTV to x, REL to x, COST to 10 - x))) else it
    })

    private fun withPriors(s: Scenario, softens: Double? = null, boundary: Double? = null, escalates: Double? = null) = s.copy(
        stakeholders = s.stakeholders.map { if (it.id == "mgr") it.copy(probSoftensOnRepair = softens, probRespectsBoundary = boundary, probEscalatesOnReactive = escalates) else it }
    )

    private fun p(s: Scenario, move: String = "a1", beta: Double = 1.0) = ResponseModel.receptiveProbability(s, "you", move, beta)

    @Test
    fun TC_HL_RESP_01_aVeryLargeBetaIsTodaysBestReply() {
        val s = them(them(Fx.hlRaise(), "a1", "r1", 8.0), "a1", "r2", 2.0)
        assertTrue(p(s, beta = 50.0)!! > 0.94)                                 // the clamp ceiling is 0.95
        assertTrue(p(them(them(Fx.hlRaise(), "a1", "r1", 2.0), "a1", "r2", 8.0), beta = 50.0)!! < 0.06)
    }

    @Test
    fun TC_HL_RESP_02_aLearnedPriorIsBlendedHalfAndHalf_aDefaultPriorIsNot() {
        val equal = them(them(Fx.hlRaise(), "a1", "r1", 5.0), "a1", "r2", 5.0)
        assertEquals(0.5, p(equal)!!, 1e-9)                                    // nothing learned: the default priors are not blended
        assertEquals(0.55, p(withPriors(equal, softens = 0.6))!!, 1e-9)        // (0.5 + 0.6) / 2
        assertEquals(0.5, p(withPriors(equal, boundary = 0.9, escalates = 0.9))!!, 1e-9)   // a repair move reads only its own prior
        // boundary and reactive moves read theirs; reactive is "1 - escalates"
        val b = them(them(equal, "a2", "r1", 5.0), "a2", "r2", 5.0)
        assertEquals(0.6, p(withPriors(b, boundary = 0.7), "a2")!!, 1e-9)       // (0.5 + 0.7) / 2
        val r = them(them(equal, "a3", "r1", 5.0), "a3", "r2", 5.0)
        assertEquals(0.35, p(withPriors(r, escalates = 0.8), "a3")!!, 1e-9)     // (0.5 + (1 - 0.8)) / 2
    }

    @Test
    fun TC_HL_RESP_03_theResultStaysInsideTheClamp() {
        val hi = them(them(Fx.hlRaise(), "a1", "r1", 10.0), "a1", "r2", 0.0)
        val lo = them(them(Fx.hlRaise(), "a1", "r1", 0.0), "a1", "r2", 10.0)
        for (beta in listOf(0.0, 1.0, 50.0, 1e6)) for (s in listOf(hi, lo, withPriors(hi, softens = 0.0), withPriors(lo, softens = 1.0))) {
            val x = p(s, beta = beta)!!
            assertTrue("p=$x beta=$beta", x in 0.05..0.95)
        }
        assertEquals(0.5, p(hi, beta = 0.0)!!, 1e-9)                           // beta 0 = pure noise
    }

    @Test
    fun TC_HL_RESP_04_betaFollowsHowNoisyTheyAre() {
        fun b(e: Double?) = ResponseModel.betaFrom(e?.let { OpponentModel("mgr", it, 0.5) })
        assertEquals(3.0, b(0.1), 0.0); assertEquals(1.5, b(0.3), 0.0); assertEquals(1.0, b(0.5), 0.0)
        assertEquals(0.5, b(0.8), 0.0); assertEquals(1.0, b(null), 0.0)
    }

    @Test
    fun TC_HL_RESP_06_nullWhenThereIsNoRecognisablePairOfReplies() {
        assertNull(ResponseModel.receptiveProbability(Fx.loanFamily(), "you", "cosign", 1.0))      // no posture tags on their options
        assertNull(ResponseModel.receptiveProbability(Fx.hlRaise(), "you", "nope", 1.0))
        assertNull(ResponseModel.receptiveProbability(Fx.hlRaise(), "mgr", "a1", 1.0))             // nobody else to react
    }

    @Test
    fun TC_HL_RESP_07_expectedValueWeighsTheOwnersTwoOutcomes() {
        var s = them(them(Fx.hlRaise(), "a1", "r1", 5.0), "a1", "r2", 5.0)       // p = 0.5
        s = you(you(s, "a1", "r1", 8.0), "a1", "r2", 2.0)
        val u1 = s.payoffs.single { it.stakeholderId == "you" && it.actionProfile["you"] == "a1" && it.actionProfile["mgr"] == "r1" }.payoff.weighted(s.weights)
        val u2 = s.payoffs.single { it.stakeholderId == "you" && it.actionProfile["you"] == "a1" && it.actionProfile["mgr"] == "r2" }.payoff.weighted(s.weights)
        assertEquals(0.5 * u1 + 0.5 * u2, ResponseModel.expectedValue(s, "you", "a1", 1.0)!!, 1e-9)
    }

    // ── in the directive ───────────────────────────────────────────────────────────

    private fun blockedTrigger(s: Scenario): String? {
        val d = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null)
        assertTrue(d.ready)
        return d.blockedOrDominatedMoves.firstOrNull { it.move == "Threaten to leave" }?.triggers
    }

    @Test
    fun TC_HL_RESP_08_aBlockedMoveNamesTheMoreLikelyReply_andSaysWhenItCouldGoEitherWay() {
        val tie = them(them(Fx.hlRaise(), "a3", "r1", 5.0), "a3", "r2", 5.0)
        assertTrue(blockedTrigger(tie)!!, blockedTrigger(tie)!!.endsWith("(could go either way)"))
        val clear = them(them(Fx.hlRaise(), "a3", "r1", 2.0), "a3", "r2", 9.0)
        val t = blockedTrigger(clear)!!
        assertTrue(t, t.contains("would most likely respond: Put it off"))
        assertFalse(t, t.contains("could go either way"))
    }

    @Test
    fun TC_HL_RESP_05_withoutAHumanContextTheDirectiveIsExactlyWhatItWasBeforeThisBranch() {
        val fixtures = listOf("loanFamily" to Fx.loanFamily(), "pd" to Fx.pd()) + Fx.templates().mapIndexed { i, s -> "template$i" to s }
        for ((name, s) in fixtures) {
            val expected = javaClass.getResource("/human/$name.json")!!.readText()
            val actual = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null).toJson().toString()
            assertEquals("directive for $name changed", expected, actual)
        }
    }
}
