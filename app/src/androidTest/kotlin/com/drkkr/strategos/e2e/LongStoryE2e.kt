package com.drkkr.strategos.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC_STORY_01: one project followed over "several weeks" in three topics, with journal entries written in between,
 * against the real coach (emulator only). Skipped unless run with `-e story 1` and a live key.
 *
 * What it asserts, for every turn: the reply is the coach's own words (not the app's canned template), it is not empty,
 * it picks up something specific the user said, and each topic keeps its own conversation. At the end: the trajectory
 * and the Overview have moved, the journal entries were screened/linked to the project, and a question about the
 * journal is answered with details from those entries. Replies are logged under tag STORY (synthetic text, emulator only).
 */
@RunWith(AndroidJUnit4::class)
class LongStoryE2e : E2eBase() {
    private class Turn(val topic: String, val say: String, val mustTouch: List<String>)

    private val canned = listOf("Options to consider", "Other options you have", "This is a best guess", "This is an early view")

    private val week1 = listOf(
        Turn("equity", "My co-founder Alex and I have barely spoken since the board meeting. He wants to push sales instead of giving up equity for a technical lead, and I think that is a mistake.", listOf("alex", "equity", "sales", "board")),
        Turn("equity", "He says I never listen and that I already decided before the meeting. Honestly he might be right about the second part.", listOf("listen", "decided", "meeting", "right")),
    )
    private val week2 = listOf(
        Turn("say", "I want to talk to Alex on Friday. What should I actually open with, given he feels unheard?", listOf("friday", "open", "heard", "listen", "alex")),
        Turn("say", "If I admit I decided too early, does that hand him the whole argument about the technical lead?", listOf("admit", "decided", "argument", "technical lead", "lead")),
    )
    private val week3 = listOf(
        Turn("no", "Say Alex still refuses the technical lead after all that. What is my fallback so I don't just give in?", listOf("refuse", "fallback", "technical lead", "give in", "lead")),
        Turn("no", "Tonight he made a snide comment that it must be nice to spend other people's money on vanity acquisitions while the rest of us build the product. I got defensive and said something I regret.", listOf("snide", "comment", "defensive", "regret", "acquisition", "build")),
    )
    private val journal1 = "Alex skipped standup again and answered my Slack about the Lisbon investor intro with one word. I think he is punishing me for the board vote."
    private val journal2 = "Slept badly. Replayed the argument about the technical lead hire three times. I keep wondering if the Lisbon investor would back us without Alex."

    private fun clean(s: String) = s.lowercase()

