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
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanLens
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.ReplyBands
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.intent.TurnChain
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID3.4: their reaction in words, and no number in any new block. */
class TheirReactionTest {
    private val main = """"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Hold at the asking price","posture":"boundary"},{"who":"You","do":"Ask her to give up the extended warranty","posture":"repair"},
        {"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Hold at the asking price","Agree"],"for_you":["++","+","++"],"for_them":["-","0","0"]},
        {"when":["Hold at the asking price","Walk away"],"for_you":["-","-","-"],"for_them":["0","-","0"]},
        {"when":["Ask her to give up the extended warranty","Agree"],"for_you":["+","+","+"],"for_them":["0","+","0"]},
        {"when":["Ask her to give up the extended warranty","Walk away"],"for_you":["0","0","0"],"for_them":["0","0","0"]}],
      "relationship":"client","status_quo_for_them":"keeps_something",
      "option_tags":{"Ask her to give up the extended warranty":["asks_them_to_give_up","public","their_interest"]}"""
    private val newMove = """{"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Offer free delivery","posture":"repair"},{"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Offer free delivery","Agree"],"for_you":["+","++","+"],"for_them":["+","+","+"]},
        {"when":["Offer free delivery","Walk away"],"for_you":["-","0","0"],"for_them":["0","0","0"]}]}"""

    private fun saved(): Scenario {
        val s0 = Scenario(id = "r", title = "Sale", decisionOwner = "You", negotiationContext = NegotiationContext(reservationPoint = "100k", aspirationPoint = "130k"))
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse("{$main}")!!, s0).ops, s0)
        val v = ChangeSetValidator.validate(s0, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s0, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun input(s: Scenario, intent: Intent, detail: IntentDetail?, withOps: Boolean = false): RouteInput {
        val deal = DealFrame.build(s.negotiationContext, s.humanContext, "")
        val sides = FallbackShift.Sides(null, null)
        val raw = EngineDirectiveBuilder.analysisView(s, null, null)
        val prepare = TurnChain.prepare(deal, sides)
        val ops = if (withOps) SketchConverter.toOps(SketchParser.parse(newMove)!!, s).ops else emptyList()
        return RouteInput(intent, prepare(raw), ops, detail, base = raw, deal = deal, sides = sides, prepare = prepare, decide = TurnChain.decide(deal, null))
    }

    @Test fun TC_ID_SIM_01_receptiveOddsBecomeWordBands() = runBlocking {
        assertEquals(ReplyBands.GO_ALONG, ReplyBands.of(0.72))
        assertEquals(ReplyBands.EITHER_WAY, ReplyBands.of(0.48))
        assertEquals(ReplyBands.PUSH_BACK, ReplyBands.of(0.20))
        assertEquals(ReplyBands.GO_ALONG, ReplyBands.of(0.60)); assertEquals(ReplyBands.EITHER_WAY, ReplyBands.of(0.35))
        // through the handler: the band matches ResponseModel on the turn's view
        val s = saved()
        val i = input(s, Intent.THEIR_REACTION, IntentDetail(move = "hold at the asking price"))
        val b = (IntentRouter().route(i) as IntentResult.TheirReaction).block
        val p = ResponseModel.receptiveProbability(i.view, i.view.stakeholders.first { it.name == "You" }.id, i.view.actions.first { it.label == b.move }.id, 1.0)!!
        assertEquals(ReplyBands.of(p), b.band)
        assertEquals("Hold at the asking price", b.move)
    }

    @Test fun TC_ID_SIM_02_aGiveUpMoveWhoseLossIsTheLargestModifierNamesTheLoss() = runBlocking {
        val s = saved()
        val b = (IntentRouter().route(input(s, Intent.THEIR_REACTION, IntentDetail(move = "Ask her to give up the extended warranty"))) as IntentResult.TheirReaction).block
        assertEquals(HumanLens.DRIVER_LOSS, b.driver)
        // the loss term (lossAversion - 1 = 1.0) is larger than "their_interest" (0.5) on the same move
        assertTrue(s.humanContext?.statusQuoForThem == "keeps_something")
    }

    @Test fun TC_ID_SIM_03_noPercentAndNoDecimalInAnyNewBlock() = runBlocking {
        val s = saved()
        val blocks = listOf(
            IntentRouter().route(input(s, Intent.THEIR_REACTION, IntentDetail(move = "Offer free delivery"), withOps = true)),
            IntentRouter().route(input(s, Intent.THEIR_REACTION, null)),
            IntentRouter().route(input(s, Intent.COMPARE, IntentDetail(moveA = "Offer free delivery", moveB = "Hold at the asking price"), withOps = true)),
            IntentRouter().route(input(s, Intent.WHAT_IF, IntentDetail(change = "she wants free delivery"), withOps = true)),
            IntentRouter().route(input(s, Intent.TOP_OPTIONS, null))
        )
        val json = blocks.map { r -> when (r) {
            is IntentResult.TheirReaction -> r.block.toJson().toString()
            is IntentResult.Compare -> r.block.toJson().toString()
            is IntentResult.WhatIf -> r.block.toJson().toString()
            is IntentResult.TopOptionsList -> com.drkkr.strategos.domain.intent.TopOptions.toJson(r.options).toString()
            else -> error("no block: $r")
        } }
        for (j in json) {
            assertFalse(j, j.contains("%"))
            assertFalse(j, Regex("""\d*\.\d""").containsMatchIn(j))
            assertFalse(j, Regex("""(?i)\b(odds|probability|percent)\b""").containsMatchIn(j))
        }
        assertEquals(5, json.size)
    }
}
