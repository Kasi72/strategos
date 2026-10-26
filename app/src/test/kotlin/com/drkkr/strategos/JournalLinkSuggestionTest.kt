package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalLinkSuggestionTest {
    private lateinit var db: StrategosDatabase
    private lateinit var scenarios: ScenarioRepository
    private lateinit var journal: JournalRepository
    private lateinit var sid: String

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        scenarios = ScenarioRepository(db, null).also { it.load() }
        sid = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved.id
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), ConversationRepository(db), aiEnabled = { true })
    }
    @After fun tearDown() { db.close() }

    private suspend fun entryWithNote(text: String): String {
        val id = journal.recordEntry(text, "ANXIOUS", null).entryId   // a global (unlinked) entry
        journal.addPostNote(JournalInsightPostNoteEntity(
            id = UUID.randomUUID().toString(), journalEntryId = id, scenarioId = sid,
            significance = "HIGH", habitOrTrigger = "h", insightHeadline = "i", practicalApproach = "p",
            doThis = "d", dontDoThis = "", createdAt = System.currentTimeMillis()
        ))
        return id
    }

    @Test fun TC_LINK_01_suggestsUnlinkedEntryTheScreenerTiedToTheProject() = runBlocking {
        val id = entryWithNote("He went quiet again")
        val suggestions = journal.linkSuggestions(sid)
        assertEquals(1, suggestions.size)
        assertEquals(id, suggestions.first().entryId)
        assertEquals("He went quiet again", suggestions.first().text)
    }

    @Test fun TC_LINK_02_linkAttachesTheEntryAndClearsTheSuggestion() = runBlocking {
        val id = entryWithNote("note")
        journal.link(id, sid)
        assertEquals(sid, journal.entry(id)!!.linkedScenarioId)
        assertTrue(journal.linkSuggestions(sid).isEmpty())
    }

    @Test fun TC_LINK_03_notThisDropsTheSuggestionButLeavesTheEntryGlobal() = runBlocking {
        val id = entryWithNote("note")
        journal.dismissLink(id, sid)
        assertNull(journal.entry(id)!!.linkedScenarioId)
        assertTrue(journal.linkSuggestions(sid).isEmpty())
    }
}
