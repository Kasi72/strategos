package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.IntentDetail
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.intent.TurnChain
import com.drkkr.strategos.domain.intent.WhatIfHandler
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID3.1: what-if runs on a copy, through the turn's own steps, and says what shifted. */
class WhatIfIntentTest {
    private val main = """"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Hold at the asking price","posture":"boundary"},{"who":"You","do":"Ask what budget she has","posture":"repair"},
        {"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Hold at the asking price","Agree"],"for_you":["++","+","++"],"for_them":["-","0","0"]},
        {"when":["Hold at the asking price","Walk away"],"for_you":["-","-","-"],"for_them":["0","-","0"]},
        {"when":["Ask what budget she has","Agree"],"for_you":["+","+","+"],"for_them":["0","+","0"]},
        {"when":["Ask what budget she has","Walk away"],"for_you":["0","0","0"],"for_them":["0","0","0"]}]"""

    /** The same keys as the main sketch, plus a tempting low offer that crosses the 100k walk-away. */
    private val lowOffer = """{"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Accept 90k","posture":"repair"},{"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Accept 90k","Agree"],"for_you":["++","++","++"],"for_them":["++","++","++"]},
        {"when":["Accept 90k","Walk away"],"for_you":["++","++","++"],"for_them":["0","0","0"]}]}"""

    private val frozen = """{"limits":["the budget is frozen"],
      "outcomes":[{"when":["Hold at the asking price","Agree"],"for_you":["-","-","-"],"for_them":["--","-","-"]},
        {"when":["Hold at the asking price","Walk away"],"for_you":["--","--","--"],"for_them":["0","-","0"]},
        {"when":["Ask what budget she has","Agree"],"for_you":["+","++","+"],"for_them":["+","+","0"]},
        {"when":["Ask what budget she has","Walk away"],"for_you":["0","+","0"],"for_them":["0","0","0"]}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}]}"""

    private fun saved(): Scenario {
        val s0 = Scenario(id = "w", title = "Sale", decisionOwner = "You", negotiationContext = NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"))
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse("{$main}")!!, s0).ops, s0)
        val v = ChangeSetValidator.validate(s0, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s0, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun input(s: Scenario, whatIf: String, change: String): RouteInput {
        val deal = DealFrame.build(s.negotiationContext, s.humanContext, "")
        val sides = FallbackShift.Sides(null, null)
        val prepare = TurnChain.prepare(deal, sides)
        val raw = EngineDirectiveBuilder.analysisView(s, null, null)
        val ops = SketchConverter.toOps(SketchParser.parse(whatIf)!!, s).ops
        return RouteInput(Intent.WHAT_IF, prepare(raw), ops, IntentDetail(change = change), base = raw, prepare = prepare,
            decide = TurnChain.decide(deal, null), deal = deal)
    }

    @Test fun TC_ID_WIF_01_theHypotheticalRunsOnACopy() = runBlocking {
        val s = saved()
        val before = s.copy()
        val r = IntentRouter().route(input(s, frozen, "the budget is frozen"))
        assertTrue(r is IntentResult.WhatIf)
        assertEquals("the saved scenario is unchanged", before, s)
        assertTrue(s.constraints.none { it.label == "the budget is frozen" })
    }

    @Test fun TC_ID_WIF_02_theBlockSaysWhatShiftedInAFixedLine() = runBlocking {
        val s = saved()
        val b = (IntentRouter().route(input(s, frozen, "the budget is frozen")) as IntentResult.WhatIf).block
        assertEquals("the budget is frozen", b.ifText)
        assertNotNull(b.pickNow); assertNotNull(b.pickThen)
        assertEquals(b.pickNow != b.pickThen, b.changed)
        assertEquals(WhatIfHandler.shifted(b.pickNow, b.pickThen), b.whatShifted)
        assertEquals(setOf("if", "pick_now", "pick_then", "changed", "what_shifted"), b.toJson().keys)
        // in the directive
        val d = EngineDirectiveBuilder.build(s, com.drkkr.strategos.domain.analysis.AnalysisPipeline().run(s), null).copy(whatIf = b).toJson()
        assertEquals(b.toJson(), d["what_if"])
    }

    @Test fun TC_ID_WIF_03_anOptionPastTheWalkAwayIsExcludedInTheVariantToo() = runBlocking {
        val s = saved()
        val b = (IntentRouter().route(input(s, lowOffer, "she offers 90k")) as IntentResult.WhatIf).block
        assertNotEquals("Accept 90k", b.pickThen)
        assertTrue(b.pickThen.orEmpty(), b.pickThen?.contains("90") != true)
        // control: without the walk-away steps, the same hypothetical WOULD pick it, so the exclusion is the walk-away's doing
        val open = input(s, lowOffer, "she offers 90k").let { i -> i.copy(prepare = TurnChain.prepare(null, FallbackShift.Sides(null, null)), decide = TurnChain.decide(null, null), deal = null) }
        assertEquals("Accept 90k", (IntentRouter().route(open) as IntentResult.WhatIf).block.pickThen)
    }
}
