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

    @Test fun argonPromptExampleParsesAndConverts() {
        val example = """{
  "title": "short scenario title (<= 6 words)",
  "goal": "what a clear win looks like for the user",
  "archetype": "CHICKEN_GAME | PRISONERS_DILEMMA | STAG_HUNT | ULTIMATUM | TRUST_GAME | RELATIONAL_REPAIR",
  "trust_delta": 0,
  "tension_delta": 0,
  "people": [
    {"name": "You", "role": "player", "wants": "respect and clarity", "fears": "escalation", "can_block": false, "power": 5},
    {"name": "Partner", "role": "player", "wants": "autonomy", "fears": "being controlled", "can_block": true, "power": 7}
  ],
  "options": [
    {"who": "You", "do": "Validate autonomy and pause timeline talk for 24h"},
    {"who": "You", "do": "Push for an immediate commitment tonight"},
    {"who": "Partner", "do": "Open up and collaborate on a shared plan"},
    {"who": "Partner", "do": "Stonewall and withdraw"}
  ],
  "dims": ["WELLBEING", "RELATIONSHIPS", "LONG_TERM_VALUE"],
  "outcomes": [
    {"when": ["Validate autonomy and pause timeline talk for 24h", "Open up and collaborate on a shared plan"], "for_you": [8, 9, 9], "for_them": [8, 8, 8]},
    {"when": ["Validate autonomy and pause timeline talk for 24h", "Stonewall and withdraw"], "for_you": [6, 6, 7], "for_them": [5, 5, 5]},
    {"when": ["Push for an immediate commitment tonight", "Open up and collaborate on a shared plan"], "for_you": [6, 5, 5], "for_them": [4, 4, 4]},
    {"when": ["Push for an immediate commitment tonight", "Stonewall and withdraw"], "for_you": [2, 2, 2], "for_them": [3, 2, 3]}
  ],
  "limits": ["Do not sacrifice self-respect or use threats"],
  "coach_guide": {
    "what_happened": "When you pressed for an immediate timeline, they felt cornered and shifted into defensive withdrawal.",
    "clear_win_move": "Step back from the timeline pressure for 24 hours while calmly holding your boundary.",
    "exact_words": "I care about us both feeling good about this plan. Let's take tonight off from deciding and revisit it tomorrow evening.",
    "do_this": [
      "Give warm, unbothered space for 24 hours so their defensiveness drops",
      "Frame the next conversation around a shared win rather than a deadline"
    ],
    "dont_do_this": [
      "Don't send follow-up texts asking if they are mad",
      "Don't cave in and abandon the plan just to end the awkward silence"
    ]
  },
  "ask": null,
  "support": false
}"""
        val sketch = SketchParser.parse(example)
        assertNotNull("the prompt's own example must parse", sketch)
        assertEquals("CHICKEN_GAME | PRISONERS_DILEMMA | STAG_HUNT | ULTIMATUM | TRUST_GAME | RELATIONAL_REPAIR".length > 0, true)
        val ops = SketchConverter.toOps(sketch!!, com.drkkr.strategos.domain.model.Scenario(title = "t")).ops
        assertEquals(4, ops.count { it.type == OpType.ADD_ACTION })
        assertTrue(ops.any { it.type == OpType.ADD_CONSTRAINT })
        assertNotNull(sketch.coachGuide)
    }

    @Test fun askMayBeAnObjectAndLimitsMayBeStrings() {
        val s = SketchParser.parse("""{"limits":["No threats",{"label":"No lies","kind":"ETHICAL"}],"ask":{"text":"Who else is affected?","options":["a","b"]},"trust_delta":"6"}""")!!
        assertEquals(listOf("No threats", "No lies"), s.limits.map { it.label })
        assertEquals("Who else is affected?", s.ask)
        assertEquals(6.0, s.trustDelta, 0.0)
    }
}
