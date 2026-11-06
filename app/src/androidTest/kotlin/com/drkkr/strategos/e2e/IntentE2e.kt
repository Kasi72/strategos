package com.drkkr.strategos.e2e

import android.graphics.Bitmap
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.domain.model.Stakeholder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Intent Dispatch ID4.3 on the real app, with the live coach (skipped without a key). Replies vary from run to run, so these
 * assert what was shown, never exact wording. A screenshot of each reply goes to the app's external files dir (`intent-e2e/`).
 */
@RunWith(AndroidJUnit4::class)
class IntentE2e : E2eBase() {
    private fun openTalk(name: String) {
        waitForText(name); text(name).performClick(); waitForText("Start chat"); text("Start chat").performClick(); waitForDesc("Back to overview")
    }

    private fun replies(sid: String): List<String> = runBlocking {
        val repo = ConversationRepository(container.database!!)
        repo.listThreads(sid).flatMap { t -> repo.messages(t.id) }.filter { it.role == "ASSISTANT" }.map { it.text }
    }

    private fun say(sid: String, text: String) {
        val before = replies(sid).size
        typeWhenComposerReady(text)
        sendMessage()
        waitFor(180_000, "a reply to: ${text.take(40)}") { replies(sid).size > before && !hasText("Stop reply") }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "intent-e2e").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Two projects with the same counterpart name: the second conversation's reply names the first project's title once. */
    @Test fun ID43_01_sameCounterpart_theReplyMentionsTheOtherProjectOnce() {
        requireLiveKey()
        reset(withKey = true)
        val firstTitle = "CRM renewal"
        val first = seedProject(firstTitle, "Renewing our CRM contract with Karthik.")
        runBlocking {
            val s = container.scenarioRepository.find(first)!!
            container.scenarioRepository.save(s.copy(decisionOwner = "You", stakeholders = listOf(Stakeholder(id = "you", name = "You"), Stakeholder(id = "k", name = "Karthik"))))
        }
        val second = seedProject("Printer lease", "Leasing office printers.")
        launch()
        openTalk("Printer lease")
        say(second, "Karthik from the printer company wants us to sign a three-year lease, and he's pushing hard to close it this week. I'd rather sign for one year.")
        val last = replies(second).last()
        shot("01-cross-project")
        last.lines().forEach { android.util.Log.i("E2E_XP", it) }     // the test's own seeded story, for the checkpoint
        assertEquals(last, 1, Regex(Regex.escape(firstTitle), RegexOption.IGNORE_CASE).findAll(last).count())
    }

    /** "What should I do over the next 3 exchanges?" gets a plan: the first move and at least two more steps. */
    @Test fun ID43_02_planAhead_aReplyWithThreeSteps() {
        requireLiveKey()
        reset(withKey = true)
        val sid = seedProject("Rent increase", "My landlord wants to raise the rent.")
        launch()
        openTalk("Rent increase")
        say(sid, "My landlord Mr. Rao wants to raise my rent by 15% at renewal next month. I've lived here four years, always paid on time, and similar flats nearby rent for about 8% more than I pay now.")
        say(sid, "What should I do over the next 3 exchanges with him?")
        val last = replies(sid).last()
        shot("02-plan-ahead")
        last.lines().forEach { android.util.Log.i("E2E_PLAN", it) }
        assertTrue(last, last.contains("Clear-win move:"))
        val steps = 1 + last.lines().count { it.startsWith("• ") && last.indexOf(it) < (last.indexOf("Don't do this:").takeIf { i -> i >= 0 } ?: last.length) }
        assertTrue("three steps: $last", steps >= 3)
        assertFalse("no odds", Regex("""\d+\s?%\s*(chance|likely|probab)""", RegexOption.IGNORE_CASE).containsMatchIn(last))
    }
}
