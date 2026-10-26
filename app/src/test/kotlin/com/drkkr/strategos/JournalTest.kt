package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.JournalScreenerService
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalTest {
    private lateinit var db: StrategosDatabase
    private lateinit var fake: FakeLlmClient
    private lateinit var scenarios: ScenarioRepository
    private lateinit var journal: JournalRepository
    private lateinit var memory: MemoryRepository
    private lateinit var service: JournalScreenerService
    private var sid = ""

    @Before fun setUp() = runBlocking {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        fake = FakeLlmClient()
        scenarios = ScenarioRepository(db, null).also { it.load() }
        sid = (scenarios.save(Fx.loanFamily()) as SaveResult.Success).saved.id
        memory = MemoryRepository(db)
        journal = JournalRepository(db, XorBase64TestCipher(), memory, ConversationRepository(db), aiEnabled = { true })
        service = JournalScreenerService(
            fake, journal, scenarios, TrajectoryRepository(db, scenarios), settings = { true to "deepseek-chat" },
            scope = CoroutineScope(UnconfinedTestDispatcher())
        )
    }
    @After fun tearDown() { db.close() }

    private fun reply(impacts: String) = FakeLlmClient.textReply(
        """{"mood_tag":"ANXIOUS","support":false,"impacts":[$impacts]}""")

    private val high = """{"scenario_id":"loan","significance":"HIGH","habit_or_trigger":"Urgency when facing silence",
        "insight_headline":"Silence triggers urgency","practical_approach":"Pause 24h","do_this":"Wait a day","dont_do_this":"Don't double-text",
        "target_dimension":"WELLBEING","weight_delta":1.5}"""

    @Test fun TC_JRN_01_recordEntryIsInstantAndEncrypted() = runBlocking {
        val r = journal.recordEntry("I panicked and wanted to text him 5 times", "ANXIOUS", sid)
        assertEquals("Recorded privately 🍃", r.ack); assertFalse(r.safetyTriggered)
        assertTrue(fake.requests.isEmpty())                                    // saving never calls the model
        val stored = db.journalDao().getEntry(r.entryId)!!
        assertFalse(stored.encryptedText.contains("panicked"))
        assertEquals("I panicked and wanted to text him 5 times", journal.observeEntries().first().single().text)
    }

    @Test fun TC_JRN_02_silentScreenerCreatesPostNoteOnHighSignificance() = runBlocking {
        fake.enqueue(reply(high))
        val id = journal.recordEntry("I hate when they go silent", "ANXIOUS", sid).entryId
        assertTrue(service.screen(id))
        val note = journal.activePostNotes(sid).single()
        assertTrue(note.insightHeadline.startsWith("Based on your journal entry:"))
        assertEquals("Wait a day", note.doThis); assertEquals("Don't double-text", note.dontDoThis)
        assertEquals("SCREENED", db.journalDao().getEntry(id)!!.syncStatus)
        assertEquals("JOURNAL_SHIFT", db.trajectoryDao().latestForScenario(sid)!!.source)
        val req = fake.requests.single()
        assertTrue(req.jsonObjectMode); assertEquals(600, req.maxOutputTokens)
    }

    @Test fun TC_JRN_03_ordinaryVentingCreatesZeroPostNotes() = runBlocking {
        fake.enqueue(reply(""))
        val id = journal.recordEntry("Tired after a long commute today", "NEUTRAL", null).entryId
        assertTrue(service.screen(id))
        assertEquals("SCREENED", db.journalDao().getEntry(id)!!.syncStatus)
        assertTrue(journal.activePostNotes(sid).isEmpty())
    }

    @Test fun TC_JRN_04_offlineOrErrorLeavesEntrySafeAsPendingScreen() = runBlocking {
        fake.enqueue(FakeLlmClient.networkDown())
        val r = journal.recordEntry("I caved again", "DRAINED", sid)
        assertEquals("Recorded privately 🍃", r.ack)
        assertFalse(service.screen(r.entryId))
        assertEquals("PENDING_SCREEN", db.journalDao().getEntry(r.entryId)!!.syncStatus)
        fake.enqueue(FakeLlmClient.textReply("not json")); assertFalse(service.screen(r.entryId))   // malformed reply is swallowed too
        assertEquals("PENDING_SCREEN", db.journalDao().getEntry(r.entryId)!!.syncStatus)
    }

    @Test fun TC_JRN_05_retryPendingScreensRecoversWhenOnline() = runBlocking {
        fake.enqueue(FakeLlmClient.networkDown())
        val id = journal.recordEntry("I double-text when ignored", "ANXIOUS", sid).entryId
        service.screen(id)
        fake.enqueue(reply(high))
        assertEquals(1, service.retryPendingScreens())
        assertEquals("SCREENED", db.journalDao().getEntry(id)!!.syncStatus)
        assertEquals(1, journal.activePostNotes(sid).size)
    }

    @Test fun TC_JRN_06_applyPostNoteUpdatesMemoryAndMarksApplied() = runBlocking {
        fake.enqueue(reply(high))
        // A substantive entry (>= 2 words) so the Phase G.5 trivial-entry pre-filter doesn't skip the screening call;
        // the post-note content under test comes from the enqueued reply, not from this text.
        service.screen(journal.recordEntry("I keep checking my phone", "ANXIOUS", sid).entryId)
        val note = journal.activePostNotes(sid).single()
        assertTrue(journal.applyPostNote(note.id))
        assertTrue(db.journalDao().getPostNote(note.id)!!.applied)
        val pref = memory.open(sid).single { it.kind == "PREFERENCE" }
        assertEquals("Urgency when facing silence: DO Wait a day | AVOID Don't double-text", pref.text)
        assertEquals("USER_ESTIMATE", pref.factStatus)
    }

    @Test fun TC_JRN_07_trivialEntryIsScreenedWithoutANetworkCall() = runBlocking {
        // A single-word entry has nothing to link to a project: it is marked SCREENED locally, with no model call.
        val id = journal.recordEntry("tired", "DRAINED", sid).entryId
        assertTrue(service.screen(id))
        assertEquals("SCREENED", db.journalDao().getEntry(id)!!.syncStatus)
        assertTrue("no model call for a trivial entry", fake.requests.isEmpty())
        assertTrue(journal.activePostNotes(sid).isEmpty())
    }

    @Test fun TC_JRN_08_trivialButCrisisStillScreens() = runBlocking {
        // A crisis signal is never treated as trivial, even when short: the pre-filter must not skip it.
        fake.enqueue(reply(""))
        val id = journal.recordEntry("suicide", "DRAINED", sid).entryId
        assertTrue(service.screen(id))
        assertEquals("the crisis carve-out forces a screening call", 1, fake.requests.size)
    }

    @Test fun aiOffMakesNoCallAndKeepsRecorded() = runBlocking {
        val off = JournalRepository(db, XorBase64TestCipher(), memory, ConversationRepository(db), aiEnabled = { false })
        val id = off.recordEntry("private", "CALM", null).entryId
        assertEquals("RECORDED", db.journalDao().getEntry(id)!!.syncStatus)
        val svc = JournalScreenerService(fake, off, scenarios, TrajectoryRepository(db, scenarios), { false to "m" }, CoroutineScope(UnconfinedTestDispatcher()))
        assertFalse(svc.screen(id)); assertTrue(fake.requests.isEmpty())
    }

    @Test fun crisisPhraseSetsSafetyFlagWithoutNetwork() = runBlocking {
        assertTrue(journal.recordEntry("I want to end my life", "DRAINED", null).safetyTriggered)
        assertTrue(fake.requests.isEmpty())
    }
}
