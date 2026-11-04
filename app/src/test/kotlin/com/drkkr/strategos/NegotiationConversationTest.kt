package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.*
import org.junit.Test

/** The negotiation prep form is gone: the conversation asks in plain words and the answers fill NegotiationContext. */
class NegotiationConversationTest {
    private fun reply(negotiation: String) = """{"goal":"get a fair raise","people":[{"name":"You","role":"player"},{"name":"Manager","role":"player"}],
        "options":[{"who":"You","do":"Ask for 19 lakh","posture":"boundary"},{"who":"Manager","do":"Counter at 16","posture":"receptive"}],
        "negotiation":$negotiation}"""

    private fun applied(s: Scenario, json: String): Scenario {
        val sketch = SketchParser.parse(json)!!
        val ops = SketchConverter.toOps(sketch, s).ops
        return ChangeSetApplier.apply(s, ops, ops.indices.map { OpDecision(it, Decision.ACCEPT) }, ops.mapNotNull { it.ref }.associateWith { java.util.UUID.randomUUID().toString() }, emptyList(), "test").scenario
    }

    @Test fun `what the user said in plain words lands in the negotiation context`() {
        val out = applied(Scenario(title = "t"), reply("""{"if_no_deal":"take the 18 lakh offer elsewhere","lowest_acceptable":"16 lakh","ideal":"19 lakh"}"""))
        val nc = out.negotiationContext!!
        assertEquals("take the 18 lakh offer elsewhere", nc.batna)
        assertEquals("16 lakh", nc.reservationPoint)
        assertEquals("19 lakh", nc.aspirationPoint)
        assertTrue(nc.openingAnchor.isBlank())
    }

    @Test fun `a later answer adds to the saved context and a blank never erases it`() {
        val first = applied(Scenario(title = "t"), reply("""{"lowest_acceptable":"16 lakh"}"""))
        val second = applied(first, reply("""{"opening_ask":"19 lakh and a title change","lowest_acceptable":""}"""))
        val nc = second.negotiationContext!!
        assertEquals("16 lakh", nc.reservationPoint)
        assertEquals("19 lakh and a title change", nc.openingAnchor)
    }

    @Test fun `a message with no negotiation facts saves nothing`() {
        assertNull(applied(Scenario(title = "t"), reply("null")).negotiationContext)
    }

    @Test fun `the extraction prompt's own example still parses and teaches the plain keys`() {
        val example = ArgonPrompts.EXTRACTION_PROMPT.substringAfter("JSON SCHEMA:").trim()
        assertNotNull(SketchParser.parse(example))
        assertTrue(ArgonPrompts.EXTRACTION_PROMPT.contains("if_no_deal") && ArgonPrompts.EXTRACTION_PROMPT.contains("never use the terms BATNA"))
    }

    // ── the questions ────────────────────────────────────────────────────────────────────────────────────────
    private val text = "I want to negotiate a raise with my manager"

    @Test fun `questions use plain words and no jargon`() {
        NegotiationQuestions.ALL.forEach { q ->
            assertFalse(q.text, Regex("(?i)batna|zopa|reservation|anchor|aspiration").containsMatchIn(q.text))
        }
    }

    @Test fun `asks the fallback first, then the lowest acceptable, never the same one twice`() {
        val q1 = NegotiationQuestions.next(text, null, emptyList())!!
        assertEquals(NegotiationQuestions.Field.BATNA, q1.field)
        val q2 = NegotiationQuestions.next(text, null, listOf("Some advice. ${q1.text} (Only if you'd like to say.)"))!!
        assertEquals(NegotiationQuestions.Field.RESERVATION, q2.field)
    }

    @Test fun `skips what is already known and stays quiet outside negotiations`() {
        val known = NegotiationContext(batna = "other offer", reservationPoint = "16")
        assertEquals(NegotiationQuestions.Field.ASPIRATION, NegotiationQuestions.next(text, known, emptyList())!!.field)
        assertNull(NegotiationQuestions.next("My brother and I keep arguing about our parents", null, emptyList()))
    }

    @Test fun `the question is added to the coach's closing line as one optional sentence`() {
        val q = NegotiationQuestions.ALL.first()
        val out = NegotiationQuestions.appendTo("Want help wording that?", q)
        assertTrue(out, out.startsWith("Want help wording that?") && out.contains(q.text) && out.contains("Only if you'd like to say"))
        assertEquals("${q.text} (Only if you'd like to say.)", NegotiationQuestions.appendTo("", q))
    }
}
