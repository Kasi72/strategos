package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.ConfidenceLevel
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.DeliveryTags
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RuledOut
import com.drkkr.strategos.domain.human.RuledOutMove
import com.drkkr.strategos.domain.intent.ConfidenceSummary
import com.drkkr.strategos.domain.intent.ConfidenceTier
import com.drkkr.strategos.domain.intent.StageGuard
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage guard (7 Nov, the Bumble project): a move that takes things up a stage gets a "not yet" option beside it, a move
 * ruled out before keeps its old ceiling when it comes back reworded, and a reply is a "Clear-win move" only at HIGH confidence.
 */
class StageGuardTest {
    private val people = """"people":[{"name":"You","role":"player"},{"name":"Harini","role":"player"}]"""
    private val dims = """"dims":["RELATIONSHIPS","LONG_TERM_VALUE","REPUTATION"]"""

    /** Turn 1: the declaration is the "much worse" move, in both replies. */
    private val turn1 = """{$people,$dims,
      "options":[{"who":"You","do":"Message her with a light hello","posture":"repair"},
        {"who":"You","do":"Wait for her to reach out","posture":"boundary"},
        {"who":"You","do":"Tell her straight away you want to marry her","posture":"reactive"},
        {"who":"Harini","do":"Reply warmly","posture":"receptive"},{"who":"Harini","do":"Go quiet","posture":"defensive"}],
      "outcomes":[
        {"when":["Message her with a light hello","Reply warmly"],"for_you":["++","++","+"],"for_them":["+","+","+"]},
        {"when":["Message her with a light hello","Go quiet"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Wait for her to reach out","Reply warmly"],"for_you":["+","+","0"],"for_them":["+","0","0"]},
        {"when":["Wait for her to reach out","Go quiet"],"for_you":["-","-","0"],"for_them":["0","0","0"]},
        {"when":["Tell her straight away you want to marry her","Reply warmly"],"for_you":["--","--","-"],"for_them":["-","-","-"]},
        {"when":["Tell her straight away you want to marry her","Go quiet"],"for_you":["--","--","--"],"for_them":["0","0","-"]}]}"""

    /** Turn 2: fresh options; the declaration is back reworded, scored "much better". [changed] is Call 1's `changed`. */
    private fun turn2(changed: String?) = """{$people,$dims,
      "options":[{"who":"You","do":"Send her one clear message stating your intention","posture":"repair",
          "revisits":"Tell her straight away you want to marry her"${changed?.let { ",\"changed\":\"$it\"" } ?: ",\"changed\":null"}},
        {"who":"You","do":"Say it in person once you meet for coffee","posture":"boundary"},
        {"who":"You","do":"Send several messages to make sure she got it","posture":"reactive"},
        {"who":"Harini","do":"Reply warmly","posture":"receptive"},{"who":"Harini","do":"Go quiet","posture":"defensive"}],
      "option_tags":{"Send her one clear message stating your intention":["steps_up","written"],"Say it in person once you meet for coffee":["not_yet"]},
      "outcomes":[
        {"when":["Send her one clear message stating your intention","Reply warmly"],"for_you":["++","++","+"],"for_them":["+","+","+"]},
        {"when":["Send her one clear message stating your intention","Go quiet"],"for_you":["+","+","0"],"for_them":["0","0","0"]},
        {"when":["Say it in person once you meet for coffee","Reply warmly"],"for_you":["+","+","+"],"for_them":["+","+","+"]},
        {"when":["Say it in person once you meet for coffee","Go quiet"],"for_you":["0","0","0"],"for_them":["0","0","0"]},
        {"when":["Send several messages to make sure she got it","Reply warmly"],"for_you":["0","-","-"],"for_them":["-","-","-"]},
        {"when":["Send several messages to make sure she got it","Go quiet"],"for_you":["--","--","--"],"for_them":["-","-","-"]}]}"""

