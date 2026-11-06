package com.drkkr.strategos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual upgrade check, skipped unless run with `-e postUpgrade 1` on a device where a 1.x build seeded "Seed One (1.x)" and
 * "Seed Two (1.x)" and the 2.0 build was then installed over it. Does not wipe anything.
 */
@RunWith(AndroidJUnit4::class)
class PostUpgradeCheckTest {
    @Test
    fun seededLegacyScenariosAreInRoomAfterUpgrade() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("postUpgrade") == "1")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val legacy = com.drkkr.strategos.data.storage.ScenarioStorage(ctx).loadAll()
            println("UPGRADE-LEGACY|scenarios=${legacy.scenarios.map { it.title }}|recoverable=${legacy.recoverableRecords.size}")
        } catch (e: Throwable) {
            println("UPGRADE-LEGACY|threw=${e.javaClass.name}|cause=${e.cause?.javaClass?.name}|msg=${e.message?.take(160)}")
        }
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        container.prepareStorage()
        val repo = container.scenarioRepository.also { it.load() }
        println("UPGRADE|storageError=${container.storageError}|titles=${repo.scenarios.value.map { it.title }}|recoverable=${repo.recoverableRecords.value.size}")
        assertEquals(setOf("Seed One (1.x)", "Seed Two (1.x)"), repo.scenarios.value.map { it.title }.toSet())
        assertTrue(repo.recoverableRecords.value.isEmpty())
    }

    /**
     * TC_ID_E2E_02, scripts/upgrade-check.sh: skipped unless run with `-e upgradeCheck 1`, after the `main` build's
     * ManualSeedDemoTest seeded the app and this branch's APK was installed over it. Does not wipe anything.
     */
    @Test
    fun TC_ID_E2E_02_upgradeKeepsTheProjectConversationAndJournal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeCheck") == "1")
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        container.prepareStorage()
        val repo = container.scenarioRepository.also { it.load() }
        val project = repo.scenarios.value.firstOrNull { it.title == "Loan for my brother" }
        val threads = project?.let { com.drkkr.strategos.data.repo.ConversationRepository(container.database!!).listThreads(it.id) }.orEmpty()
        val messages = threads.sumOf { com.drkkr.strategos.data.repo.ConversationRepository(container.database!!).messages(it.id).size }
        val journal = project?.let { container.journalRepository!!.linkedEntries(it.id, 10) }.orEmpty()
        println("UPGRADE-CHECK|storageError=${container.storageError}|project=${project != null}|threads=${threads.size}|messages=$messages|journal=${journal.size}")
        assertTrue("the project survived the upgrade", project != null)
        assertTrue("its conversations survived (threads=${threads.size})", threads.size >= 2)
        assertTrue("its messages survived ($messages)", messages >= 5)
        assertTrue("the journal entry survived", journal.isNotEmpty())
    }
}
