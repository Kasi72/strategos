package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ConfidenceLevel
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.game.LabelChecks
import com.drkkr.strategos.domain.game.LabelIssue
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID1.4: the two checks on the AI's own payoff labels. */
class LabelChecksTest {
    private val R = PayoffDimension.RELATIONSHIPS
    private val W = PayoffDimension.WELLBEING
    private val C = PayoffDimension.COST

    /** You: A, B (and C); Sam: Yes (receptive), No (defensive). [you] maps (mine, theirs) to the user's [R, W, C]. */
    private fun game(you: Map<Pair<String, String>, List<Double>>, status: FactStatus = FactStatus.AI_INFERRED): Scenario {
        val mine = you.keys.map { it.first }.distinct()
        val actions = mine.map { Action(id = it, stakeholderId = "you", label = "Option $it") } +
            listOf(Action(id = "yes", stakeholderId = "sam", label = "Agree", description = "[posture:receptive]"),
                   Action(id = "no", stakeholderId = "sam", label = "Refuse", description = "[posture:defensive]"))
        val pay = you.flatMap { (k, v) ->
            listOf(
                PayoffEntry("you", mapOf("you" to k.first, "sam" to k.second), MultiPayoff(mapOf(R to v[0], W to v[1], C to v[2])), status),
                PayoffEntry("sam", mapOf("you" to k.first, "sam" to k.second), MultiPayoff(mapOf(R to 5.0, W to 5.0, C to 5.0)), status)
            )
        }
        return Scenario(id = "lc", title = "t", decisionOwner = "You",
            stakeholders = listOf(Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER), Stakeholder(id = "sam", name = "Sam", gameRole = GameRole.PLAYER)),
            actions = actions, payoffs = pay)
    }

    private val balanced = mapOf(
        ("a" to "yes") to listOf(8.5, 6.5, 5.0), ("a" to "no") to listOf(3.5, 5.0, 5.0),
        ("b" to "yes") to listOf(6.5, 8.5, 3.5), ("b" to "no") to listOf(5.0, 3.5, 6.5)
    )

    @Test fun TC_ID_RUB_07_aRefusalLabelledBetterThanAcceptanceIsFlaggedAndNothingChanges() {
        assertTrue(LabelChecks.check(game(balanced)).isEmpty())
        // A: against DEFENSIVE `+` on WELLBEING, against RECEPTIVE `-`
        val s = game(balanced + (("a" to "yes") to listOf(8.5, 3.5, 5.0)) + (("a" to "no") to listOf(3.5, 6.5, 5.0)))
        val before = s.copy()
        val issues = LabelChecks.check(s)
        assertEquals(listOf<LabelIssue>(LabelIssue.RefusalBeatsAcceptance("a", W)), issues)
        assertEquals("values unchanged", before, s)
        // COST runs the other way: a lower cost on refusal is the "better" one
        val cost = LabelChecks.check(game(balanced + (("b" to "yes") to listOf(6.5, 8.5, 6.5)) + (("b" to "no") to listOf(5.0, 3.5, 3.5))))
        assertEquals(listOf<LabelIssue>(LabelIssue.RefusalBeatsAcceptance("b", C)), cost)
        // a figure the user gave is theirs, not the AI's reading
        assertTrue(LabelChecks.check(game(balanced + (("a" to "yes") to listOf(8.5, 3.5, 5.0)) + (("a" to "no") to listOf(3.5, 6.5, 5.0)), FactStatus.USER_ESTIMATE)).isEmpty())
        // confidence one tier lower
        val pr = AnalysisPipeline().run(s)
        val plain = EngineDirectiveBuilder.build(s, pr, null, gameTheoreticPattern = "Zero-Sum")
        val flagged = EngineDirectiveBuilder.build(s, pr, null, gameTheoreticPattern = "Zero-Sum", labelIssues = issues)
        assertEquals(ConfidenceLevel.HIGH, plain.confidenceLevel)
        assertEquals(ConfidenceLevel.MEDIUM, flagged.confidenceLevel)
        assertEquals(ConfidenceLevel.LOW, EngineDirectiveBuilder.build(s, pr, null, labelIssues = issues).confidenceLevel)
    }

    @Test fun TC_ID_RUB_08_oneOptionLabelledBestEverywhere() {
        val s = game(mapOf(
            ("a" to "yes") to listOf(8.5, 8.5, 1.5), ("a" to "no") to listOf(6.5, 6.5, 3.5),
            ("b" to "yes") to listOf(6.5, 8.5, 3.5), ("b" to "no") to listOf(3.5, 5.0, 6.5),
            ("c" to "yes") to listOf(5.0, 5.0, 5.0), ("c" to "no") to listOf(1.5, 1.5, 8.5)
        ))
        val issues = LabelChecks.check(s)
        assertEquals(listOf<LabelIssue>(LabelIssue.LabelDominance("a")), issues)
        assertTrue("no dominance in a balanced table", LabelChecks.check(game(balanced)).none { it is LabelIssue.LabelDominance })
        val pr = AnalysisPipeline().run(s)
        val d = EngineDirectiveBuilder.build(s, pr, null, gameTheoreticPattern = "Zero-Sum", dominatedStrategies = listOf("You: Option c (dominated)"), labelIssues = issues)
        assertFalse("no dominated_strategies key", d.toJson().containsKey("dominated_strategies"))
        assertTrue(d.autoTopOptions)
        assertEquals("at most MEDIUM", ConfidenceLevel.MEDIUM, d.confidenceLevel)
        // without the issue the same list is sent (the baseline is unchanged)
        val base = EngineDirectiveBuilder.build(s, pr, null, gameTheoreticPattern = "Zero-Sum", dominatedStrategies = listOf("You: Option c (dominated)"))
        assertTrue(base.toJson().containsKey("dominated_strategies"))
        assertFalse(base.autoTopOptions)
        assertFalse("label issues never reach the JSON", d.toJson().toString().contains("abel"))
    }

    /** A stand-in pick: the user's option with the highest total against the receptive reply. */
    private fun pick(s: Scenario): String? = s.actionsFor("you").maxByOrNull { a ->
        s.payoffs.first { it.stakeholderId == "you" && it.actionProfile["you"] == a.id && it.actionProfile["sam"] == "yes" }.payoff.values.values.sum()
    }?.id

    @Test fun TC_ID_RUB_09_aPickThatFlipsWhenTheLabelSpacingIsDoubledIsFragile() {
        // A = ++ / -- / ++ (18.5), B = + / + / 0 (18.0): A leads. Doubled, the extremes hit the scale's ends (10, 0, 10 = 20) and B
        // (8, 8, 5 = 21) leads. COST is labelled in the stored direction here, as the stand-in pick only sums.
        val s = game(mapOf(
            ("a" to "yes") to listOf(8.5, 1.5, 8.5), ("a" to "no") to listOf(5.0, 5.0, 5.0),
            ("b" to "yes") to listOf(6.5, 6.5, 5.0), ("b" to "no") to listOf(5.0, 5.0, 5.0)
        ))
        assertEquals("a", pick(s))
        assertEquals("b", pick(com.drkkr.strategos.domain.human.HumanSensitivity.labelSpaced(s, 2.0)))
        assertTrue(com.drkkr.strategos.domain.human.HumanSensitivity.pickChanges(s, ::pick))
        // a robust lead stays robust, and an older reply's numbers are not re-spaced
        assertFalse(com.drkkr.strategos.domain.human.HumanSensitivity.pickChanges(game(balanced), ::pick))
        val numeric = s.copy(payoffs = s.payoffs.map { it.copy(provenanceNote = "From your description · AI_SCORED_NUMERIC") })
        assertTrue(com.drkkr.strategos.domain.human.HumanSensitivity.labelSpaced(numeric, 2.0) === numeric)
    }
}
