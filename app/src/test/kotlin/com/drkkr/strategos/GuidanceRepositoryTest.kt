package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.GuidanceRepository
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.agent.GuidanceSource
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
class GuidanceRepositoryTest {
    private lateinit var db: StrategosDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var scenarios: ScenarioRepository
    private lateinit var journal: JournalRepository
    private lateinit var guidance: GuidanceRepository
    private lateinit var sid: String

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        scenarios = ScenarioRepository(db, null).also { it.load() }
        sid = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved.id
        conversations = ConversationRepository(db)
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations, aiEnabled = { true })
        guidance = GuidanceRepository(db)
    }
    @After fun tearDown() { db.close() }

    private suspend fun guidedReply(convId: String, dos: List<String>, donts: List<String>) {
        val payload = GuidanceCodec.encode(CoachGuideSketch(doThis = dos, dontDoThis = donts))
        conversations.append(convId, "t", MessageRole.ASSISTANT, "reply", payloadJson = payload)
    }

    @Test fun TC_GUIDEREPO_01_mergesLatestGuideFromEachThread() = runBlocking {
        val main = conversations.getOrCreateActive(sid, "m", "v")
        guidedReply(main.id, dos = listOf("Hold your limit"), donts = listOf("Don't co-sign to keep the peace"))
        val money = conversations.createNamedThread(sid, "Money angle")
        guidedReply(money.id, dos = listOf("Offer a smaller gift"), donts = emptyList())

        val g = guidance.standing(sid)
        assertEquals(setOf("Hold your limit", "Offer a smaller gift"), g.dos.map { it.text }.toSet())
        assertEquals(listOf("Don't co-sign to keep the peace"), g.donts.map { it.text })
        assertTrue("every row carries its source thread title", g.dos.all { it.sourceLabel in setOf("Main Thread", "Money angle") })
        assertTrue("thread rows carry a refId to jump to", g.dos.all { it.refId != null })
    }

    @Test fun TC_GUIDEREPO_02_onlyTheLatestGuidePerThreadCounts() = runBlocking {
        val main = conversations.getOrCreateActive(sid, "m", "v")
        guidedReply(main.id, dos = listOf("Old advice"), donts = emptyList())
        guidedReply(main.id, dos = listOf("Fresh advice"), donts = emptyList())

        val g = guidance.standing(sid)
        assertEquals(listOf("Fresh advice"), g.dos.map { it.text })
    }

    @Test fun TC_GUIDEREPO_03_includesAppliedJournalTakeaways() = runBlocking {
        val entryId = journal.recordEntry("He went quiet and I wanted to text him", "ANXIOUS", sid).entryId
        val note = JournalInsightPostNoteEntity(
            id = UUID.randomUUID().toString(), journalEntryId = entryId, scenarioId = sid,
            significance = "HIGH", habitOrTrigger = "Silence triggers urgency", insightHeadline = "Silence triggers urgency",
            practicalApproach = "Pause 24h", doThis = "Give the quiet a day", dontDoThis = "Don't send a check-in text",
            createdAt = System.currentTimeMillis()
        )
        journal.addPostNote(note)
        journal.applyPostNote(note.id)

        val g = guidance.standing(sid)
        val journalDo = g.dos.single { it.source == GuidanceSource.JOURNAL }
        assertEquals("Give the quiet a day", journalDo.text)
        assertEquals("your journal", journalDo.sourceLabel)
        assertEquals(entryId, journalDo.refId)
        assertTrue(g.donts.any { it.text == "Don't send a check-in text" && it.source == GuidanceSource.JOURNAL })
    }

    @Test fun TC_GUIDEREPO_04_pendingJournalNoteIsExcludedUntilApplied() = runBlocking {
        val entryId = journal.recordEntry("note", "NEUTRAL", sid).entryId
        val note = JournalInsightPostNoteEntity(
            id = UUID.randomUUID().toString(), journalEntryId = entryId, scenarioId = sid,
            significance = "HIGH", habitOrTrigger = "h", insightHeadline = "i", practicalApproach = "p",
            doThis = "Not yet applied", dontDoThis = "", createdAt = System.currentTimeMillis()
        )
        journal.addPostNote(note)   // never applied
        assertTrue(guidance.standing(sid).dos.none { it.text == "Not yet applied" })
    }
}
