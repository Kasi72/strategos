package com.drkkr.strategos

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
import com.drkkr.strategos.nlp.ScenarioParser
import com.drkkr.strategos.ui.StrategosViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.ExecutorService

/** I7: with AI off the app behaves like 1.x and the LLM client is never called. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiOffTest {
    private lateinit var db: StrategosDatabase
    private lateinit var roomExecutor: ExecutorService
    private lateinit var vm: StrategosViewModel
    private val fake = FakeLlmClient()

    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StrategosApplication>()
        val (d, ex) = inMemoryDbForTest(app)
        db = d; roomExecutor = ex
        // Use roomExecutor as Main so Room's createFlow picks up our executor as
        // queryDispatcher. GlobalScope queries then run on our executor, so
        // shutdown-before-close in closeDbSafely drains them before db.close().
        Dispatchers.setMain(roomExecutor.asCoroutineDispatcher())
        app.replaceContainerForTests(AppContainer(app, openDatabase = { db }, llmOverride = fake))
        SettingsRepository(db).setAiEnabled(false)
        vm = StrategosViewModel(app)
    }

    @After fun tearDown() {
        runBlocking { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        Dispatchers.resetMain()
        try { roomExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        closeDbSafely(db, roomExecutor)
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
