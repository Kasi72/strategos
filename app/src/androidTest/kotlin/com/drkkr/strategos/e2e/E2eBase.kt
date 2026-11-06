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
import androidx.compose.ui.test.performSemanticsAction
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
        // Process-wide UI state an earlier no-key test may have set (it pushed "Test key" under the keyboard in SET_03).
        com.drkkr.strategos.ui.calm.settings.SettingsNudge.on = false
        s.setPowerLock(false)   // a test that failed under the lock must not hide the switch from the next one
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

    /**
     * TC_ID_E2E_03: types into the Talk composer only once it is ready (no dialog over it, no reply in progress, one enabled text
     * field), then waits until the text is in the field and Send is enabled. Conditions on the nodes only: no sleeps, no retries.
     */
    protected fun typeWhenComposerReady(s: String) {
        val composer = androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.isEnabled()
        val sendReady = androidx.compose.ui.test.hasContentDescription("Send message") and androidx.compose.ui.test.hasClickAction()
        waitFor(30_000, "the composer to be ready") {
            rule.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithContentDescription("Stop reply").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(composer).fetchSemanticsNodes().size == 1 &&
                rule.onAllNodes(sendReady).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onAllNodes(composer)[0].performClick()
        rule.onAllNodes(composer)[0].performTextInput(s)
        waitFor(10_000, "the text in the composer and Send enabled") {
            rule.onAllNodes(composer and androidx.compose.ui.test.hasText(s)).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(sendReady and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Sends what is in the Talk composer through the button's click action (not a screen tap: the floating composer moves while
     * the keyboard animates, and a tap at the old spot was seen to miss), then waits until the composer has emptied, which is
     * when the message has really gone. TC_ID_E2E_03.
     */
    protected fun sendMessage() {
        val send = androidx.compose.ui.test.hasContentDescription("Send message") and androidx.compose.ui.test.hasClickAction()
        rule.onNode(send).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor(10_000, "the composer to empty after Send") {
            rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("", substring = false)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithContentDescription("Stop reply").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Taps a clickable element by its click action, wherever it sits on screen (under the floating composer too). */
    protected fun clickAction(t: String, substring: Boolean = false) =
        rule.onAllNodes(androidx.compose.ui.test.hasText(t, substring = substring) and androidx.compose.ui.test.hasClickAction())[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

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
