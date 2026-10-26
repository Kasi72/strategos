package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.BrainFactKind
import com.drkkr.strategos.domain.agent.ProjectBrain
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.*
import org.junit.Test

/** Pure read-model tests over a fixed scenario — no DB, no android. */
class ProjectBrainTest {

    @Test fun TC_BRAIN_01_peopleGroupedWithSelfFirst() {
        val brain = ProjectBrain.from(Fx.loanFamily())
        assertEquals(4, brain.people.size)
        assertTrue("You sorts first", brain.people.first().isSelf)
        assertEquals("You", brain.people.first().name)
        assertEquals("B", brain.people.first { it.name == "Brother" }.initial)
    }

    @Test fun TC_BRAIN_02_wantsAndFearsMapFromStakeholder() {
        val brother = ProjectBrain.from(Fx.loanFamily()).people.first { it.name == "Brother" }
        assertTrue(brother.facts.any { it.kind == BrainFactKind.WANT && it.text == "Get the loan" })
        assertTrue(brother.facts.any { it.kind == BrainFactKind.FEAR && it.text == "Losing face" })
    }

    @Test fun TC_BRAIN_03_limitsComeFromConstraints() {
        val brain = ProjectBrain.from(Fx.loanFamily())
        assertEquals(1, brain.limits.size)
        assertEquals("Don't risk peace at home", brain.limits.first().text)
        assertEquals(BrainFactKind.LIMIT, brain.limits.first().kind)
    }

    @Test fun TC_BRAIN_04_modelCountsMatchScenario() {
        val brain = ProjectBrain.from(Fx.loanFamily())
        assertEquals(4, brain.counts.people)
        assertEquals(6, brain.counts.options)                 // 3 of mine + 3 of his
        assertTrue(brain.counts.outcomesExpected > 0)
        assertEquals(brain.counts.outcomesExpected, brain.counts.outcomesFilled)  // loanFamily fills every profile
    }

    @Test fun TC_BRAIN_05_inferredFactsCarryInferredBadgeAndCount() {
        val s = Fx.loanFamily().let { base ->
            base.copy(stakeholders = base.stakeholders.map {
                if (it.name == "Parents") it.copy(factStatus = FactStatus.AI_INFERRED, provenanceNote = "guessed from context") else it
            })
        }
        val brain = ProjectBrain.from(s)
        val parents = brain.people.first { it.name == "Parents" }
        assertTrue(parents.facts.all { it.provenance == Provenance.INFERRED })
        assertEquals(1, brain.inferredCount)                  // one person still to review
        assertEquals(0, brain.reviewedCount)
        assertEquals(1, brain.inferredTotal)
    }

    @Test fun TC_BRAIN_06_keptInferredFactCountsAsReviewedNotPending() {
        // AI first guessed Parents (keeps its rationale note), the user has since kept it as their own.
        val s = Fx.loanFamily().let { base ->
            base.copy(stakeholders = base.stakeholders.map {
                if (it.name == "Parents") it.copy(factStatus = FactStatus.USER_ESTIMATE, provenanceNote = "guessed from context") else it
            })
        }
        val brain = ProjectBrain.from(s)
        assertEquals(0, brain.inferredCount)
        assertEquals(1, brain.reviewedCount)
        assertEquals(1, brain.inferredTotal)
        assertEquals(Provenance.USER, brain.people.first { it.name == "Parents" }.facts.first().provenance)
    }
}
