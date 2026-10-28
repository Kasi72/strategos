package com.drkkr.strategos.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: ask, answer the coach's clarifying questions, and log which path produced the first real answer. Run with `-e probe 2`. */
@RunWith(AndroidJUnit4::class)
class FirstAnswerProbe : E2eBase() {
    private val say = listOf(
        "My business partner Jordan keeps committing our small design studio to rush client projects over the weekend without asking me first, and then leaves me to do 80% of the weekend production work. Today he slacked me that he promised a client a full pitch deck by Monday morning. I'm exhausted and want to tell him he can do the whole deck himself or I'm quitting, or try talking to him warmly about protecting our weekend bandwidth.",
        "1. Usually when I bring things up gently, he talks it through and agrees in the moment, though lately he's been brushing it off and forgetting by the next week.\n2. Keeping the partnership intact matters a lot to me because we built this studio together, so I want to give collaboration a fair shot first—but I still need a real rule in place so I don't burn out."
    )

    @Test fun probe_firstRealAnswer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probe") == "2")
        requireLiveKey()
        reset(withKey = true); launch()
        waitForText("What's on your mind?")
        val dao = container.database!!.messageDao()
        var sid = ""
        fun count() = runBlocking { dao.countVisibleForScenario(sid) }
        fun idle() = waitFor(90_000, "composer idle") { !hasText("Stop reply") && rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        typeInto(0, say[0]); desc("Send").performClick()
        waitForDesc("Back to overview")
        sid = runBlocking { container.scenarioRepository.scenarios.value }.first().id
        var expected = 2
        waitFor(180_000, "reply 1") { count() >= expected }; idle()
        typeInto(0, say[1]); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 2") { count() >= expected }; idle()
        val cid = runBlocking { container.database!!.conversationDao().listThreadsForScenario(sid) }.first().id
        runBlocking { dao.all(cid) }.filter { it.role == "ASSISTANT" }.forEachIndexed { i, r ->
            android.util.Log.e("PROBE2", "REPLY ${i + 1}: ${r.text.replace("\n", " ⏎ ").take(700)}")
        }
    }
}
