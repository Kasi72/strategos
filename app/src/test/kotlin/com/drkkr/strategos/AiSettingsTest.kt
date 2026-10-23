package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.AiSettingsController
import com.drkkr.strategos.agent.KeyState
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.MapVault
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.ModelInfo
import com.drkkr.strategos.llm.ModelListResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TEST_CASES §9 (TC_KEY_*) and §9b (TC_MODEL_*), Groq wording. TC_KEY_07 is in OneShotFlowTest, TC_MODEL_07 too; KEY_08 (PIN) is deferred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsTest {
    private lateinit var db: StrategosDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var vault: MapVault
    private lateinit var controller: AiSettingsController
    private val fake = FakeLlmClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 10_000_000_000L

    @Before fun setUp() {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository(db)
        vault = MapVault(db.settingDao())
        controller = AiSettingsController(scope, settings, vault, fake) { clock }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun ok() = LlmResult.Ok("r", kotlinx.serialization.json.JsonArray(emptyList()), com.drkkr.strategos.llm.LlmUsage.ZERO, 1)
    private fun live(vararg ids: String) { fake.models = ModelListResult.Ok(ids.map { ModelInfo(it) }) }

    @Test
    fun TC_KEY_01_savedTrimmedThenReadyAfterConsentWithoutRestart() = runBlocking {
        fake.testKeyResult = ok()
        // An observer, as the Counsel screen has: it sees aiReady flip without any restart.
        val seen = mutableListOf<Boolean>()
        val watcher = scope.launch { settings.observeAiFlags().collect { seen += settings.aiReady() } }
        controller.saveAndConnect("  gsk-123\n").join()
        assertEquals("gsk-123", vault.get())
        assertEquals("gsk-123", fake.testKeyCalls.single().first)               // tested with the candidate key
        assertTrue(controller.state.value.needsConsent)
        assertFalse(settings.aiReady())
        controller.acceptConsent().join()
        assertTrue(settings.aiReady())
        assertEquals(KeyState.CONNECTED, controller.state.value.keyState)
        assertEquals("-123", controller.state.value.keyLast4)
        withTimeout(5_000) { while (seen.lastOrNull() != true) kotlinx.coroutines.delay(20) }
        watcher.cancel()
    }

    @Test
    fun TC_KEY_02_rejectedKeyStoresNothing() = runBlocking {
        fake.testKeyResult = LlmResult.Unauthorized(401)
        controller.saveAndConnect("gsk-bad").join()
        assertNull(vault.get())
        assertEquals(KeyState.REJECTED, controller.state.value.keyState)
        assertTrue(controller.state.value.message!!.contains("didn't accept this key"))
        assertFalse(settings.aiEnabled())
    }

    @Test
    fun TC_KEY_03_offlineStoresNothing() = runBlocking {
        fake.testKeyResult = LlmResult.Network("offline")
        controller.saveAndConnect("gsk-1").join()
        assertNull(vault.get())
        assertEquals(KeyState.OFFLINE, controller.state.value.keyState)
    }

    @Test
    fun TC_KEY_04_busyServiceStillSavesTheKey() = runBlocking {
        fake.testKeyResult = LlmResult.HttpError(503, null)
        controller.saveAndConnect("gsk-1").join()
        assertEquals("gsk-1", vault.get())
        assertEquals(KeyState.CONNECTED, controller.state.value.keyState)
        assertTrue(controller.state.value.message!!.startsWith("Saved."))
    }

    @Test
    fun TC_KEY_05_failedChangeKeepsTheOldKey() = runBlocking {
        fake.testKeyResult = ok()
        controller.saveAndConnect("gsk-old").join()
        fake.testKeyResult = LlmResult.Unauthorized(401)
        controller.saveAndConnect("gsk-new").join()
        assertEquals("gsk-old", vault.get())
    }

    @Test
    fun TC_KEY_06_removeTurnsAiOffButKeepsConversations() = runBlocking {
        db.scenarioDao().upsert(ScenarioEntity("s", "S", "DRAFT", 1, 1L, 1L, "{}"))
        val cid = ConversationRepository(db).getOrCreateActive("s", "m", "v").id
        ConversationRepository(db).append(cid, "t", com.drkkr.strategos.data.repo.MessageRole.USER, "hello")
        fake.testKeyResult = ok()
        controller.saveAndConnect("gsk-1").join(); controller.acceptConsent().join()
        controller.remove().join()
        assertNull(vault.get())
        assertFalse(settings.aiEnabled())
        assertFalse(settings.aiReady())
        assertEquals(1, ConversationRepository(db).messages(cid).size)
    }

    @Test
    fun blankKeyAsksForOne() = runBlocking {
        controller.saveAndConnect("  \n ").join()
        assertEquals("Enter a key.", controller.state.value.message)
        assertTrue(fake.testKeyCalls.isEmpty())
    }

    @Test
    fun TC_MODEL_01_liveListFilteredAndSorted() = runBlocking {
        vault.put("gsk-1")
        live("whisper-large-v3", "openai/gpt-oss-120b", "meta-llama/llama-prompt-guard-2-86m", "canopylabs/orpheus-v1", "openai/gpt-oss-safeguard-20b", "openai/gpt-oss-20b")
        controller.load().join()
        val rows = controller.state.value.models
        assertEquals(listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b"), rows.map { it.id })
        assertTrue(rows[0].recommended)
        assertTrue(rows[0].current)
        assertEquals("$0.075 / $0.3 per 1M", rows[0].priceLabel)
    }

    @Test
    fun TC_MODEL_02_failedLiveCallUsesA3DayOldCache() = runBlocking {
        vault.put("gsk-1")
        live("openai/gpt-oss-20b", "some/cached-model")
        controller.refreshModels(force = true).join()
        clock += 3 * 24 * 60 * 60 * 1000L
        fake.models = ModelListResult.Failed("offline")
        controller.refreshModels(force = true).join()
        assertEquals(setOf("openai/gpt-oss-20b", "some/cached-model"), controller.state.value.models.map { it.id }.toSet())
    }

    @Test
    fun TC_MODEL_03_noLiveNoCacheUsesModelOptions() = runBlocking {
        vault.put("gsk-1")
        fake.models = ModelListResult.Failed("offline")
        controller.refreshModels(force = true).join()
        assertEquals(AgentConfig.MODEL_OPTIONS, controller.state.value.models.map { it.id })
        // A cache older than 7 days is ignored too.
        live("old/model"); controller.refreshModels(force = true).join()
        clock += 8 * 24 * 60 * 60 * 1000L
        fake.models = ModelListResult.Failed("offline")
        controller.refreshModels(force = true).join()
        assertEquals(AgentConfig.MODEL_OPTIONS, controller.state.value.models.map { it.id })
    }

    @Test
    fun TC_MODEL_04_workingModelIsSaved() = runBlocking {
        vault.put("gsk-1"); fake.testKeyResult = ok()
        controller.selectModel("openai/gpt-oss-120b").join()
        assertEquals("openai/gpt-oss-120b", settings.model())
        assertEquals("gsk-1" to "openai/gpt-oss-120b", fake.testKeyCalls.single())     // pinged with the STORED key
        assertEquals("Now using openai/gpt-oss-120b.", controller.state.value.modelMessage)
    }

    @Test
    fun TC_MODEL_05_unavailableModelIsNotSaved() = runBlocking {
        vault.put("gsk-1"); fake.testKeyResult = LlmResult.HttpError(404, "model_not_found")
        controller.selectModel("openai/gpt-oss-120b").join()
        assertEquals(AgentConfig.DEFAULT_MODEL, settings.model())
        assertEquals("That model isn't available on your key.", controller.state.value.modelMessage)
    }

    @Test
    fun TC_MODEL_06_customIdSavedAfterPingAndShownWithUnknownPrice() = runBlocking {
        vault.put("gsk-1"); fake.testKeyResult = ok()
        controller.selectModel("vendor/brand-new-model").join()
        assertEquals("vendor/brand-new-model", settings.model())
        val row = controller.state.value.models.single { it.id == "vendor/brand-new-model" }
        assertEquals("price unknown", row.priceLabel)
        assertTrue(row.current)
    }
}