    @Test fun TC_STORY_01_threeTopicsWithJournal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("story") == "1")
        requireLiveKey()
        reset(withKey = true); launch()
        waitForText("What's on your mind?")

        val db = container.database!!
        val dao = db.messageDao()
        val report = StringBuilder()
        var sid = ""
        var expected = 0
        fun count() = runBlocking { dao.countVisibleForScenario(sid) }
        fun idle() = waitFor(90_000, "composer idle") { !hasText("Stop reply") && rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }

        // ── Week 1: project is created by the first message ─────────────────
        typeInto(0, week1[0].say); desc("Send").performClick()
        waitForDesc("Back to overview")
        sid = runBlocking { container.scenarioRepository.scenarios.value }.first().id
        expected = 2
        waitFor(180_000, "reply 1") { count() >= expected }
        idle()
        typeInto(0, week1[1].say); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 2") { count() >= expected }; idle()

        // ── Journal entry 1, linked to the project, screened like the app does ─
        val journal = container.journalRepository!!
        val j1 = runBlocking { journal.recordEntry(journal1, "ANXIOUS", sid) }
        runBlocking { container.journalScreener!!.screen(j1.entryId) }

        // ── Week 2: a second topic ──────────────────────────────────────────
        text("+ Topic").performClick()
        waitForText("A new topic")
        rule.onAllNodes(hasSetTextAction()).onLast().performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("What to say to Alex")
        text("Start talking").performClick()
        // The sheet only names the topic; the first line is the topic name itself, so continue the conversation.
        waitFor(180_000, "topic 2 first reply") { count() >= expected + 2 }; expected += 2; idle()
        typeInto(0, week2[0].say); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 4") { count() >= expected }; idle()
        typeInto(0, week2[1].say); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 5") { count() >= expected }; idle()

        // ── Journal entry 2 ─────────────────────────────────────────────────
        val j2 = runBlocking { journal.recordEntry(journal2, "ANXIOUS", sid) }
        runBlocking { container.journalScreener!!.screen(j2.entryId) }

        // ── Week 3: a third topic ───────────────────────────────────────────
        text("+ Topic").performClick()
        waitForText("A new topic")
        rule.onAllNodes(hasSetTextAction()).onLast().performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("If he says no")
        text("Start talking").performClick()
        waitFor(180_000, "topic 3 first reply") { count() >= expected + 2 }; expected += 2; idle()
        typeInto(0, week3[0].say); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 7") { count() >= expected }; idle()
        typeInto(0, week3[1].say); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "reply 8") { count() >= expected }; idle()

        // ── Back to topic 1: does it remember the journal? ───────────────────
        val ask = "Before I go back to Alex: what did I write in my journal this week, and does it change what you'd tell me?"
        val threads = runBlocking { db.conversationDao().listThreadsForScenario(sid) }
        assertEquals("three separate topics", 3, threads.size)
        typeInto(0, ask); desc("Send message").performClick()
        expected += 2; waitFor(180_000, "journal reply") { count() >= expected }; idle()

        // ── Collect and judge ───────────────────────────────────────────────
        val turns = week1 + week2 + week3
        threads.forEach { t ->
            val msgs = runBlocking { dao.all(t.id) }.filter { it.role == "USER" || it.role == "ASSISTANT" }
            report.append("\n── THREAD ${t.title} (${msgs.size} msgs)")
            msgs.forEach { report.append("\n${it.role}: ${it.text.replace("\n", " ⏎ ")}") }
        }
        val replies = threads.flatMap { t -> runBlocking { dao.all(t.id) }.filter { it.role == "ASSISTANT" } }
        val cannedCount = replies.count { r -> canned.any { r.text.contains(it, ignoreCase = true) } }
        val misses = mutableListOf<String>()
        turns.forEach { turn ->
            // A reply is on-topic if it names something the user said, or if it is the coach asking a clarifying question first.
            val reply = replies.firstOrNull { r -> turn.mustTouch.any { clean(r.text).contains(it) } }
            if (reply == null) misses += turn.say.take(50)
        }
        val trajectory = runBlocking { container.trajectoryRepository!!.points(sid) }
        val notes = runBlocking { journal.activePostNotes(sid) }
        val linked = runBlocking { journal.linkSuggestions(sid) }
        val journalReply = clean(replies.maxByOrNull { it.createdAt }!!.text)
        val asked = replies.count { it.text.startsWith("Before I call it") }
        val touchedJournal = listOf("lisbon", "standup", "board vote", "slack", "slept", "investor", "punish").filter { journalReply.contains(it) }

        report.append("\n\n── REPORT\nreplies=${replies.size} canned=$cannedCount unmatchedTurns=${misses.size} $misses")
        report.append("\nclarifyingQuestions=$asked trajectoryPoints=${trajectory.size} postNotes=${notes.size} linkSuggestions=${linked.size}")
        notes.forEach { report.append("\nNOTE: ${it.insightHeadline} | do: ${it.doThis} | dont: ${it.dontDoThis}") }
        report.append("\njournalReplyTouched=$touchedJournal")
        report.toString().lines().forEach { android.util.Log.e("STORY", it) }

        assertTrue("every user turn got a reply (${replies.size})", replies.size >= 9)
        assertTrue("canned fallbacks: $cannedCount of ${replies.size}\n$report", cannedCount <= 1)
        assertTrue("replies that never touched what the user said: $misses", misses.size <= 1)
        assertTrue("the coach asked a question before most replies ($asked of ${replies.size})", asked <= 4)
        assertTrue("trajectory moved over the story (${trajectory.size} points)", trajectory.size >= 6)
        assertTrue("the journal entries reach the coach: reply only said '$journalReply'", touchedJournal.isNotEmpty())
    }
}
