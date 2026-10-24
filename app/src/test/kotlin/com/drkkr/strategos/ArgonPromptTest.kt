package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.fixtures.Sketches
import org.junit.Assert.*
import org.junit.Test

class ArgonPromptTest {
    private val coachJson = """{"archetype":"STAG_HUNT","trust_delta":6,"tension_delta":-8,
        "coach_guide":{"what_happened":"They felt cornered.","clear_win_move":"Pause for 24h.","exact_words":"Let's revisit tomorrow.",
        "do_this":["Give space"],"dont_do_this":["Don't double-text"]}}"""

    @Test fun TC_PROMPT_01_sketchParsesCoachGuideAndDeltas() {
        val s = SketchParser.parse(coachJson)!!
        assertEquals("STAG_HUNT", s.archetype); assertEquals(6.0, s.trustDelta, 0.0); assertEquals(-8.0, s.tensionDelta, 0.0)
        val g = s.coachGuide!!
        assertEquals("They felt cornered.", g.whatHappened); assertEquals("Pause for 24h.", g.clearWinMove)
        assertEquals("Let's revisit tomorrow.", g.exactWords)
        assertEquals(listOf("Give space"), g.doThis); assertEquals(listOf("Don't double-text"), g.dontDoThis)
    }

    @Test fun TC_PROMPT_02_plainResultIncludesClearWinDoAndDont() {
        val s = Fx.loanFamily()
        val text = PlainResult.build(s, AnalysisPipeline().run(s), emptyList(), SketchParser.parse(coachJson)!!.coachGuide)
        listOf("What's happening underneath:", "Clear-win move:", "What to say:", "Do this:", "Don't do this:", "Options to consider")
            .forEach { assertTrue("missing $it in\n$text", text.contains(it)) }
        assertTrue(text.endsWith(PlainResult.FOOTER))
    }

    @Test fun TC_PROMPT_03_backwardCompatibleSketchWithoutCoachGuide() {
        val s = SketchParser.parse(Sketches.loanStory)!!
        assertNull(s.coachGuide); assertEquals(0.0, s.trustDelta, 0.0); assertNull(s.archetype)
        val s0 = Fx.loanFamily()
        assertFalse(PlainResult.build(s0, AnalysisPipeline().run(s0), emptyList(), null).contains("Clear-win move:"))
    }

    @Test fun TC_PROMPT_04_noBannedJargonInPrompts() {
        val coach = ArgonPrompts.INTERACTION_COACH_PROMPT
        listOf("Nash", "payoff matrix", "dominant strategy").forEach { assertTrue(coach.contains("\"$it\"")) }
        assertTrue(coach.contains("NEVER use academic game-theory jargon"))
        assertTrue(ArgonPrompts.JOURNAL_SCREENER_PROMPT.contains("NEVER judge, scold, lecture, diagnose, or criticize"))
        assertTrue(ArgonPrompts.JOURNAL_SCREENER_PROMPT.contains("Based on your journal entry"))
    }

    @Test fun argonSchemaFormIsConvertedToOps() {
        val json = """{"people":[{"name":"You","role":"player"},{"name":"Partner","role":"player","can_block":true,"power":7}],
            "options":[{"who":"You","do":"Pause"},{"who":"You","do":"Push"},{"who":"Partner","do":"Open up"},{"who":"Partner","do":"Withdraw"}],
            "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
            "outcomes":[{"when":["Pause","Open up"],"for_you":[8,9,9],"for_them":[8,8,8]}]}"""
        val ops = SketchConverter.toOps(SketchParser.parse(json)!!, com.drkkr.strategos.domain.model.Scenario(title = "t")).ops
        assertEquals(4, ops.count { it.type == OpType.ADD_ACTION })
        assertEquals(2, ops.count { it.type == OpType.SET_PAYOFF })
        assertEquals(1, ops.count { it.type == OpType.ADD_STAKEHOLDER && it.canVeto == true })
    }
}
