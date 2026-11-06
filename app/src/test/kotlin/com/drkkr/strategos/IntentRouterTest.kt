package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HumanSensitivity
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentHandlers
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.NegotiationView
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID2.2: the router calls exactly one handler, none on standard or support turns, and standard turns keep the baseline (I-D2). */
class IntentRouterTest {
    /** Fake handlers that record which one ran. */
    private class Recorder : IntentHandlers {
        val calls = mutableListOf<String>()
        private fun hit(name: String): IntentResult { calls += name; return IntentResult.None }
        override suspend fun whatIf(input: RouteInput) = hit("what_if")
        override suspend fun compare(input: RouteInput) = hit("compare")
        override suspend fun topOptions(input: RouteInput) = hit("top_options")
        override suspend fun theirReaction(input: RouteInput) = hit("their_reaction")
        override suspend fun planAhead(input: RouteInput) = hit("plan_ahead")
    }

    @Test fun TC_ID_INT_03_exactlyOneHandlerPerIntent_noneForStandard() = kotlinx.coroutines.runBlocking {
        for (i in Intent.entries) {
            val r = Recorder()
            IntentRouter(r).route(RouteInput(i, Fx.loanFamily()))
            if (i == Intent.STANDARD) assertEquals(emptyList<String>(), r.calls) else assertEquals(listOf(i.word), r.calls)
        }
        // the app's handlers answer None until each intent's step
        for (i in Intent.entries) assertSame(IntentResult.None, IntentRouter().route(RouteInput(i, Fx.loanFamily())))
    }

    @Test fun TC_ID_INT_04_aSupportTurnRunsNoHandler() = kotlinx.coroutines.runBlocking {
        val sketch = SketchParser.parse("""{"support":true,"intent":"what_if","what_if":{"options":[{"who":"You","do":"Leave tonight","posture":"boundary"}]}}""")!!
        assertEquals(Intent.WHAT_IF, sketch.intentKind)
        val r = Recorder()
        assertSame(IntentResult.None, IntentRouter(r).route(RouteInput(sketch.intentKind, Fx.loanFamily(), support = true)))
        assertEquals("no handler on a support turn", emptyList<String>(), r.calls)
        Unit
    }

    private fun keys(json: String) = Json.parseToJsonElement(json).jsonObject.keys

    @Test fun TC_ID_INT_05_aStandardTurnKeepsThePowerModeBaselineKeys() = kotlinx.coroutines.runBlocking {
        val fixtures = listOf("loanFamily" to Fx.loanFamily(), "pd" to Fx.pd(), "pdWithInfluencer" to Fx.pdWithInfluencer(), "pdAiPayoffs1" to Fx.pdAiPayoffs(1),
            "hlRaise" to Fx.hlRaise(), "hlLoan" to Fx.hlLoan(), "hlSpouse" to Fx.hlSpouse()) + Fx.templates().mapIndexed { i, s -> "template$i" to s }
        for ((name, s) in fixtures) {
            // the turn's chain as OneShotTurn runs it: walk-away view -> fallback shift -> humanised view -> router -> directive
            val frame = DealFrame.build(s.negotiationContext, s.humanContext, "")
            val negotiated = NegotiationView.apply(EngineDirectiveBuilder.analysisView(s, null, null), frame)
            val fallback = FallbackShift.effect(negotiated, frame, false, false, HumanSensitivity.pick(AnalysisPipeline()))
            val view = HumanizedView.apply(fallback.view)
            assertSame(IntentResult.None, IntentRouter().route(RouteInput(Intent.STANDARD, view)))
            val actual = EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null, deal = frame, fallbackLine = fallback.line).toJson().toString()
            val baseline = javaClass.getResource("/negotiation/baseline/$name.flow.json")!!.readText()
            assertEquals("keys for $name", keys(baseline), keys(actual))
            assertEquals("whole directive for $name", baseline, actual)
        }
        for (name in listOf("loanFamily", "pd") + (0..3).map { "template$it" }) {
            val s = when (name) { "loanFamily" -> Fx.loanFamily(); "pd" -> Fx.pd(); else -> Fx.templates()[name.last().digitToInt()] }
            assertSame(IntentResult.None, IntentRouter().route(RouteInput(Intent.STANDARD, s)))
            val actual = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null).toJson().toString()
            assertEquals("keys for human/$name", keys(javaClass.getResource("/human/$name.json")!!.readText()), keys(actual))
        }
        assertTrue(fixtures.size >= 11); Unit
    }
}
