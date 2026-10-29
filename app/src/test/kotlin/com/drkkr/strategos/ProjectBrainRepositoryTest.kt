package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.ProjectBrainRepository
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.domain.agent.BrainFactKind
import com.drkkr.strategos.domain.agent.ReviewAction
import com.drkkr.strategos.domain.agent.ReviewKind
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectBrainRepositoryTest {
    private lateinit var db: StrategosDatabase
    private lateinit var scenarios: ScenarioRepository
    private lateinit var brainRepo: ProjectBrainRepository
    private lateinit var sid: String

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        scenarios = ScenarioRepository(db, null).also { it.load() }
        sid = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved.id
        brainRepo = ProjectBrainRepository(scenarios, MemoryRepository(db))
    }
    @After fun tearDown() { db.close() }

    @Test fun TC_BRAINREPO_01_addFactShowsAsUserNote() = runBlocking {
        brainRepo.addFact(sid, "He texts late at night")
        val notes = brainRepo.brain(sid)!!.notes
        assertEquals(1, notes.size)
        assertEquals("He texts late at night", notes.first().text)
        assertEquals(BrainFactKind.NOTE, notes.first().kind)
        assertEquals(Provenance.USER, notes.first().provenance)
        assertNotNull(notes.first().memoryId)
    }

    @Test fun TC_BRAINREPO_02_editAndRemoveNote() = runBlocking {
        val id = brainRepo.addFact(sid, "typo here")
        brainRepo.editNote(id, "fixed text")
        assertEquals("fixed text", brainRepo.brain(sid)!!.notes.single().text)
        brainRepo.removeNote(id)
        assertTrue(brainRepo.brain(sid)!!.notes.isEmpty())
    }

    @Test fun TC_BRAINREPO_03_reviewKeepPromotesInferredToYours() = runBlocking {
        // Make Parents AI-inferred, then keep them as the user's own.
        val withInferred = scenarios.find(sid)!!.let { s ->
            s.copy(stakeholders = s.stakeholders.map {
                if (it.name == "Parents") it.copy(factStatus = FactStatus.AI_INFERRED, provenanceNote = "guessed") else it
            })
        }
        scenarios.save(withInferred)
        assertEquals(1, brainRepo.brain(sid)!!.inferredCount)

        val item = brainRepo.reviewItems(sid).single { it.kind == ReviewKind.PERSON }
        assertTrue(brainRepo.review(sid, item, ReviewAction.KEEP))

        val after = brainRepo.brain(sid)!!
        assertEquals(0, after.inferredCount)
        assertEquals(1, after.reviewedCount)
        assertEquals(Provenance.USER, after.people.first { it.name == "Parents" }.facts.first().provenance)
    }

    @Test fun TC_BRAINREPO_04_reviewRemoveDropsThePerson() = runBlocking {
        val withInferred = scenarios.find(sid)!!.let { s ->
            s.copy(stakeholders = s.stakeholders.map {
                if (it.name == "Parents") it.copy(factStatus = FactStatus.AI_INFERRED, provenanceNote = "guessed") else it
            })
        }
        scenarios.save(withInferred)
        val item = brainRepo.reviewItems(sid).single { it.kind == ReviewKind.PERSON }
        assertTrue(brainRepo.review(sid, item, ReviewAction.REMOVE))

        val after = brainRepo.brain(sid)!!
        assertEquals(3, after.counts.people)
        assertTrue(after.people.none { it.name == "Parents" })
    }
}
