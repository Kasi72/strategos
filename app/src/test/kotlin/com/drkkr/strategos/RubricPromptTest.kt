package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.game.RubricPayoffs
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID1.2: Call 1 is asked for labels, not 0–10 scores. */
class RubricPromptTest {
    private val p = ArgonPrompts.EXTRACTION_PROMPT

    @Test fun TC_ID_RUB_10_thePromptAsksForLabels() {
        assertTrue(p.contains("say whether this combination leaves THEM much better, better, the same as today, worse or much worse"))
        assertTrue(p.contains("Judge each player from their own side."))
        assertFalse("the 0–10 scale sentence is gone", p.contains("one number 0 to 10"))
        val example = SketchParser.parse(p.substring(p.indexOf("JSON SCHEMA:") + "JSON SCHEMA:".length))!!
        assertEquals("the example keeps its 6 outcomes", 6, example.outcomes.size)
        val cells = example.outcomes.flatMap { it.rawForYou + it.rawForThem }
        assertEquals(36, cells.size)
        assertTrue("every example cell is a label: $cells", cells.all { RubricPayoffs.level(it) != null })
    }

    /** I-D5: the "fill in what's missing" pass (OneShotTurn.PROMPT) is Call 1 too, so it asks for labels as well. */
    @Test fun TC_ID_RUB_10_theRepairPassPromptAsksForLabelsToo() {
        val r = com.drkkr.strategos.agent.OneShotTurn.PROMPT
        assertTrue(r.contains("give each player one label per dim"))
        assertTrue(r.contains("Judge each player from their own side"))
        assertFalse(r.contains("numbers 0-10"))
        assertFalse(r.contains("COST 10 = very costly"))
        val example = SketchParser.parse(r.substring(r.indexOf("(a different situation):")))!!
        val cells = example.outcomes.flatMap { o -> o.rawScores.values.flatten() }
        assertEquals(24, cells.size)
        assertTrue("every example cell is a label: $cells", cells.all { RubricPayoffs.level(it) != null })
    }

    /** Recorded live 2026-11-06 (salary story, first message, the id1.2 prompt): resources/intent/salary_turn1_live.json. */
    @Test fun TC_ID_RUB_11_aLiveExtractionConvertsWithNoMissingCells() {
        val sk = SketchParser.parse(javaClass.getResource("/intent/salary_turn1_live.json")!!.readText())!!
        val dims = sk.dims.map { d -> PayoffDimension.valueOf(d) }
        assertEquals(listOf(PayoffDimension.COST, PayoffDimension.LONG_TERM_VALUE, PayoffDimension.RELATIONSHIPS), dims)
        val numbers = SketchConverter.numbers(sk.outcomes, dims)
        // every cell is a label, and each converts to exactly its mapping
        sk.outcomes.zip(numbers).forEach { (raw, n) ->
            for ((cells, values) in listOf(raw.rawForYou to n.forYou, raw.rawForThem to n.forThem)) {
                assertEquals(dims.size, values.size)
                cells.zip(dims).zip(values).forEach { (cd, v) -> assertEquals(RubricPayoffs.value(RubricPayoffs.level(cd.first)!!, cd.second), v, 1e-9) }
            }
            assertFalse(n.numeric)
        }
        // saved: one payoff per player per outcome, none missing, none marked numeric
        val ops = SketchConverter.toOps(sk, Scenario(title = "t")).ops.filter { it.type == OpType.SET_PAYOFF }
        assertEquals(12, ops.size)
        assertTrue(ops.all { it.values!!.size == 3 && RubricPayoffs.AI_SCORED_NUMERIC !in it.rationale.orEmpty() })
        // a cell no later adjustment touches (their receptive reply to your reactive move) is the label mapping as saved:
        // ["-","0","-"] on [COST, LONG_TERM_VALUE, RELATIONSHIPS] = 10 - 3.5, 5.0, 3.5
        val emp = ops.single { op -> op.stakeholder == "current_employer" && op.profile!!.any { it.action.startsWith("you_say_you_ll_resign") } && op.profile!!.any { it.action.startsWith("current_employer_engage") } }
        assertEquals(listOf(6.5, 5.0, 3.5), emp.values!!.map { it.value })
    }
}
