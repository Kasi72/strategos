package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.entities.ChangeSetEntity
import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeOpDescriber
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.QuoteVerifier
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Turns stored messages into chat items (blueprint §10.6). Pure. Raw tool JSON is never shown:
 * TOOL_CALL messages are dropped and TOOL_RESULT messages become activity steps plus, for rich kinds, a card.
 */
object MessageMapper {

    // BLUEPRINT-QUESTION: §10.6 passes `whatIfs`; the comparison already sits in the TOOL_RESULT payload, so the
    // scenario (for change-set row names) is passed instead.
    fun toChatItems(messages: List<MessageEntity>, changeSets: List<ChangeSetEntity>, scenario: Scenario?): List<ChatItem> {
        val sorted = messages.sortedBy { it.seq }
        val callPayloads = sorted.filter { it.role == MessageRole.TOOL_CALL.name && it.callId != null }
            .associate { it.callId!! to it.payloadJson }
        val userTexts = sorted.filter { it.role == MessageRole.USER.name }.map { it.text }
        val changeSetById = changeSets.associateBy { it.id }
        val lastSeq = sorted.lastOrNull()?.seq
        val items = mutableListOf<ChatItem>()
        var activity: ChatItem.Activity? = null

        fun flushActivity() {
            activity?.let { items += it }
            activity = null
        }
        fun answeredAfter(m: MessageEntity) = sorted.any { it.seq > m.seq && it.role == MessageRole.USER.name }

        for (m in sorted) {
            when (m.role) {
                MessageRole.TOOL_CALL.name -> Unit
                MessageRole.TOOL_RESULT.name -> {
                    activity = activity?.let { it.copy(steps = it.steps + m.text) } ?: ChatItem.Activity(m.id, listOf(m.text))
                    val output = parseObject(m.payloadJson)
                    if (output == null || "error" in output) continue
                    val card: ChatItem? = when (m.uiKind) {
                        UiKind.CHANGE_SET.name -> m.refId?.let(changeSetById::get)?.let { ChatItem.ChangeSetCard(m.id, changeSetUi(it, scenario, userTexts)) }
                        UiKind.QUESTIONS.name -> ChatItem.Questions(m.id, questions(callPayloads[m.callId]), answeredAfter(m))
                        UiKind.RECOMMENDATION.name -> ChatItem.Recommendation(m.id, output)
                        UiKind.WHAT_IF.name -> ChatItem.WhatIf(m.id, output, m.refId.orEmpty())
                        UiKind.STRESS_TEST.name -> ChatItem.StressTest(m.id, output)
                        UiKind.UNDERSTANDING.name -> ChatItem.Understanding(m.id, strings(parseObject(callPayloads[m.callId])?.get("bullets")), answeredAfter(m))
                        UiKind.SUPPORT.name -> ChatItem.Support(m.id)
                        else -> null
                    }
                    if (card != null) {
                        flushActivity()
                        items += card
                    }
                }
                else -> {
                    flushActivity()
                    when (m.role) {
                        MessageRole.USER.name -> items += ChatItem.UserText(m.id, m.text)
                        MessageRole.ASSISTANT.name -> items += ChatItem.AssistantText(
                            m.id, m.text, pill = m.payloadJson?.let { com.drkkr.strategos.domain.agent.GuidanceCodec.decode(it) }?.pill?.takeIf { it.isNotBlank() }
                        )
                        MessageRole.EVENT.name -> items += ChatItem.EventNote(m.id, m.text)
                        MessageRole.ERROR.name -> items += ChatItem.Error(m.id, m.text, retryable = m.seq == lastSeq)
                    }
                }
            }
        }
        flushActivity()
        return items
    }

    fun changeSetUi(cs: ChangeSetEntity, scenario: Scenario?, userTexts: List<String>): ChangeSetUi {
        val ops = decodeOps(cs.opsJson)
        val base = scenario ?: Scenario(title = "")
        val rows = ops.mapIndexed { i, op ->
            OpRowUi(
                index = i,
                text = ChangeOpDescriber.describe(op, base, ops),
                rationale = op.rationale.orEmpty(),
                userStated = op.source == OpSource.USER_STATED && QuoteVerifier.isVerified(op.quote, userTexts)
            )
        }
        return ChangeSetUi(cs.id, cs.summary, cs.status, rows)
    }

    fun decodeOps(json: String): List<ChangeOp> =
        runCatching { ChangeOp.json.decodeFromString(ListSerializer(ChangeOp.serializer()), json) }.getOrDefault(emptyList())

    private fun questions(argsJson: String?): List<QuestionUi> {
        val args = parseObject(argsJson)
        // LEAN PROFILE: a Guided propose_changes carries one question in `ask`.
        (args?.get("ask") as? JsonObject)?.let { ask ->
            val text = str(ask["text"]) ?: return emptyList()
            // The model sometimes sends 5 options; show the first 4 rather than failing the whole save.
            return listOf(QuestionUi("ask", text, strings(ask["options"]).take(MAX_OPTIONS), allowFreeText = true))
        }
        val arr = args?.get("questions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val q = el as? JsonObject ?: return@mapNotNull null
            QuestionUi(
                id = str(q["id"]) ?: return@mapNotNull null,
                text = str(q["text"]) ?: return@mapNotNull null,
                options = strings(q["options"]),
                allowFreeText = (q["allow_free_text"] as? JsonPrimitive)?.booleanOrNull ?: true
            )
        }
    }

    private const val MAX_OPTIONS = 4

    private fun parseObject(json: String?): JsonObject? = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun str(e: Any?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(e: Any?): List<String> = (e as? JsonArray)?.mapNotNull(::str).orEmpty()
}
