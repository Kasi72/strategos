package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ExtractionVerifier
import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionVerifierTest {

    private fun scenario(repeated: Boolean = false, actionLabels: List<String> = listOf("Push", "Hold")): Scenario {
        val userId = "user"
        val oppId = "opp"
        val actions = actionLabels.mapIndexed { i, label ->
            Action(id = "u$i", stakeholderId = userId, label = label, reversible = true, publicVisibility = true)
        } + listOf(
            Action(id = "o0", stakeholderId = oppId, label = "Accept", reversible = true, publicVisibility = true)
        )
        return Scenario(
            id = "s1",
            title = "Test",
            stakeholders = listOf(
                Stakeholder(id = userId, name = "Me", gameRole = GameRole.PLAYER),
                Stakeholder(id = oppId, name = "Them", gameRole = GameRole.PLAYER)
            ),
            actions = actions,
            payoffs = emptyList(),
            repeated = repeated
        )
    }

    @Test
    fun noWarningForNormalScenario() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(repeated = false),
            archetype = GameArchetype.PRISONERS_DILEMMA,
            stressVerdict = "ROBUST",
            payoffsFilled = 8,
            payoffsExpected = 8
        )
        assertNull(result)
    }

    @Test
    fun warnsZeroSumOnRepeatedScenario() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(repeated = true),
            archetype = GameArchetype.ZERO_SUM,
            stressVerdict = "ROBUST",
            payoffsFilled = 4,
            payoffsExpected = 4
        )
        assertNotNull(result)
        assertTrue(result!!.contains("ongoing relationship"))
    }

    @Test
    fun warnsFragileWithMajorityMissingPayoffs() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(),
            archetype = null,
            stressVerdict = "FRAGILE",
            payoffsFilled = 2,
            payoffsExpected = 8
        )
        assertNotNull(result)
        assertTrue(result!!.contains("fragile"))
    }

    @Test
    fun noWarningFragileWhenPayoffsMostlyPresent() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(),
            archetype = null,
            stressVerdict = "FRAGILE",
            payoffsFilled = 6,
            payoffsExpected = 8
        )
        assertNull(result)
    }

    @Test
    fun warnsPrisonersDilemmaAllCooperativeLabels() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(actionLabels = listOf("Cooperate", "Collaborate")),
            archetype = GameArchetype.PRISONERS_DILEMMA,
            stressVerdict = "ROBUST",
            payoffsFilled = 8,
            payoffsExpected = 8
        )
        assertNotNull(result)
        assertTrue(result!!.contains("cooperative"))
    }

    @Test
    fun noWarningPrisonersDilemmaNonCooperativeLabels() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(actionLabels = listOf("Defect", "Hold")),
            archetype = GameArchetype.PRISONERS_DILEMMA,
            stressVerdict = "ROBUST",
            payoffsFilled = 8,
            payoffsExpected = 8
        )
        assertNull(result)
    }

    @Test
    fun zeroSumNonRepeatedNoWarning() {
        val result = ExtractionVerifier.verify(
            scenario = scenario(repeated = false),
            archetype = GameArchetype.ZERO_SUM,
            stressVerdict = "ROBUST",
            payoffsFilled = 4,
            payoffsExpected = 4
        )
        assertNull(result)
    }
}
