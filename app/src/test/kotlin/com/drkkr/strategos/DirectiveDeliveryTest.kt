package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.Channel
import com.drkkr.strategos.domain.human.Delivery
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.PlannedTactic
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.Timing
import com.drkkr.strategos.fixtures.Fx
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL3.4: the delivery block in the directive, and the coach's rules about it. */
class DirectiveDeliveryTest {
    private val s = Fx.hlRaise().let { raise ->
        raise.copy(
            actions = raise.actions.map { if (it.id == "a1") it.copy(description = "[posture:repair][f:asks_them_to_give_up,corrects_them,open_ended,public]") else it },
            humanContext = HumanContext(relationship = "manager", theirPower = "they_higher", deadline = "none")
        )
    }
    private val base = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null)
    private val delivery = Delivery(
        tactics = listOf(PlannedTactic(TacticId.PRIVATE_AND_CREDIT, "Say it in private, and credit their thinking first.", 0.1),
            PlannedTactic(TacticId.DEFAULT_WITH_DEADLINE, "Say what you'll do and by when, unless they'd prefer something else; make objecting easy.", 0.08)),
        timing = Timing.NOW, channel = Channel.PRIVATE, sayLess = true, defaultOffer = "Ask with a plan", optionsToOffer = listOf("Ask with a plan", "Hold the line")
    )

    @Test
    fun TC_HL_DIR_01_theJsonCarriesHintsAndNeverANameASourceOrALaw() {
        val json = base.copy(delivery = delivery).toJson()
        val d = json.getValue("delivery").jsonObject
        assertEquals(setOf("tactics", "timing", "channel", "keep_it_short", "default_offer", "options_to_offer"), d.keys)
        assertEquals(listOf("Say it in private, and credit their thinking first.", "Say what you'll do and by when, unless they'd prefer something else; make objecting easy."),
            d.getValue("tactics").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("NOW", d.getValue("timing").jsonPrimitive.content)
        assertEquals("PRIVATE", d.getValue("channel").jsonPrimitive.content)
        val text = json.toString()
        assertFalse(text, BannedWords.containedIn(text))
        assertFalse(text, text.contains("Ch."))
        assertFalse(text, Regex("L\\d").containsMatchIn(text))
        for (id in TacticId.values()) assertFalse(id.name, text.contains(id.name, ignoreCase = true))
    }

    @Test
    fun TC_HL_DIR_02_noHumanContextMeansNoDeliveryAndNoKey() {
        val loan = Fx.loanFamily()
        val d = EngineDirectiveBuilder.build(loan, AnalysisPipeline().run(loan), null)
        assertNull(d.delivery)
        assertTrue(d.watchFor.isEmpty()); assertNull(d.humanLens)
        val json = d.toJson()
        assertFalse(json.containsKey("delivery")); assertFalse(json.containsKey("watch_for"))
        // an EMPTY delivery (safety, coercion) is not sent either
        assertFalse(base.copy(delivery = Delivery()).toJson().containsKey("delivery"))
    }

    @Test
    fun TC_HL_DIR_03_theBadgeAndMoveDoNotChangeBecauseOfDelivery() {
        val with = base.copy(delivery = delivery)
        assertEquals(base.badge(), with.badge())
        assertEquals(base.moveToImplement, with.moveToImplement)
        assertEquals(base.stressVerdict, with.stressVerdict)
    }

    @Test
    fun TC_HL_DIR_04_theHumanLensIsNeverSentToTheCoach() {
        assertFalse(base.copy(humanLens = "They'd be giving something up.").toJson().toString().contains("giving something up"))
    }

    @Test
    fun TC_HL_COACH_01_theCoachPromptHasTheDeliveryTimingAndNoManipulationRules() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("\n19. DELIVERY"))
        assertTrue(p.contains("exact_words") && p.contains("default_offer") && p.contains("options_to_offer"))
        assertTrue(p.contains("Never name a technique, a law or a book"))
        assertTrue(p.contains("\n20. TIMING") && p.contains("AFTER_COOLING"))
        assertTrue(p.contains("\n22. NO MANIPULATION") && p.contains("pretending, hiding your real aim, or making someone dependent"))
    }

    @Test
    fun TC_HL_COACH_02_aReplyThatNamesALawABookOrATacticIsFlagged() {
        fun reply(text: String) = CoachReply(engineMove = "x", whatHappened = text)
        assertTrue(reply("As Law 31 says, offer two options").namesSomethingBanned())
        assertTrue(reply("Greene would call this a good move").namesSomethingBanned())
        assertTrue(CoachReply(engineMove = "x", doThis = listOf("Use DEFAULT_WITH_DEADLINE")).namesSomethingBanned())
        assertTrue(CoachReply(engineMove = "x", followUp = "Read the 48 Laws of Power").namesSomethingBanned())
        assertTrue(CoachReply(engineMove = "x", exactWords = "Sunstein calls this a nudge").namesSomethingBanned())
        assertFalse(reply("Say it privately, and give her the credit first.").namesSomethingBanned())
        assertFalse(reply("A gentle nudge toward Friday works.").namesSomethingBanned())
        assertFalse(CoachReply(engineMove = "Law 31 is the engine move?").namesSomethingBanned())      // the echoed move is the engine's own text, not the coach's
    }
}
