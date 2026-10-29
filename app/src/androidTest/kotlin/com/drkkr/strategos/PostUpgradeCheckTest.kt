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
}
