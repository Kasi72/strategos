package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.CrossProjectPeople
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.*
import org.junit.Test

class CrossProjectPeopleTest {

    private fun project(id: String, title: String, brother: Stakeholder) = Scenario(
        id = id, title = title,
        stakeholders = listOf(Stakeholder(id = "you", name = "You"), brother)
    )

    private val knownBrother = Stakeholder(id = "b", name = "Brother", goals = listOf("Get the loan"), fears = listOf("Losing face"), role = "the asker")
    private val blankBrother = Stakeholder(id = "b2", name = "Brother")

    @Test fun TC_CARRY_01_suggestsAFamiliarButBlankPerson() {
        val target = project("t", "Family holiday", blankBrother)
        val source = project("s", "Loan for my brother", knownBrother)
        val suggestions = CrossProjectPeople.suggestions(target, listOf(source))
        assertEquals(1, suggestions.size)
        assertEquals("Brother", suggestions.first().name)
        assertEquals("s", suggestions.first().sourceScenarioId)
    }

    @Test fun TC_CARRY_02_noSuggestionWhenTargetAlreadyKnowsThePerson() {
        val target = project("t", "Family holiday", knownBrother.copy(id = "b3"))
        val source = project("s", "Loan", knownBrother)
        assertTrue(CrossProjectPeople.suggestions(target, listOf(source)).isEmpty())
    }

    @Test fun TC_CARRY_03_bringOverCopiesFlaggedForReview() {
        val target = project("t", "Family holiday", blankBrother)
        val source = project("s", "Loan for my brother", knownBrother)
        val after = CrossProjectPeople.bringOver(target, source, "Brother")
        val bro = after.stakeholders.first { it.name == "Brother" }
        assertEquals(listOf("Get the loan"), bro.goals)
        assertEquals(listOf("Losing face"), bro.fears)
        assertEquals("the asker", bro.role)
        assertEquals(FactStatus.AI_INFERRED, bro.factStatus)             // flagged for review, not silently filed
        assertTrue(bro.provenanceNote.contains("Loan for my brother"))
    }

    @Test fun TC_CARRY_04_brainsStaySeparate_targetKeepsItsOwnId() {
        val target = project("t", "Family holiday", blankBrother)
        val source = project("s", "Loan", knownBrother)
        val after = CrossProjectPeople.bringOver(target, source, "Brother")
        assertEquals("b2", after.stakeholders.first { it.name == "Brother" }.id)   // same person node, not the source's
    }
}
