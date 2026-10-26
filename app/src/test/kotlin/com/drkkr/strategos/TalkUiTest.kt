package com.drkkr.strategos

import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.QuestionUi
import com.drkkr.strategos.ui.calm.talk.TalkTurn
import com.drkkr.strategos.ui.calm.talk.TalkUi
import org.junit.Assert.*
import org.junit.Test

class TalkUiTest {
    @Test fun TC_TALK_01_tagParsesThreePartPill() {
        val t = TalkUi.tag("Wait · Mutual trust needed · Robust")!!
        assertEquals("Wait", t.move); assertEquals("Mutual trust needed", t.archetype); assertTrue(t.robust)
        assertFalse(TalkUi.tag("Co-sign · Chicken · Fragile")!!.robust)
        assertNull(TalkUi.tag("  "))
    }

    @Test fun TC_TALK_02_splitFindsLeadBodyAndClosing() {
        val c = TalkUi.split("Let him ask.\n\nKeep your answer the same and give it a day.\n\nWhat would help him feel respected?")
        assertEquals("Let him ask.", c.lead); assertEquals("Keep your answer the same and give it a day.", c.body)
        assertEquals("What would help him feel respected?", c.closing)
    }

    @Test fun TC_TALK_03_splitCollectsBullets() {
        val c = TalkUi.split("Here is what I'd do.\n\n- Say it once\n- Give a day")
        assertEquals(listOf("Say it once", "Give a day"), c.bullets)
        assertEquals("Here is what I'd do.", c.lead.ifEmpty { c.body })
    }

    @Test fun TC_TALK_04_splitNeverLosesWords() {
        val raw = "One short reply."
        val c = TalkUi.split(raw)
        assertEquals(raw, listOf(c.lead, c.body).filter { it.isNotEmpty() }.joinToString(" "))
    }

    @Test fun TC_TALK_05_turnsMapItemsAndAppendQueuedLast() {
        val items = listOf(
            ChatItem.UserText("u1", "hi"),
            ChatItem.AssistantText("a1", "Wait.", pill = "Wait · Chicken · Robust"),
            ChatItem.Questions("q1", listOf(QuestionUi("x", "Who?", listOf("Mum", "Dad"), true)), answered = false)
        )
        val t = TalkUi.turns(items, queued = listOf("later"))
        assertEquals(4, t.size)
        assertTrue(t[1] is TalkTurn.Coach && (t[1] as TalkTurn.Coach).tag != null)
        assertTrue((t[2] as TalkTurn.Ask).options.size == 2)
        assertTrue((t[3] as TalkTurn.User).queued)
    }

    @Test fun TC_TALK_06_whenLabelBuckets() {
        val now = 10_000_000_000L
        assertEquals("now", TalkUi.whenLabel(now, now))
        assertEquals("5m", TalkUi.whenLabel(now - 5 * 60_000, now))
        assertEquals("2h", TalkUi.whenLabel(now - 2 * 3_600_000, now))
        assertEquals("1d", TalkUi.whenLabel(now - 26 * 3_600_000, now))
    }
}
