package com.drkkr.strategos

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.flywheel.PatternSynthesizer
import org.junit.Assert.*
import org.junit.Test

class PatternSynthesizerTest {

    private fun record(
        rating: Int,
        recommended: String = "ask",
        actual: String = "ask",
        reaction: String? = "partner",
        archetype: String? = null
    ) = OutcomeRecord(
        scenarioId = "s1",
        recommendedMove = recommended,
        actualMove = actual,
        outcomeRating = rating,
        opponentReaction = reaction,
        archetypeName = archetype,
        recordedAt = System.currentTimeMillis()
    )

    @Test fun `returns null for empty list`() {
        assertNull(PatternSynthesizer.synthesize(emptyList(), "partner"))
    }

    @Test fun `returns null for blank opponentId`() {
        val records = listOf(record(4), record(3))
        assertNull(PatternSynthesizer.synthesize(records, ""))
    }

    @Test fun `returns null for fewer than 2 records`() {
        assertNull(PatternSynthesizer.synthesize(listOf(record(4)), "partner"))
    }

    @Test fun `computes average rating`() {
        val records = listOf(record(4), record(2), record(5))
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertEquals((4 + 2 + 5).toDouble() / 3, result!!.avgRating, 0.01)
    }

    @Test fun `computes follow rate correctly`() {
        val records = listOf(
            record(4, "ask", "ask"),
            record(3, "wait", "ask"),
            record(5, "ask", "ask")
        )
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertEquals(2.0 / 3.0, result!!.followRate, 0.01)
    }

    @Test fun `dominant archetype requires at least 2 occurrences`() {
        val records = listOf(
            record(4, archetype = "Chicken"),
            record(3, archetype = "PD"),
        )
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertNull(result!!.dominantArchetype)
    }

    @Test fun `dominant archetype detected when repeated`() {
        val records = listOf(
            record(4, archetype = "Chicken"),
            record(3, archetype = "Chicken"),
            record(5, archetype = "PD"),
        )
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertEquals("Chicken", result!!.dominantArchetype)
    }

    @Test fun `insight is non-blank`() {
        val records = listOf(record(5), record(4))
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertTrue(result!!.insight.isNotBlank())
    }

    @Test fun `insight reflects high rating`() {
        val records = listOf(record(5), record(5))
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertTrue(result!!.insight.contains("go well"))
    }

    @Test fun `insight reflects low rating`() {
        val records = listOf(record(1), record(2))
        val result = PatternSynthesizer.synthesize(records, "partner")
        assertNotNull(result)
        assertTrue(result!!.insight.contains("poor"))
    }

    @Test fun `synthesizeAll groups by opponentReaction`() {
        val records = listOf(
            record(4, reaction = "boss"),
            record(3, reaction = "boss"),
            record(5, reaction = "partner"),
            record(4, reaction = "partner"),
        )
        val patterns = PatternSynthesizer.synthesizeAll(records)
        assertEquals(2, patterns.size)
        assertTrue(patterns.any { it.opponentId == "boss" })
        assertTrue(patterns.any { it.opponentId == "partner" })
    }
}
