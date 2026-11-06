package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.game.RubricConstants
import com.drkkr.strategos.domain.game.RubricPayoffs
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID1.1: Call 1 writes a label per cell, player and dimension, and Kotlin turns it into the number. */
class RubricPayoffsTest {
    private val R = PayoffDimension.RELATIONSHIPS
    private val W = PayoffDimension.WELLBEING
    private val C = PayoffDimension.COST

    /** You: Talk calmly / Send angry texts; Sam: Listen / Walk off; dims [RELATIONSHIPS, WELLBEING, COST]. */
    private fun sketch(cells: List<Pair<String, String>>, postures: Boolean = false): String {
        fun p(x: String) = if (postures) ""","posture":"$x"""" else ""
        val picks = listOf("Talk calmly" to "Listen", "Talk calmly" to "Walk off", "Send angry texts" to "Listen", "Send angry texts" to "Walk off")
        val outcomes = picks.zip(cells).joinToString(",") { (pk, c) -> """{"when":["${pk.first}","${pk.second}"],"for_you":${c.first},"for_them":${c.second}}""" }
        return """{"people":[{"name":"You","role":"PLAYER"},{"name":"Sam","role":"PLAYER"}],
            "options":[{"for":"You","label":"Talk calmly"${p("repair")}},{"for":"You","label":"Send angry texts"${p("reactive")}},
                       {"for":"Sam","label":"Listen"${p("receptive")}},{"for":"Sam","label":"Walk off"${p("defensive")}}],
            "dims":["RELATIONSHIPS","WELLBEING","COST"],"outcomes":[$outcomes]}"""
    }

    private val labelled = listOf(
        """["++","+","0"]""" to """["+","+","0"]""",
        """["-","0","0"]""" to """["0","+","-"]""",
        """["--","-","-"]""" to """["-","-","--"]""",
        """["--","--","--"]""" to """["-","0","-"]"""
    )
    private val numeric = listOf(
        "[8.5,6.5,5]" to "[6.5,6.5,5]", "[3.5,5,5]" to "[5,6.5,6.5]", "[1.5,3.5,6.5]" to "[3.5,3.5,8.5]", "[1.5,1.5,8.5]" to "[3.5,5,6.5]"
    )

    private fun payoffs(json: String): List<ChangeOp> = SketchConverter.toOps(SketchParser.parse(json)!!, Scenario(title = "t")).ops.filter { it.type == OpType.SET_PAYOFF }
    private fun cell(ops: List<ChangeOp>, who: String, you: String, sam: String): Map<PayoffDimension, Double> =
        ops.single { op -> op.stakeholder == who && op.profile!!.map { it.action }.toSet() == setOf(you, sam) }.values!!.associate { it.dimension to it.value }

    @Test fun TC_ID_RUB_01_labelsWordsNumbersAndAnythingElse() {
        val steps = listOf("++" to 2, "+" to 1, "0" to 0, "-" to -1, "--" to -2)
        steps.forEach { (l, n) -> assertEquals(l, n, RubricPayoffs.level(JsonPrimitive(l))!!.step) }
        listOf("much_better" to 2, "better" to 1, "same" to 0, "worse" to -1, "much_worse" to -2, " Much_Better " to 2, "−" to -1, "– –" to null)
            .forEach { (w, n) -> assertEquals(w, n, RubricPayoffs.level(JsonPrimitive(w))?.step) }
        assertEquals(RubricPayoffs.Cell.Numeric(7.5), RubricPayoffs.cell(JsonPrimitive(7.5)))
        assertNull(RubricPayoffs.level(JsonPrimitive(7.5)))
        assertEquals(RubricPayoffs.Cell.Missing, RubricPayoffs.cell(JsonPrimitive("maybe")))
        assertNull(RubricPayoffs.level(JsonPrimitive("maybe")))
        // provenance: a numeric reply is marked, a labelled one is not
        assertTrue(payoffs(sketch(numeric)).all { RubricPayoffs.AI_SCORED_NUMERIC in it.rationale.orEmpty() })
        assertFalse(payoffs(sketch(labelled)).any { RubricPayoffs.AI_SCORED_NUMERIC in it.rationale.orEmpty() })
        // a cell that is neither: that player's entry for that outcome is missing (left to the proxy fill), the rest is kept
        val withMaybe = payoffs(sketch(listOf("""["++","maybe","0"]""" to """["+","+","0"]""") + labelled.drop(1)))
        assertEquals(7, withMaybe.size)
        assertTrue(withMaybe.none { op -> op.stakeholder == "you" && op.profile!!.map { it.action }.toSet() == setOf("you_talk_calmly", "sam_listen") })
    }

