package com.drkkr.strategos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.storage.ScenarioStorage
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.templates.CrisisManagementTemplate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Install 1.x, create 2 scenarios, install 2.0": the 1.x store is written with the same ScenarioStorage class 1.x used
 * (EncryptedSharedPreferences + the AndroidX master key), then the 2.0 container migrates it.
 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before @After fun wipe() {
        context.deleteDatabase("strategos.db")
        File(context.noBackupFilesDir, "db.key").delete()
        context.deleteSharedPreferences("strategos_scenarios")
    }

    @Test
    fun legacyScenariosAppearAfterUpgradeAndSaveStillWorks() = runBlocking {
        val legacy = ScenarioStorage(context)
        val a = CrisisManagementTemplate.create().copy(title = "Legacy A", version = 3)
        val b = Scenario(id = "legacy-b", title = "Legacy B")
        legacy.save(a); legacy.save(b)
        legacy.saveSnapshot(AnalysisSnapshot(scenarioId = a.id, scenarioVersion = 3, scenarioHash = "h", selectedModuleIds = listOf("m"),
            executiveStatus = "X", candidateCount = 1, overallConfidence = 0.5, reportHash = "r", scenarioJson = "{}"))

        val container = AppContainer(context)
        container.prepareStorage()
        val repo = container.scenarioRepository.also { it.load() }

        assertEquals(setOf("Legacy A", "Legacy B"), repo.scenarios.value.map { it.title }.toSet())
        assertEquals(3, repo.find(a.id)!!.version)
        assertEquals(1, repo.loadSnapshots(a.id).size)
        val saved = repo.save(repo.find("legacy-b")!!.copy(title = "Legacy B edited"))
        assertTrue(saved is SaveResult.Success)

        // A second process start does not migrate again and does not duplicate anything.
        val again = AppContainer(context)
        again.prepareStorage()
        assertEquals(2, again.scenarioRepository.also { it.load() }.scenarios.value.size)
        // The 1.x store is kept in 2.0.
        assertEquals(2, ScenarioStorage(context).loadAll().scenarios.size)
    }

    @Test
    fun freshInstallHasNoScenariosAndNoLegacyKeyIsCreated() = runBlocking {
        val container = AppContainer(context)
        container.prepareStorage()
        assertTrue(container.scenarioRepository.also { it.load() }.scenarios.value.isEmpty())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/strategos_scenarios.xml").exists())
    }
}
