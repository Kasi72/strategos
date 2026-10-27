package com.drkkr.strategos.e2e

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic, not a regression test: skipped unless run with `-e probe 1`. Sends a series of messages to the real coach
 * through the Talk screen and logs, for each reply, which path produced it (the coach's own words or the app's canned
 * fallback). Replies are logged under the tag PROBE on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class ConversationProbe : E2eBase() {
    private val messages = listOf(
        "My co-founder Alex and I have barely spoken since the board meeting. He wants to push sales instead of giving up equity for a technical lead, and I think that is a mistake.",
        "He says I never listen and that I already decided before the meeting. Honestly he might be right about the second part.",
        "Tonight while we were working late, he made a snide comment about how \"nice it must be to spend other people's money on vanity acquisitions while the rest of us actually build the product.\" I got so defensive and exhausted from months of this that I said something I regret."
    )

    private fun visibleCount(sid: String) = runBlocking { container.database!!.messageDao().countVisibleForScenario(sid) }

    @Test fun probe_threeMessageConversation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probe") == "1")
        requireLiveKey()
        reset(withKey = true); launch()
        waitForText("What's on your mind?")
        typeInto(0, messages[0]); desc("Send").performClick()
        waitForDesc("Back to overview")
        val sid = runBlocking { container.scenarioRepository.scenarios.value }.first().id
        var expect = 2
        waitFor(180_000, "reply 1") { visibleCount(sid) >= expect }
        for (i in 1 until messages.size) {
            waitFor(60_000, "composer idle") { !hasText("Stop reply") && rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            typeInto(0, messages[i]); desc("Send message").performClick()
            expect += 2
            waitFor(180_000, "reply ${i + 1}") { visibleCount(sid) >= expect }
        }
        val dao = container.database!!.messageDao()
        val cid = runBlocking { container.database!!.conversationDao().listThreadsForScenario(sid) }.first().id
        runBlocking { dao.all(cid) }.filter { it.role == "ASSISTANT" || it.role == "USER" }.forEach {
            android.util.Log.e("PROBE", "${it.role}: ${it.text.replace("\n", " ⏎ ").take(900)}")
        }
    }
}
