package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.XRayArenaBuilder
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

class XRayArenaTest {
    private val loan = Fx.loanFamily()

    @Test fun TC_ARENA_01_youTheCounterpartAndTheStrongestOutsider() {
        val a = XRayArenaBuilder.from(loan, trust = 58.4, tension = 46.0)!!
        assertEquals("You", a.you.name)
        assertEquals("Brother", a.them.name)
        assertEquals("an influencer outranks a bystander; the stronger one wins", "Parents", a.influencer?.name)
        assertEquals(58, a.trust); assertEquals(46, a.tension)
    }

    @Test fun TC_ARENA_02_cardsOnlyCarryWhatTheScenarioHolds() {
        val a = XRayArenaBuilder.from(loan)!!
        assertEquals(listOf("You want" to "Help without risking our savings"), a.you.lines.map { it.label to it.value })
        assertEquals(listOf("Wants" to "Get the loan", "Worried about" to "Losing face"), a.them.lines.map { it.label to it.value })
        assertTrue("the user's own facts carry no Inferred badge", a.them.lines.none { it.inferred })
        assertNull("never measured means no gauge, not a made-up number", a.trust)
        assertNull(a.tension)
    }

    @Test fun TC_ARENA_03_floorAndTargetComeFromTheDealFrame() {
        val a = XRayArenaBuilder.from(loan.copy(negotiationContext = NegotiationContext(reservationPoint = "27L", aspirationPoint = "29L")))!!
        assertEquals(listOf("Floor" to "27L", "Target" to "29L"), a.you.lines.map { it.label to it.value })
    }

    @Test fun TC_ARENA_04_trustFallsBackToTheCounterpartsBaseline_andReadingsAreClamped() {
        val s = loan.copy(stakeholders = loan.stakeholders.map { if (it.id == "bro") it.copy(trustBaseline = 0.7) else it })
        assertEquals(70, XRayArenaBuilder.from(s)!!.trust)
        val a = XRayArenaBuilder.from(loan, trust = 140.0, tension = -3.0)!!
        assertEquals(100, a.trust); assertEquals(0, a.tension)
    }

    @Test fun TC_ARENA_05_noCounterpartMeansNoArena() {
        assertNull(XRayArenaBuilder.from(Scenario(title = "empty")))
    }
}
