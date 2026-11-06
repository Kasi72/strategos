package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.framework.Applicability
import com.drkkr.strategos.domain.framework.CalculationStatus
import com.drkkr.strategos.domain.framework.FrameworkCategory
import com.drkkr.strategos.domain.framework.RecommendedAction
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.recommendation.RecommendationEngine
import com.drkkr.strategos.ui.calm.talk.looksLikeNegotiation
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NegotiationContextTest {

    private fun scenario(nc: NegotiationContext? = null) = Scenario(
        title = "Salary negotiation",
        description = "Negotiating a raise with my manager.",
        negotiationContext = nc
    )

    @Test fun `NegotiationContext defaults are blank`() {
        val nc = NegotiationContext()
        assertFalse(nc.hasContent())
    }

    @Test fun `hasContent true when batna set`() {
        val nc = NegotiationContext(batna = "Take other offer")
        assertTrue(nc.hasContent())
    }

    @Test fun `hasContent true when aspiration set`() {
        assertTrue(NegotiationContext(aspirationPoint = "20% raise").hasContent())
    }

    @Test fun `hasContent true when openingAnchor set`() {
        assertTrue(NegotiationContext(openingAnchor = "25% raise").hasContent())
    }

    @Test fun `LiveDigest omits negotiation block when null`() {
        val digest = LiveDigest.build(scenario(null))
        assertNull(digest["negotiation"])
    }

    @Test fun `LiveDigest omits negotiation block when empty`() {
        val digest = LiveDigest.build(scenario(NegotiationContext()))
        assertNull(digest["negotiation"])
    }

    @Test fun `LiveDigest emits negotiation block with content`() {
        val nc = NegotiationContext(
            batna = "Take competing offer",
            aspirationPoint = "20% raise",
            reservationPoint = "10% raise",
            openingAnchor = "25% raise",
            zopaNote = "Probably 12-18%",
            counterpartBatna = "Lose me",
            counterpartReservation = "8% raise"
        )
        val digest = LiveDigest.build(scenario(nc))
        val neg = digest["negotiation"]?.jsonObject
        assertNotNull(neg)
        assertEquals("Take competing offer", neg!!["batna"]?.jsonPrimitive?.content)
        assertEquals("20% raise", neg["aspiration"]?.jsonPrimitive?.content)
        assertEquals("10% raise", neg["reservation"]?.jsonPrimitive?.content)
        assertEquals("25% raise", neg["opening_anchor"]?.jsonPrimitive?.content)
        assertEquals("Probably 12-18%", neg["zopa"]?.jsonPrimitive?.content)
        assertEquals("Lose me", neg["counterpart_batna"]?.jsonPrimitive?.content)
        assertEquals("8% raise", neg["counterpart_reservation"]?.jsonPrimitive?.content)
    }

    @Test fun `LiveDigest truncates long negotiation fields`() {
        val longText = "x".repeat(300)
        val nc = NegotiationContext(batna = longText, aspirationPoint = "target")
        val neg = LiveDigest.build(scenario(nc))["negotiation"]?.jsonObject
        assertNotNull(neg)
        assertEquals(200, neg!!["batna"]?.jsonPrimitive?.content?.length)
    }

    @Test fun `looksLikeNegotiation detects keywords`() {
        assertTrue(looksLikeNegotiation("I am negotiating a salary raise with my manager."))
        assertTrue(looksLikeNegotiation("Contract renewal discussion"))
        assertTrue(looksLikeNegotiation("job offer decision"))
        assertFalse(looksLikeNegotiation("I had a fight with my partner."))
        assertFalse(looksLikeNegotiation(""))
    }

    @Test fun `NegotiationContext is serializable via Scenario JSON round-trip`() {
        val nc = NegotiationContext(batna = "Walk away", aspirationPoint = "Full ask")
        val s = scenario(nc)
        val json = kotlinx.serialization.json.Json.encodeToString(Scenario.serializer(), s)
        val s2 = kotlinx.serialization.json.Json.decodeFromString(Scenario.serializer(), json)
        assertEquals(nc, s2.negotiationContext)
    }

    // ── RecommendationEngine × NegotiationContext ─────────────────────────────

    @Test fun `identifyMissing flags absent negotiation context for negotiation-like scenario`() {
        val s = scenario(nc = null)
        val report = RecommendationEngine().combine(s, emptyList())
        assertTrue(
            "missingInfo must flag absent negotiation context",
            report.missingInfo.any { it.description == "No negotiation context entered" }
        )
    }

    @Test fun `identifyMissing no flag when negotiation context already present`() {
        val s = scenario(NegotiationContext(aspirationPoint = "20% raise"))
        val report = RecommendationEngine().combine(s, emptyList())
        assertFalse(
            "missingInfo must NOT flag negotiation context when it is already set",
            report.missingInfo.any { it.description == "No negotiation context entered" }
        )
    }

    @Test fun `concession-signaling action scores lower when reservation set`() {
        val p1 = Stakeholder("p1", "Alice")
        val p2 = Stakeholder("p2", "Bob")
        val normalAction = Action("a1", "p1", "Negotiate hard for 20% raise")
        val concessionAction = Action("a2", "p1", "Accept current offer")
        val nc = NegotiationContext(reservationPoint = "10% raise")
        val s = Scenario(
            title = "Salary negotiation",
            description = "Annual review",
            stakeholders = listOf(p1, p2),
            actions = listOf(normalAction, concessionAction),
            negotiationContext = nc
        )
        val result = AnalysisResult(
            moduleId = "test", moduleName = "Test",
            applicability = Applicability(0.8, emptyList()),
            findings = emptyList(),
            recommendedActions = listOf(
                RecommendedAction(null, normalAction.label, "good move", actionId = "a1"),
                RecommendedAction(null, concessionAction.label, "fallback", actionId = "a2")
            ),
            assumptions = emptyList(), calculations = emptyMap(),
            confidence = 0.8, visualizations = emptyList(), explanation = "",
            calculationStatus = CalculationStatus.PROXY_ESTIMATE,
            frameworkCategory = FrameworkCategory.SUPPORTING
        )
        val candidates = RecommendationEngine().buildCandidates(s, listOf(result))
        val normalScore = candidates.first { it.actionId == "a1" }.score
        val concessionScore = candidates.first { it.actionId == "a2" }.score
        assertTrue("Concession action must score lower than normal action", concessionScore < normalScore)
    }

    @Test fun `Recommended pathway rationale mentions aspiration when negotiationContext set`() {
        val p1 = Stakeholder("p1", "Alice")
        val p2 = Stakeholder("p2", "Bob")
        val action1 = Action("a1", "p1", "Request raise meeting")
        val action2 = Action("a2", "p2", "Approve budget")
        val nc = NegotiationContext(aspirationPoint = "20% raise by Q1")
        val s = Scenario(
            title = "Salary negotiation",
            description = "Annual review",
            stakeholders = listOf(p1, p2),
            actions = listOf(action1, action2),
            negotiationContext = nc
        )
        val result = AnalysisResult(
            moduleId = "test", moduleName = "Test",
            applicability = Applicability(0.8, emptyList()),
            findings = emptyList(),
            recommendedActions = listOf(
                RecommendedAction(null, action1.label, "primary", actionId = "a1")
            ),
            assumptions = emptyList(), calculations = emptyMap(),
            confidence = 0.8, visualizations = emptyList(), explanation = "",
            calculationStatus = CalculationStatus.PROXY_ESTIMATE,
            frameworkCategory = FrameworkCategory.SUPPORTING
        )
        val report = RecommendationEngine().combine(s, listOf(result))
        val recommended = report.pathways.firstOrNull { it.label == "Recommended" }
        assertNotNull("Recommended pathway must exist", recommended)
        assertTrue(
            "Rationale must mention aspiration point",
            recommended!!.rationale.contains("20% raise by Q1")
        )
    }
}
