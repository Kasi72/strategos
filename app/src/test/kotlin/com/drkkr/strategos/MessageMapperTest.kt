package com.drkkr.strategos

import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.MessageMapper
import com.drkkr.strategos.data.db.entities.ChangeSetEntity
import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMapperTest {
    private var seq = 0
    private fun msg(role: String, text: String = "", callId: String? = null, payload: String? = null, uiKind: String = "NONE", refId: String? = null) =
        MessageEntity("m${++seq}", "c", seq, "t", role, text, callId = callId, payloadJson = payload, uiKind = uiKind, refId = refId, createdAt = 0)

    private val ops = """[{"type":"add_stakeholder","ref":"reg","name":"Regulator","rationale":"They license the site","source":"user_stated","quote":"the regulator can shut us down"}]"""
    private val changeSet = ChangeSetEntity("cs1", "pd", "c", "PENDING", "Add the regulator", ops, """{"reg":"u1"}""", baseScenarioVersion = 1, createdAt = 0)

    @Test
    fun TC_MAP_01_changeSetResultBecomesActivityAndCard() {
        val messages = listOf(
            msg("USER", "Honestly the regulator can shut us down any day"),
            msg("TOOL_CALL", callId = "c1", payload = """{"summary":"Add the regulator","ops":$ops}"""),
            msg("TOOL_RESULT", "Proposed 1 changes", callId = "c1", payload = """{"status":"pending_user_review","change_set_id":"cs1"}""", uiKind = "CHANGE_SET", refId = "cs1"),
            msg("ASSISTANT", "Review the card.")
        )
        val items = MessageMapper.toChatItems(messages, listOf(changeSet), Fx.pd())
        assertEquals(listOf(ChatItem.UserText::class, ChatItem.Activity::class, ChatItem.ChangeSetCard::class, ChatItem.AssistantText::class), items.map { it::class })
        assertEquals(listOf("Proposed 1 changes"), (items[1] as ChatItem.Activity).steps)
        val card = (items[2] as ChatItem.ChangeSetCard).ui
        assertEquals("Add the regulator", card.summary)
        assertEquals("Add Regulator (influencer)", card.rows.single().text)
        assertTrue(card.rows.single().userStated)
        // No raw tool JSON anywhere in the visible text.
        val visible = items.joinToString { it.toString() }
        assertFalse(visible.contains("pending_user_review"))
        assertFalse(visible.contains("\"ops\""))
    }

    @Test
    fun consecutiveResultsMergeAndQuestionsAnsweredLater() {
        val messages = listOf(
            msg("USER", "Hi"),
            msg("TOOL_CALL", callId = "a"), msg("TOOL_RESULT", "Read the model", callId = "a", payload = "{}"),
            msg("TOOL_CALL", callId = "b"), msg("TOOL_RESULT", "Updated memory", callId = "b", payload = "{}"),
            msg("TOOL_CALL", callId = "q", payload = """{"questions":[{"id":"q1","text":"Who decides?","options":["Me","Them"]}]}"""),
            msg("TOOL_RESULT", "Asked a question", callId = "q", payload = """{"status":"shown"}""", uiKind = "QUESTIONS"),
            msg("TOOL_RESULT", "Tool error", callId = "x", payload = """{"error":"internal_error"}""", uiKind = "RECOMMENDATION")
        )
        val items = MessageMapper.toChatItems(messages, emptyList(), null)
        assertEquals(listOf("Read the model", "Updated memory", "Asked a question"), (items[1] as ChatItem.Activity).steps)
        val q = items[2] as ChatItem.Questions
        assertFalse(q.answered)
        assertEquals(listOf("Me", "Them"), q.questions.single().options)
        assertTrue(items.none { it is ChatItem.Recommendation })   // error output → no card

        val answered = MessageMapper.toChatItems(messages + msg("USER", "Re: Who decides? → Me"), emptyList(), null)
        assertTrue(answered.filterIsInstance<ChatItem.Questions>().single().answered)
    }

    @Test
    fun askWithFiveOptionsShowsFour() {
        val messages = listOf(
            msg("USER", "Hi"),
            msg("TOOL_CALL", callId = "a", payload = """{"ops":[],"ask":{"text":"What matters most?","options":["a","b","c","d","e"]}}"""),
            msg("TOOL_RESULT", "Asked a question", callId = "a", payload = """{"status":"asked"}""", uiKind = "QUESTIONS")
        )
        val q = MessageMapper.toChatItems(messages, emptyList(), null).filterIsInstance<ChatItem.Questions>().single()
        assertEquals(listOf("a", "b", "c", "d"), q.questions.single().options)
    }
}
