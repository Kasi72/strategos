package com.drkkr.strategos.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC_STORY_02: a reported bug. When the engine's pick stays the same across turns, the fourth reply repeated the third and
 * ignored the new message. Live coach, emulator only; skipped unless run with `-e story 1` and a key.
 */
@RunWith(AndroidJUnit4::class)
class RepeatedAdviceE2e : E2eBase() {
    private val say = listOf(
        "My older brother Sam and I haven't spoken since Sunday, when I told him I want to buy him out of our late parents' house to keep it in the family. He wants to sell to a developer immediately. When I raised it he stared at the floor, said I think I can fix everything with my salary, and walked out. I haven't signed anything because I want us to agree, but I'm scared he'll stonewall me.",
        "Context for your questions: it is pure urgency and debt. He has heavy credit card and business debts and the developer's cash offer would clear them. He has zero attachment to the house and thinks my attachment is childish.",
        "Tonight at dinner he said it must be nice to play the hero and keep the house while he took care of Mom and Dad for three years. I'm ready to go list every medical bill I paid and every flight, and call him a hypocrite.",
        "I held back from listing the bills and instead asked why he's punishing me for wanting to keep our home. It still blew up. He said he feels invisible in this family, he's done talking for the weekend, and slammed the door. My chest is tight like when I had panic attacks two years ago. I'm safe, just shaken, and I want to knock on his door and force him to finish this tonight so I can sleep."
    )
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z']+")).filter { it.length > 3 }.toSet()
    private fun lines(s: String) = s.lines().map { it.trim().trimStart('•', '-', ' ') }.filter { it.length > 25 }.toSet()

    @Test fun TC_STORY_02_fourthReplyIsNotTheThirdAgain() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("story") == "1")
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
        for (i in 1 until say.size) {
            typeInto(0, say[i]); desc("Send message").performClick()
            expected += 2; waitFor(180_000, "reply ${i + 1}") { count() >= expected }; idle()
        }
        val cid = runBlocking { container.database!!.conversationDao().listThreadsForScenario(sid) }.first().id
        val replies = runBlocking { dao.all(cid) }.filter { it.role == "ASSISTANT" }.map { it.text }
        replies.forEachIndexed { i, r -> android.util.Log.e("STORY2", "REPLY ${i + 1}: ${r.replace("\n", " ⏎ ")}") }
        val third = replies[2]; val fourth = replies[3]
        val sharedLines = lines(third).intersect(lines(fourth))
        val overlap = words(third).intersect(words(fourth)).size.toDouble() / words(fourth).size.coerceAtLeast(1)
        val touched = listOf("invisible", "door", "slam", "panic", "chest", "sleep", "tonight", "therap", "ground", "weekend", "shaken").filter { fourth.lowercase().contains(it) }
        android.util.Log.e("STORY2", "sharedLines=$sharedLines wordOverlap=$overlap touched=$touched")
        assertTrue("fourth reply repeats whole lines from the third: $sharedLines", sharedLines.isEmpty())
        assertTrue("fourth reply is mostly the third again (overlap $overlap)", overlap < 0.6)
        assertTrue("fourth reply ignores the new message: $fourth", touched.size >= 2)
    }
}
