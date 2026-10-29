package com.drkkr.strategos

import com.drkkr.strategos.nlp.ScenarioParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the heuristic free-text extraction: stakeholders from role keywords,
 * actions from decision verbs, owner assignment, and clarifications.
 */
class ScenarioParserTest {

    private val sample = """
        Our hospital's psychiatry department head and the clinical director
        disagree on whether to adopt a new involuntary-hold protocol. The
        department head wants to pilot it; the clinical director fears legal
        and ethical fallout and could veto.
    """.trimIndent()

    @Test
    fun extractsStakeholdersFromRoleKeywords() {
        val r = ScenarioParser.parse(sample)
        val names = r.draft.stakeholders.map { it.name.lowercase() }
        assertTrue("expected a director stakeholder", names.any { it.contains("director") })
        assertTrue("expected a department/head stakeholder", names.any { it.contains("head") || it.contains("department") })
        // Possessive tokens must be stripped from inferred names.
        assertTrue("possessive leaked into a name", names.none { it.contains("'s") })
    }

    @Test
    fun extractsActionsFromDecisionVerbs() {
        val r = ScenarioParser.parse(sample)
        val labels = r.draft.actions.map { it.label.lowercase() }
        assertTrue("expected an adopt action", labels.any { it.startsWith("adopt") })
        assertTrue("expected a pilot action", labels.any { it.startsWith("pilot") })
        assertTrue("expected a veto action", labels.any { it.startsWith("veto") })
    }

    @Test
    fun assignsAtLeastOneActionOwner() {
        val r = ScenarioParser.parse(sample)
        val ids = r.draft.stakeholders.map { it.id }.toSet()
        assertTrue(
            "expected at least one action assigned to an inferred stakeholder",
            r.draft.actions.any { it.stakeholderId in ids }
        )
    }

    @Test
    fun inferredFieldsAreMarkedForReview() {
        val r = ScenarioParser.parse(sample)
        // Not a blocking assertion suite — just that the draft flags its origin.
        assertTrue(r.draft.assumptionLog.isNotEmpty())
        assertTrue(r.clarificationQuestions.isNotEmpty())
    }

    @Test
    fun shortTextAsksForMoreDetail() {
        val r = ScenarioParser.parse("help")
        assertTrue(r.clarificationQuestions.any { it.contains("more detail", ignoreCase = true) })
    }

    @Test
    fun emptyishTextProducesNoStakeholdersButStillParses() {
        val r = ScenarioParser.parse("We must decide soon.")
        assertEquals(0, r.draft.stakeholders.size)
        assertTrue(r.clarificationQuestions.any { it.contains("stakeholders", ignoreCase = true) })
    }
}
