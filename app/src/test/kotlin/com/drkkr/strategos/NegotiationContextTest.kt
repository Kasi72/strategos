package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
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
}