    @Test fun TC_ID_RUB_02_muchBetterAndMuchWorse() {
        assertEquals(8.5, RubricPayoffs.value(RubricPayoffs.Level.MUCH_BETTER, R), 1e-9)
        assertEquals(1.5, RubricPayoffs.value(RubricPayoffs.Level.MUCH_WORSE, W), 1e-9)
        val you = cell(payoffs(sketch(labelled)), "you", "you_talk_calmly", "sam_listen")
        assertEquals(8.5, you.getValue(R), 1e-9)
        assertEquals(6.5, you.getValue(W), 1e-9)
        assertEquals(5.0, you.getValue(C), 1e-9)
        assertEquals(1.5, cell(payoffs(sketch(labelled)), "you", "you_send_angry_texts", "sam_walk_off").getValue(W), 1e-9)
    }

    @Test fun TC_ID_RUB_03_costIsFlipped() {
        assertEquals(1.5, RubricPayoffs.value(RubricPayoffs.Level.MUCH_BETTER, C), 1e-9)
        assertEquals(8.5, RubricPayoffs.value(RubricPayoffs.Level.MUCH_WORSE, C), 1e-9)
        val w = mapOf(C to 1.0)
        assertTrue(MultiPayoff(mapOf(C to 1.5)).weighted(w) > MultiPayoff(mapOf(C to 8.5)).weighted(w))
        // Sam's "--" on cost (much worse for Sam) is a high cost
        assertEquals(8.5, cell(payoffs(sketch(labelled)), "sam", "you_send_angry_texts", "sam_listen").getValue(C), 1e-9)
        // each constant carries its source note
        RubricConstants.DEFAULT.notes.values.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(5, RubricConstants.DEFAULT.notes.size)
    }

    @Test fun TC_ID_RUB_04_sameShapeAsTheNumericPathAndPostureContrastStillApplies() {
        val fromLabels = payoffs(sketch(labelled))
        val fromNumbers = payoffs(sketch(numeric))
        assertEquals(8, fromLabels.size)
        fun shape(ops: List<ChangeOp>) = ops.map { op -> Triple(op.stakeholder, op.profile!!.toSet(), op.values!!.map { it.dimension }) }.toSet()
        assertEquals(shape(fromNumbers), shape(fromLabels))
        // the numeric twin was written as the same steps, so the values agree too
        fromLabels.forEach { op ->
            val twin = fromNumbers.single { it.stakeholder == op.stakeholder && it.profile!!.toSet() == op.profile!!.toSet() }
            assertEquals(twin.values, op.values)
        }
        // PostureContrast runs after the conversion: a reactive move's RELATIONSHIPS is capped at 2 for You
        val tagged = payoffs(sketch(labelled.mapIndexed { i, c -> if (i == 2) """["++","-","-"]""" to c.second else c }, postures = true))
        assertEquals(2.0, cell(tagged, "you", "you_send_angry_texts", "sam_listen").getValue(R), 1e-9)
        // and nothing touches the weights
        assertTrue(SketchConverter.toOps(SketchParser.parse(sketch(labelled))!!, Scenario(title = "t")).ops.none { "WEIGHT" in it.type.name })
    }
}
