package com.drkkr.strategos

import com.drkkr.strategos.domain.game.MechanismAdvisor
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.*
import org.junit.Test

class MechanismAdvisorTest {

    private fun scenario(title: String = "", description: String = "") =
        Scenario(title = title, description = description)

    @Test fun `returns null for generic scenario`() {
        val result = MechanismAdvisor.detect(scenario("Salary raise conversation", "I want a raise"))
        assertNull(result)
    }

    @Test fun `detects auction from title`() {
        val result = MechanismAdvisor.detect(scenario("Procurement auction design", "We need to design bidding rules"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.AUCTION, result!!.type)
    }

    @Test fun `detects auction from description keyword`() {
        val result = MechanismAdvisor.detect(scenario("Vendor selection", "We are running a tender for the contract"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.AUCTION, result!!.type)
    }

    @Test fun `detects matching with design trigger`() {
        val result = MechanismAdvisor.detect(scenario("Staff placement", "How should we design the matching of employees to projects"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.MATCHING, result!!.type)
    }

    @Test fun `matching without design trigger returns null`() {
        val result = MechanismAdvisor.detect(scenario("My interview", "I want to match with a good company"))
        assertNull(result)
    }

    @Test fun `detects contract incentive design`() {
        val result = MechanismAdvisor.detect(scenario("Sales compensation design", "We need to design incentives for the sales team bonus structure"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.CONTRACT, result!!.type)
    }

    @Test fun `detects voting rule design`() {
        val result = MechanismAdvisor.detect(scenario("Committee decision", "What voting rule should we use for committee decisions"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.VOTING, result!!.type)
    }

    @Test fun `detects fair division with design trigger`() {
        val result = MechanismAdvisor.detect(scenario("Budget allocation", "We need to design how to allocate budget fairly across teams"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.ALLOCATION, result!!.type)
    }

    @Test fun `hint has non-blank insight and recommendation`() {
        val result = MechanismAdvisor.detect(scenario("Sealed bid auction", "Design the bidding rules"))
        assertNotNull(result)
        assertTrue(result!!.insight.isNotBlank())
        assertTrue(result.recommendation.isNotBlank())
    }

    @Test fun `case insensitive matching`() {
        val result = MechanismAdvisor.detect(scenario("AUCTION DESIGN", "PROCUREMENT TENDER"))
        assertNotNull(result)
        assertEquals(MechanismAdvisor.MechanismType.AUCTION, result!!.type)
    }
}
