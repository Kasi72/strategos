package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.nlp.ScenarioParser
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** I7: with AI off the app behaves like 1.x and the LLM client is never called. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiOffTest {
    private lateinit var db: StrategosDatabase
    private lateinit var vm: StrategosViewModel
    private val fake = FakeLlmClient()

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val app = ApplicationProvider.getApplicationContext<StrategosApplication>()
        db = DbFactory.inMemoryForTests(app)
        app.replaceContainerForTests(AppContainer(app, openDatabase = { db }, llmOverride = fake))
        SettingsRepository(db).setAiEnabled(false)
        vm = StrategosViewModel(app)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun TC_AIOFF_01_composerTakesParserPath() = runBlocking {
        val text = "The hospital board must decide whether to close the night clinic. Staff and patients disagree."
        val navigated = CompletableDeferred<Boolean>()
        vm.startCounselFromText(text) { navigated.complete(it) }
        assertFalse(withTimeout(5_000) { navigated.await() })
        assertEquals(ScenarioParser.parse(text).draft.title, vm.state.value.activeScenario!!.title)
        delay(200)   // give any stray coroutine a chance to call the LLM
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun sendWithAiOffNeverCallsTheLlm() = runBlocking {
        val counsel = vm.counsel!!
        counsel.bindScenario(null).join()
        counsel.send("hello")?.join()
        counsel.awaitIdle()
        assertTrue(fake.requests.isEmpty())
        assertFalse(vm.counselState.value.aiReady)
    }
}
