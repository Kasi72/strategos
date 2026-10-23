package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.fixtures.FakeLlmClient.Companion.textReply
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.fixtures.Sketches
import com.drkkr.strategos.ui.StrategosViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelCounselWiringTest {
    private lateinit var db: StrategosDatabase
    private lateinit var vm: StrategosViewModel
    private val fake = FakeLlmClient()

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val app = ApplicationProvider.getApplicationContext<StrategosApplication>()
        db = DbFactory.inMemoryForTests(app)
        app.replaceContainerForTests(AppContainer(app, openDatabase = { db }, llmOverride = fake))
        val settings = SettingsRepository(db)
        settings.setAiEnabled(true)
        settings.setConsentVersion(AgentConfig.CONSENT_VERSION)
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        vm = StrategosViewModel(app)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun analyzeThenCommitDoesNotConflict() = runBlocking {
        val saved = (vm.saveScenarioInternal(Fx.pd()) as SaveResult.Success).saved
        vm.setActiveScenario(saved)
        vm.analyzeAndAwait()
        val active = vm.state.value.activeScenario!!
        assertEquals(db.scenarioDao().get(saved.id)!!.version, active.version)
        val r = vm.commitAgentChange(active.copy(stakes = "Jobs"))
        assertTrue("expected Success, got $r", r is SaveResult.Success)
    }

    @Test
    fun startCounselFromTextWithAiReadySavesBindsAndSends() = runBlocking {
        fake.enqueue(textReply(Sketches.ASK))     // the app container runs the one-shot flow, so the reply is a JSON sketch
        val navigated = CompletableDeferred<Boolean>()
        vm.startCounselFromText("My brother wants me to co-sign a loan.\nMy wife is against it.") { navigated.complete(it) }
        assertEquals(true, withTimeout(5_000) { navigated.await() })
        vm.counsel!!.awaitIdle()
        val active = vm.state.value.activeScenario!!
        assertEquals("My brother wants me to co-sign a loan.", active.title)
        assertTrue(db.scenarioDao().get(active.id) != null)
        assertEquals(1, fake.requests.size)
        withTimeout(5_000) { while (vm.counselState.value.items.size < 2) delay(20) }
    }
}
