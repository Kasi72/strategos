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
import com.drkkr.strategos.domain.intent.Compare
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.IntentResult
import com.drkkr.strategos.domain.intent.IntentRouter
import com.drkkr.strategos.domain.intent.ReplyBands
import com.drkkr.strategos.domain.intent.RouteInput
import com.drkkr.strategos.domain.intent.TurnChain
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID3.2: two moves compared, in words. */
class CompareIntentTest {
    private val R = PayoffDimension.RELATIONSHIPS

    /**
     * You: "bold" (boundary) and "safe" (repair); Sam: "yes"/"no". Yours are the AI's labels; Sam's are the user's own estimates
     * (so only yours move with the label spacing). [bold] and [safe] are your (yes, no); [pBold] sets Sam's yes-minus-no gap.
     */
    private fun game(bold: Pair<Double, Double>, safe: Pair<Double, Double>, boldGap: Double, safeGap: Double = 0.0): Scenario {
        fun you(m: String, t: String, v: Double) = PayoffEntry("you", mapOf("you" to m, "sam" to t), MultiPayoff(mapOf(R to v)), FactStatus.AI_INFERRED)
        fun sam(m: String, t: String, v: Double) = PayoffEntry("sam", mapOf("you" to m, "sam" to t), MultiPayoff(mapOf(R to v)), FactStatus.USER_ESTIMATE)
        return Scenario(id = "c", title = "t", decisionOwner = "You",
            stakeholders = listOf(Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER), Stakeholder(id = "sam", name = "Sam", gameRole = GameRole.PLAYER)),
            actions = listOf(Action(id = "bold", stakeholderId = "you", label = "Name the problem today", description = "[posture:boundary]"),
                Action(id = "safe", stakeholderId = "you", label = "Wait for a calm moment", description = "[posture:repair]"),
                Action(id = "yes", stakeholderId = "sam", label = "Talk it through", description = "[posture:receptive]"),
                Action(id = "no", stakeholderId = "sam", label = "Shut down", description = "[posture:defensive]")),
            payoffs = listOf(you("bold", "yes", bold.first), you("bold", "no", bold.second), you("safe", "yes", safe.first), you("safe", "no", safe.second),
                sam("bold", "yes", 5.0 + boldGap), sam("bold", "no", 5.0), sam("safe", "yes", 5.0 + safeGap), sam("safe", "no", 5.0)))
    }

    private fun input(s: Scenario, a: String, b: String, ops: List<com.drkkr.strategos.domain.agent.ChangeOp> = emptyList()) =
        RouteInput(Intent.COMPARE, TurnChain.prepare(null, FallbackShift.Sides(null, null))(s), ops, IntentDetail(moveA = a, moveB = b), base = s)

    private fun block(r: IntentResult) = (r as IntentResult.Compare).block

    @Test fun TC_ID_CMP_01_twoSavedMovesInWordsOnly() = runBlocking {
        // safe clearly ahead: good either way, and Sam is likelier to talk it through
        val s = game(bold = 7.0 to 1.5, safe = 7.5 to 6.0, boldGap = -1.0, safeGap = 1.0)
        val b = block(IntentRouter().route(input(s, "Name the problem today", "wait for a calm moment")))
        assertEquals(Compare.B, b.better)
        assertEquals("Name the problem today", b.a.move); assertEquals("Wait for a calm moment", b.b.move)
        assertEquals(ReplyBands.PUSH_BACK, b.a.band); assertEquals("Shut down", b.a.theirLikelyReply)
        assertEquals(ReplyBands.GO_ALONG, b.b.band); assertEquals("Talk it through", b.b.theirLikelyReply)
        assertEquals(Compare.why("Wait for a calm moment", "Name the problem today", true), b.why)
        val json = b.toJson().toString()
        assertFalse(json, Regex("""\d""").containsMatchIn(json)); assertFalse(json.contains("%"))
        // into the directive
        val d = EngineDirectiveBuilder.build(s, com.drkkr.strategos.domain.analysis.AnalysisPipeline().run(s), null).copy(compare = b).toJson()
        assertEquals(b.toJson(), d["compare"])
    }

    private val main = """"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Hold at the asking price","posture":"boundary"},{"who":"You","do":"Ask what budget she has","posture":"repair"},
        {"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Hold at the asking price","Agree"],"for_you":["++","+","++"],"for_them":["-","0","0"]},
        {"when":["Hold at the asking price","Walk away"],"for_you":["-","-","-"],"for_them":["0","-","0"]},
        {"when":["Ask what budget she has","Agree"],"for_you":["+","+","+"],"for_them":["0","+","0"]},
        {"when":["Ask what budget she has","Walk away"],"for_you":["0","0","0"],"for_them":["0","0","0"]}]"""
    private val newMove = """{"people":[{"name":"You","role":"player"},{"name":"Asha","role":"player"}],
      "options":[{"who":"You","do":"Offer free delivery","posture":"repair"},{"who":"Asha","do":"Agree","posture":"receptive"},{"who":"Asha","do":"Walk away","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Offer free delivery","Agree"],"for_you":["+","++","+"],"for_them":["+","+","+"]},
        {"when":["Offer free delivery","Walk away"],"for_you":["-","0","0"],"for_them":["0","0","0"]}]}"""

    @Test fun TC_ID_CMP_02_aMoveThatIsNotSavedIsComparedOnACopy() = runBlocking {
        val s0 = Scenario(id = "w", title = "Sale", decisionOwner = "You")
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse("{$main}")!!, s0).ops, s0)
        val v = ChangeSetValidator.validate(s0, o)
        val accepted = v.accepted.map { it.op }
        val s = ChangeSetApplier.apply(s0, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
        val before = s.copy()
        val ops = SketchConverter.toOps(SketchParser.parse(newMove)!!, s).ops
        val b = block(IntentRouter().route(input(s, "Offer free delivery", "Hold at the asking price", ops)))
        assertEquals("Offer free delivery", b.a.move)
        assertEquals("the saved scenario is unchanged", before, s)
        assertTrue(s.actions.none { it.label == "Offer free delivery" })
        // without the hypothetical's ops, the unsaved move can't be compared
        assertTrue(IntentRouter().route(input(s, "Offer free delivery", "Hold at the asking price")) is IntentResult.None)
    }

    @Test fun TC_ID_CMP_03_anOrderThatFlipsUnderTheSensitivityRunsIsTooClose() = runBlocking {
        // bold "++"/"--" with Sam at about 0.63 to say yes, safe "+"/"0" at even odds: bold just ahead by default; doubling the
        // label spacing clips bold at 10 and 0 but not safe, and safe comes out ahead
        val s = game(bold = 8.5 to 1.5, safe = 6.5 to 5.0, boldGap = 0.532)
        val b = block(IntentRouter().route(input(s, "Name the problem today", "Wait for a calm moment")))
        assertEquals(Compare.TOO_CLOSE, b.better)
        assertEquals(Compare.why(null, null, false), b.why)
        // a clear margin is not too close
        assertEquals(Compare.A, block(IntentRouter().route(input(game(bold = 8.5 to 6.5, safe = 6.5 to 5.0, boldGap = 0.532), "Name the problem today", "Wait for a calm moment"))).better)
    }

    @Test fun cpi2_aDetailWrittenAsOneStringAndTheUsersShortWordsStillMatch() = runBlocking {
        assertEquals("wait one more month" to "confront him directly this week", Compare.split("wait one more month vs confront him directly this week"))
        assertEquals("A" to "B", Compare.split("A or B?"))
        assertEquals(null, Compare.split("just one move"))
        val parsed = SketchParser.parse("""{"intent":"compare","intent_detail":"wait one more month vs confront him directly this week"}""")!!
        assertEquals("wait one more month vs confront him directly this week", parsed.intentDetail?.change)
        val s = game(bold = 7.0 to 1.5, safe = 7.5 to 6.0, boldGap = -1.0, safeGap = 1.0).let { g ->
            g.copy(actions = g.actions.map { a -> when (a.id) { "bold" -> a.copy(label = "Name the pattern to Arjun directly this week"); "safe" -> a.copy(label = "Wait one more month and hope he delivers"); else -> a } })
        }
        val b = block(IntentRouter().route(RouteInput(Intent.COMPARE, s, detail = parsed.intentDetail, base = s)))
        assertEquals("Wait one more month and hope he delivers", b.a.move)
        assertEquals("Name the pattern to Arjun directly this week", b.b.move)
    }
}
