package com.drkkr.strategos

import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.intent.CrossProjectMatcher
import com.drkkr.strategos.domain.intent.MatchStrength
import com.drkkr.strategos.domain.intent.ProjectFeatures
import com.drkkr.strategos.domain.model.ScenarioStatus
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID4.2: cross-project memory (BLUEPRINT §6.2). */
class CrossProjectMatcherTest {
    private fun p(
        id: String, people: List<String> = emptyList(), archetype: String? = null, tags: Set<String> = emptySet(),
        archived: Boolean = false, updated: Long = 1L, worked: String? = null, title: String = "Project $id"
    ) = ProjectFeatures(id, title, people, null, archetype, tags, archived, updated, worked)

    private val current = p("now", people = listOf("Karthik"), archetype = "PRISONERS_DILEMMA", tags = setOf("probes_you"))

    @Test fun TC_ID_XP_01_theSameCounterpartNameIsAStrongMatch() {
        val m = CrossProjectMatcher.match(current, listOf(p("a", people = listOf("Asha")), p("b", people = listOf("karthik"), title = "CRM renewal")))!!
        assertEquals(MatchStrength.STRONG, m.strength)
        assertEquals("CRM renewal", m.title)
        assertEquals("the same person: Karthik", m.shared)
        val json = m.toJson().toString()
        assertTrue(json.contains("\"title\"") && json.contains("\"shared\""))
        // the same rule as the people carry-over on the Overview
        assertTrue(com.drkkr.strategos.domain.agent.CrossProjectPeople.samePerson("Karthik", "KARTHIK"))
    }

    @Test fun TC_ID_XP_02_theSameArchetypeAndConfirmedRadarTagIsAMediumMatch() {
        val other = p("b", archetype = "PRISONERS_DILEMMA", tags = setOf("probes_you"), title = "Office lease")
        val m = CrossProjectMatcher.match(current, listOf(other))!!
        assertEquals(MatchStrength.MEDIUM, m.strength)
        assertEquals("Office lease", m.title)
        assertTrue(m.shared, m.shared.startsWith("the same kind of situation, and the same sign: "))
        // either half alone is not a match
        assertNull(CrossProjectMatcher.match(current, listOf(p("c", archetype = "PRISONERS_DILEMMA"))))
        assertNull(CrossProjectMatcher.match(current, listOf(p("d", archetype = "CHICKEN", tags = setOf("probes_you")))))
        // a strong match wins over a newer medium one
        val strong = p("e", people = listOf("Karthik"), updated = 1L)
        assertEquals("e", CrossProjectMatcher.match(current, listOf(other.copy(updatedAt = 9L), strong))!!.projectId)
    }

    @Test fun TC_ID_XP_03_anArchivedOrDeletedProjectNeverMatches() {
        assertNull(CrossProjectMatcher.match(current, listOf(p("a", people = listOf("Karthik"), archived = true))))
        // a deleted project is not in the repository's list at all
        assertNull(CrossProjectMatcher.match(current, emptyList()))
        // read from the saved status
        assertTrue(CrossProjectMatcher.features(Fx.hlRaise().copy(status = ScenarioStatus.ARCHIVED)).archived)
        assertFalse(CrossProjectMatcher.features(Fx.hlRaise()).archived)
    }

    @Test fun TC_ID_XP_04_anAlreadyShownProjectIsSkipped() {
        val others = listOf(p("b", people = listOf("Karthik")))
        assertNull(CrossProjectMatcher.match(current, others, alreadyShown = setOf("b")))
        assertEquals("xproj_shown:c1:b", CrossProjectMatcher.shownKey("c1", "b"))
    }

    @Test fun TC_ID_XP_05_whatWorkedOnlyFromARecord_neverGuessed() {
        val s = Fx.hlRaise()
        assertNull("no record, no outcome: omitted", CrossProjectMatcher.features(s).whatWorked)
        val m = CrossProjectMatcher.match(current, listOf(p("b", people = listOf("Karthik"))))!!
        assertNull(m.whatWorked)
        assertFalse(m.toJson().containsKey("what_worked"))
        // a poor outcome is not "what worked"
        val poor = OutcomeRecord(s.id, "Ask with a plan", "Ask with a plan", 2)
        assertNull(CrossProjectMatcher.features(s, listOf(poor)).whatWorked)
        val good = OutcomeRecord(s.id, "Ask with a plan", "Ask with a plan", 5)
        assertEquals(CrossProjectMatcher.OUTCOME_WORKED.format("Ask with a plan"), CrossProjectMatcher.features(s, listOf(good)).whatWorked)
        // a learned tactic prior with more yes than no
        val learned = s.copy(humanContext = s.humanContext!!.copy(tacticSuccess = mapOf(TacticId.PUT_IT_IN_WRITING.name to listOf(3.0, 1.0))))
        val hint = TacticCatalog.offered.first { it.id == TacticId.PUT_IT_IN_WRITING }.coachHint
        assertEquals(CrossProjectMatcher.TACTIC_WORKED.format(hint), CrossProjectMatcher.features(learned).whatWorked)
        // the prior alone (no record) is not one
        val prior = s.copy(humanContext = s.humanContext!!.copy(tacticSuccess = mapOf(TacticId.PUT_IT_IN_WRITING.name to listOf(1.0, 1.0))))
        assertNull(CrossProjectMatcher.features(prior).whatWorked)
    }
}
