package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.AiReview
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainSummary
import com.drkkr.strategos.domain.agent.ReviewKind
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Sketches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReviewTest {
    /** A model built the way the app builds it from the loan sketch: everything AI_INFERRED. */
    private fun aiModel(): Scenario {
        val base = Scenario(title = "Loan for my brother", description = "My brother wants me to co-sign a loan.")
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(Sketches.loanStory)!!, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    @Test
    fun listsEveryAiGuessByKind() {
        val items = AiReview.items(aiModel())
        assertEquals(3, items.count { it.kind == ReviewKind.PERSON })           // the user ("You") is never listed
        assertEquals(6, items.count { it.kind == ReviewKind.OPTION })
        assertEquals(1, items.count { it.kind == ReviewKind.LIMIT })
        assertEquals(18, items.count { it.kind == ReviewKind.OUTCOME })
        assertTrue(items.first { it.kind == ReviewKind.OPTION }.title.contains(": "))     // "Brother: Take the loan"
    }

    @Test
    fun keepConfirmAndAcceptAll() {
        val s = aiModel()
        val person = AiReview.items(s).first { it.kind == ReviewKind.PERSON }
        val kept = AiReview.keepAsMine(s, person)
        assertEquals(FactStatus.USER_ESTIMATE, kept.stakeholders.single { it.id == person.key }.factStatus)
        assertEquals(AiReview.items(s).size - 1, AiReview.items(kept).size)
        val outcome = AiReview.items(s).first { it.kind == ReviewKind.OUTCOME }
        assertEquals(1, AiReview.confirm(s, outcome).payoffs.count { it.factStatus == FactStatus.CONFIRMED })
        val all = AiReview.acceptAll(s)
        assertTrue(AiReview.items(all).isEmpty())
        assertEquals(s.payoffs.size, all.payoffs.count { it.factStatus == FactStatus.USER_ESTIMATE })
    }

    @Test
    fun removeCascades() {
        val s = aiModel()
        val brother = AiReview.items(s).first { it.kind == ReviewKind.PERSON && it.title == "Brother" }
        val without = AiReview.remove(s, brother)
        assertTrue(without.stakeholders.none { it.id == brother.key })
        assertTrue(without.actions.none { it.stakeholderId == brother.key })
        assertTrue(without.payoffs.none { it.stakeholderId == brother.key || brother.key in it.actionProfile.keys })
        val option = AiReview.items(s).first { it.kind == ReviewKind.OPTION && it.title == "You: Wait" }
        val noWait = AiReview.remove(s, option)
        assertTrue(noWait.payoffs.none { option.key in it.actionProfile.values })
        assertEquals(s.payoffs.size - 6, noWait.payoffs.size)                                // 3 Brother options x 2 players
        val outcome = AiReview.items(s).first { it.kind == ReviewKind.OUTCOME }
        assertEquals(s.payoffs.size - 1, AiReview.remove(s, outcome).payoffs.size)
    }

    @Test
    fun summaryIsPlain() {
        val text = PlainSummary.build(aiModel(), "What I understood\n• Wife: can stop this\n\nHere's what I see\nEarly view.")
        assertTrue(text.startsWith("Loan for my brother"))
        assertTrue(text.contains("People involved"))
        assertTrue(text.contains("• Wife: can stop this"))
        assertTrue(text.contains("Your options"))
        assertTrue(text.contains("• Co-sign"))
        assertTrue(text.contains("The latest view\nHere's what I see\nEarly view."))
        assertEquals(1, Regex("• Wife: can stop this").findAll(text).count())      // not repeated in the latest view
        listOf("player", "influencer", "payoff", "matrix", "equilibrium").forEach { assertFalse("'$it' in summary", text.lowercase().contains(it)) }
    }
}
