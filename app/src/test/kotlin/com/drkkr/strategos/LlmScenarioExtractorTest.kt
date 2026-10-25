package com.drkkr.strategos

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.nlp.LlmScenarioExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure, network-free parts of the LLM extractor: mapping extracted
 * JSON to a domain Scenario, fence stripping, and pulling assistant text out of
 * a Messages API response body. The live HTTP call is not exercised here.
 */
class LlmScenarioExtractorTest {

    private val extraction = """
        {
          "title": "Protocol adoption dispute",
          "description": "A dept head and clinical director disagree on a new protocol.",
          "stakeholders": [
            {"name": "Department Head", "role": "Psychiatry lead", "authority": 7, "influence": 6, "canVeto": false, "goals": ["Pilot the protocol"]},
            {"name": "Clinical Director", "role": "Clinical oversight", "authority": 12, "influence": 7, "canVeto": true, "goals": ["Avoid legal risk"]}
          ],
          "actions": [
            {"label": "Pilot the protocol", "owner": "Department Head", "description": "Run a limited trial"},
            {"label": "Veto the protocol", "owner": "Clinical Director", "description": ""}
          ],
          "constraints": [
            {"label": "Patient safety", "kind": "SAFETY"},
            {"label": "Involuntary-hold legality", "kind": "LEGAL"}
          ],
          "beliefs": [
            {"label": "Regulator approves", "probability": 0.4}
          ]
        }
    """.trimIndent()

    @Test
    fun mapsStakeholdersActionsConstraintsAndBeliefs() {
        val s = LlmScenarioExtractor.mapExtractionJson(extraction, rawText = "irrelevant")!!
        assertEquals("Protocol adoption dispute", s.title)
        assertEquals(2, s.stakeholders.size)
        assertEquals(2, s.actions.size)
        assertEquals(2, s.constraints.size)
        assertEquals(1, s.beliefs.size)

        val director = s.stakeholders.first { it.name == "Clinical Director" }
        assertTrue("veto flag should map", director.canVeto)
        assertEquals("authority must be clamped to 0..10", 10, director.authority)

        assertEquals(Constraint.Kind.SAFETY, s.constraints[0].kind)
        assertEquals(Constraint.Kind.LEGAL, s.constraints[1].kind)
        assertEquals(FactStatus.AI_INFERRED, s.beliefs[0].status)
        assertEquals(0.4, s.beliefs[0].probability, 1e-9)
    }

    @Test
    fun linksActionOwnersToStakeholderIds() {
        val s = LlmScenarioExtractor.mapExtractionJson(extraction, rawText = "x")!!
        val headId = s.stakeholders.first { it.name == "Department Head" }.id
        val dirId = s.stakeholders.first { it.name == "Clinical Director" }.id
        assertEquals(headId, s.actions.first { it.label == "Pilot the protocol" }.stakeholderId)
        assertEquals(dirId, s.actions.first { it.label == "Veto the protocol" }.stakeholderId)
    }

    @Test
    fun stripsMarkdownFencesAroundJson() {
        val fenced = "```json\n$extraction\n```"
        val s = LlmScenarioExtractor.mapExtractionJson(fenced, rawText = "x")
        assertTrue(s != null && s.stakeholders.size == 2)
    }

    @Test
    fun unknownConstraintKindFallsBackToPolicy() {
        val json = """{"title":"t","stakeholders":[{"name":"A"}],"constraints":[{"label":"Odd","kind":"NONSENSE"}]}"""
        val s = LlmScenarioExtractor.mapExtractionJson(json, rawText = "x")!!
        assertEquals(Constraint.Kind.POLICY, s.constraints[0].kind)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(LlmScenarioExtractor.mapExtractionJson("not json at all", rawText = "x"))
    }

    @Test
    fun emptyExtractionReturnsNull() {
        assertNull(LlmScenarioExtractor.mapExtractionJson("""{"stakeholders":[],"actions":[],"title":""}""", rawText = "x"))
    }

    @Test
    fun extractsAssistantTextFromMessagesResponse() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"{\"title\":\"ok\"}"}],
             "stop_reason":"end_turn"}
        """.trimIndent()
        assertEquals("{\"title\":\"ok\"}", LlmScenarioExtractor.extractAssistantText(body))
    }

    @Test
    fun extractAssistantTextReturnsNullOnGarbage() {
        assertNull(LlmScenarioExtractor.extractAssistantText("<html>error</html>"))
    }
}
