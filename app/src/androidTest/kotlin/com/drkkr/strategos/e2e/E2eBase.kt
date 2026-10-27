package com.drkkr.strategos.e2e

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.AppContainer
import com.drkkr.strategos.MainActivity
import com.drkkr.strategos.StrategosApplication
import com.drkkr.strategos.agent.AgentConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * Drives the real app on the emulator (never the user's phone). Each test starts from a wiped database.
 * The live key arrives as the instrumentation argument `deepseekKey` (see scripts/run-e2e.sh); tests that need the
 * real coach are skipped when it is absent. The key is never logged or asserted on.
 */
abstract class E2eBase {
    @get:Rule val rule = createEmptyComposeRule()
    protected var scenario: ActivityScenario<MainActivity>? = null

    protected val ctx: Context get() = ApplicationProvider.getApplicationContext()
    protected val container: AppContainer get() = (ctx as StrategosApplication).container
    protected val liveKey: String? get() = InstrumentationRegistry.getArguments().getString("deepseekKey")?.takeIf { it.isNotBlank() }

    protected fun requireLiveKey() = assumeTrue("deepseekKey instrumentation argument not set", liveKey != null)

    /** Wipes projects, journal and settings; optionally installs the live key with consent given. */
    protected fun reset(withKey: Boolean, onboarded: Boolean = true) = runBlocking {
        container.prepareStorage()
        container.scenarioRepository.load()
        container.scenarioRepository.deleteEverything()
        val s = container.settingsRepository!!
        container.keyVault!!.clear()
        s.setAiEnabled(false); s.setKeyInvalid(false); s.setConsentVersion(0)
        if (withKey) {
            container.keyVault!!.put(liveKey!!)
            s.setAiEnabled(true); s.setConsentVersion(AgentConfig.CONSENT_VERSION); s.setModel(AgentConfig.DEFAULT_MODEL)
        }
        ctx.getSharedPreferences("calm_ui", Context.MODE_PRIVATE).edit().putBoolean("onboarded", onboarded).commit()
        container.scenarioRepository.load()
    }

    protected fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    @After fun closeApp() { scenario?.close() }

    // ── Finders ─────────────────────────────────────────────────────────────
    protected fun text(t: String, substring: Boolean = false) = rule.onNodeWithText(t, substring = substring, useUnmergedTree = true)
    protected fun desc(d: String) = rule.onNodeWithContentDescription(d)
    protected fun field(index: Int = 0): SemanticsNodeInteraction = rule.onAllNodes(hasSetTextAction())[index]
    protected fun typeInto(index: Int, s: String) { field(index).performClick(); field(index).performTextInput(s) }

    protected fun waitFor(timeoutMs: Long = 20_000, what: String = "condition", cond: () -> Boolean) {
        try { rule.waitUntil(timeoutMs) { cond() } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val tree = runCatching { rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 40) }.getOrDefault("(no tree)")
            tree.lines().take(120).forEach { android.util.Log.e("E2E", it) }
            throw AssertionError("Timed out waiting for $what. Screen was:\n" + tree.lines().take(80).joinToString("\n"), e)
        }
    }
    protected fun waitForText(t: String, timeoutMs: Long = 20_000, substring: Boolean = false) =
        waitFor(timeoutMs, "text '$t'") { rule.onAllNodesWithText(t, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    protected fun waitForDesc(d: String, timeoutMs: Long = 20_000) =
        waitFor(timeoutMs, "description '$d'") { rule.onAllNodesWithContentDescription(d).fetchSemanticsNodes().isNotEmpty() }
    protected fun seedProject(title: String, description: String = "Seeded for an end-to-end test."): String = runBlocking {
        val saved = container.scenarioRepository.save(com.drkkr.strategos.domain.model.Scenario(title = title, description = description))
        (saved as com.drkkr.strategos.data.repo.SaveResult.Success).saved.id
    }

    protected fun hasText(t: String, substring: Boolean = false) = rule.onAllNodesWithText(t, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun openTab(name: String) { rule.onAllNodesWithText(name).onLast().performClick(); rule.waitForIdle() }

    /** Waits until the coach has answered the first message of the (only) project. */
    protected fun waitForCoachReply(timeoutMs: Long = 120_000) {
        val dao = container.database!!.messageDao()
        waitFor(timeoutMs) {
            val sid = runBlocking { container.scenarioRepository.scenarios.value }.firstOrNull()?.id ?: return@waitFor false
            runBlocking { dao.countVisibleForScenario(sid) } >= 2
        }
    }
}