    private fun apply(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun bestOf(s: Scenario, label: String): Double {
        val you = IntakeChecklist.self(s)!!.id
        return RuledOut.bestCase(s, you, s.actions.single { it.label == label }.id)!!
    }

    private val first = apply(turn1, Scenario(id = "bumble", title = "College Band Match"))

    @Test fun TC_SG_01_aDroppedMoveWorseThanTodayEvenAtBestIsRemembered_othersAreNot() {
        val second = apply(turn2(null), first)
        val ruled = second.humanContext!!.ruledOut
        assertEquals(listOf("Tell her straight away you want to marry her"), ruled.map { it.label })
        assertTrue("best case is worse than today", ruled.single().best < RuledOut.SAME)
        // "Wait for her to reach out" was dropped too, but its best case ("+") beat today: not ruled out.
        assertFalse(ruled.any { it.label.startsWith("Wait") })
    }

    @Test fun TC_SG_02_aRuledOutMoveBackRewordedWithNothingChangedKeepsItsOldCeiling() {
        val second = apply(turn2(null), first)
        val old = second.humanContext!!.ruledOut.single().best
        val best = bestOf(second, "Send her one clear message stating your intention")
        assertTrue("capped at $old, got $best", best <= old + 1e-9)
        // Harini's scores for the same outcomes are not touched.
        val harini = second.stakeholders.single { it.name == "Harini" }.id
        val action = second.actions.single { it.label.startsWith("Send her one clear") }.id
        assertTrue(second.payoffs.filter { it.stakeholderId == harini && it.actionProfile.values.contains(action) }.any { e -> e.payoff.values.values.any { it > RuledOut.SAME } })
    }

    @Test fun TC_SG_03_withAChangeTheReturningMoveIsScoredAfresh() {
        val second = apply(turn2("We reconnected and she said yes to coffee"), first)
        assertTrue(bestOf(second, "Send her one clear message stating your intention") > RuledOut.SAME)
    }

    @Test fun TC_SG_04_stageTagsAreKeptOnTheActionsAndOneWayOnlyReadsThem() {
        val second = apply(turn2(null), first)
        val tags = second.actions.associate { it.label to DeliveryTags.of(it) }
        assertEquals(setOf("steps_up", "written"), tags["Send her one clear message stating your intention"])
        assertEquals(setOf("not_yet"), tags["Say it in person once you meet for coffee"])
        assertFalse("a not-yet option is there", StageGuard.oneWayOnly(second))
        // The same model with the not-yet option gone: only "now" was weighed.
        val notYet = second.actions.single { it.label.startsWith("Say it in person") }.id
        assertTrue(StageGuard.oneWayOnly(second.copy(actions = second.actions.filter { it.id != notYet })))
        assertFalse("nothing steps up", StageGuard.oneWayOnly(first))
    }

    @Test fun TC_SG_05_revisitedNamesTheEarlierMoveForThePickOnly() {
        val sk = SketchParser.parse(turn2("She said yes to coffee"))!!
        val second = apply(turn2("She said yes to coffee"), first)
        val r = StageGuard.revisited(sk, second, "Send her one clear message stating your intention")!!
        assertEquals("Tell her straight away you want to marry her", r.earlier)
        assertEquals("She said yes to coffee", r.changed)
        assertNull("another pick", StageGuard.revisited(sk, second, "Say it in person once you meet for coffee"))
        val unchanged = StageGuard.revisited(SketchParser.parse(turn2(null))!!, apply(turn2(null), first), "Send her one clear message stating your intention")!!
        assertNull(unchanged.changed)
    }

    @Test fun TC_SG_06_ruledOutMovesSurviveMergesAndReachExtraction() {
        val a = HumanContext(ruledOut = listOf(RuledOutMove("Tell her straight away", 2.0)))
        val merged = a.merge(HumanContext(relationship = "friend")).merge(HumanContext(ruledOut = listOf(RuledOutMove("Send ten texts", 1.5), RuledOutMove("tell her straight away", 2.0))))
        assertEquals(listOf("Send ten texts", "tell her straight away"), merged.ruledOut.map { it.label })
        val many = (1..12).map { RuledOutMove("move $it", 1.0) }
        assertEquals(RuledOut.MAX, HumanContext().merge(HumanContext(ruledOut = many)).ruledOut.size)
        val second = apply(turn2(null), first)
        val digest = LiveDigest.build(second, forSketch = true)
        assertEquals("Tell her straight away you want to marry her", digest["ruled_out_before"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull("only for extraction", LiveDigest.build(second)["ruled_out_before"])
    }

    @Test fun TC_SG_07_aStepUpPickIsNeverAClearWin() {
        val high = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), null, "Harini")
        assertEquals(ConfidenceTier.HIGH, high.tier)
        val oneWay = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), null, "Harini", oneWayOnly = true)
        assertEquals(ConfidenceTier.MODERATE, oneWay.tier); assertEquals(ConfidenceSummary.ONE_WAY, oneWay.reason)
        val early = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), null, "Harini", oneWayOnly = true, revisitedUnchanged = true)
        assertEquals(ConfidenceSummary.STILL_EARLY, early.reason)
    }

    @Test fun TC_SG_07b_pickingNowOverANotYetOptionIsAJudgementCall_withoutTheStepUpTag() {
        // Seen live: the not-yet option was tagged, the declaration was not, and the declaration was picked at HIGH.
        val second = apply(turn2(null).replace("\"steps_up\",", ""), first)
        assertFalse(StageGuard.oneWayOnly(second))
        assertTrue(StageGuard.nowOverWait(second, "Send her one clear message stating your intention"))
        assertFalse("the not-yet pick itself", StageGuard.nowOverWait(second, "Say it in person once you meet for coffee"))
        assertFalse("no not-yet option at all", StageGuard.nowOverWait(first, "Message her with a light hello"))
        val c = ConfidenceSummary.of(ConfidenceLevel.HIGH, false, null, emptyList(), null, "Harini", nowOverWait = true)
        assertEquals(ConfidenceTier.MODERATE, c.tier); assertEquals(ConfidenceSummary.NOW_OR_WAIT, c.reason)
    }

    @Test fun TC_SG_07c_aLongOptionIsCutAtAWordNotDropped_andItsOutcomesAndTagsStillMatch() {
        val long = "Say you're glad you reconnected and want to take it seriously, but save the full conversation for when you meet in person"
        assertTrue(long.length > ChangeSetValidator.MAX_LABEL)
        val second = apply(turn2(null).replace("Say it in person once you meet for coffee", long), first)
        val kept = second.actions.single { it.label.startsWith("Say you're glad") }
        assertTrue(kept.label.length <= ChangeSetValidator.MAX_LABEL)
        assertTrue("cut at a word: ${kept.label}", long.startsWith(kept.label) && long[kept.label.length] == ' ')
        assertEquals(setOf("not_yet"), DeliveryTags.of(kept))
        val you = IntakeChecklist.self(second)!!.id
        assertEquals("both replies scored", 2, second.payoffs.count { it.stakeholderId == you && it.actionProfile[you] == kept.id })
    }

    @Test fun TC_SG_08_theMoveLabelFollowsTheTier() {
        val g = CoachGuideSketch(whatHappened = "She said yes to coffee.", clearWinMove = "Say it in person.", exactWords = "Coffee soon?",
            doThis = listOf("Pick a day"), dontDoThis = listOf("Don't send a long message"), followUp = "Send it Saturday.")
        assertTrue(PlainResult.coach(g).contains("Clear-win move: Say it in person."))
        val unsure = PlainResult.coach(g, sure = false)
        assertTrue(unsure.contains("Best move for now: Say it in person."))
        assertFalse(unsure.contains("Clear-win"))
    }

    @Test fun TC_SG_09_thePromptsCarryTheRules() {
        val p = ArgonPrompts.EXTRACTION_PROMPT
        assertTrue(p.contains("\n24. STEP UP.")); assertTrue(p.contains("\n25. RULED OUT."))
        for (w in listOf("steps_up", "not_yet", "ruled_out_before", "revisits", "changed")) assertTrue(w, p.contains(w))
        assertTrue(ArgonPrompts.ENGINE_COACH_PROMPT.contains("\n33. REVISITED."))
        assertNotNull(SketchParser.parse(turn2(null)))
    }
}
