package com.drkkr.strategos

import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.agent.MessageMapper
import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.a2ui.A2ui
import com.drkkr.strategos.domain.a2ui.A2uiReader
import com.drkkr.strategos.domain.a2ui.Surface
import com.drkkr.strategos.domain.a2ui.SurfaceCodec
import com.drkkr.strategos.domain.a2ui.SurfaceWriter
import com.drkkr.strategos.domain.a2ui.TurnCards
import com.drkkr.strategos.domain.a2ui.UiAction
import com.drkkr.strategos.domain.a2ui.UiNode
import com.drkkr.strategos.domain.files.FileBlock
import com.drkkr.strategos.domain.files.FileIngest
import com.drkkr.strategos.domain.files.IngestResult
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.intent.NextQuestion
import com.drkkr.strategos.domain.intent.TopOption
import com.drkkr.strategos.domain.intent.TopOptions
import com.drkkr.strategos.ui.calm.talk.TalkTurn
import com.drkkr.strategos.ui.calm.talk.TalkUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Cards in the chat: the A2UI 1.0 messages the app writes, how strictly it reads them, and when each card appears. */
class TurnCardsTest {

    private fun read(json: String): List<Surface> = A2uiReader.surfaces(Json.parseToJsonElement(json).jsonArray)
    private fun texts(n: UiNode): List<String> = when (n) {
        is UiNode.Card -> texts(n.child)
        is UiNode.Column -> n.children.flatMap(::texts)
        is UiNode.Row -> n.children.flatMap(::texts)
        is UiNode.Text -> listOf(n.text)
        is UiNode.Button -> listOf(n.label)
        is UiNode.Option -> listOf(n.tag, n.title, n.detail)
        is UiNode.Step -> listOf(n.label, n.title, n.detail)
        is UiNode.Divider -> emptyList()
    }
    private fun surface(messages: JsonArray): Surface = A2uiReader.surfaces(messages).single()
    private fun create(catalog: String = A2ui.CATALOG) = """{"version":"1.0","createSurface":{"surfaceId":"s","catalogId":"$catalog"}}"""
    private fun components(vararg c: String, version: String = "1.0") = """{"version":"$version","updateComponents":{"surfaceId":"s","components":[${c.joinToString(",")}]}}"""

    // ---- The message format ----

    @Test fun TC_CARD_01_theWriterProducesStandardA2uiMessages() {
        val w = SurfaceWriter("demo")
        w.card(w.column(listOf(w.text("Title", "h3"), w.button("Go", UiAction(A2ui.REPLY, mapOf("text" to "Yes"))))), id = A2ui.ROOT)
        val m = w.messages()
        assertEquals(2, m.size)
        assertTrue("every message carries the version", m.all { it.jsonObject["version"]!!.jsonPrimitive.content == "1.0" })
        val created = m[0].jsonObject["createSurface"]!!.jsonObject
        assertEquals("demo", created["surfaceId"]!!.jsonPrimitive.content); assertEquals(A2ui.CATALOG, created["catalogId"]!!.jsonPrimitive.content)
        val comps = m[1].jsonObject["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        assertTrue("a flat list, each with an id and a component name", comps.all { "id" in it && "component" in it })
        val root = comps.single { it["id"]!!.jsonPrimitive.content == "root" }
        assertEquals("Card", root["component"]!!.jsonPrimitive.content); assertTrue("a card points at its one child by id", root["child"] is kotlinx.serialization.json.JsonPrimitive)
        val button = comps.single { it["component"]!!.jsonPrimitive.content == "Button" }
        val event = button["action"]!!.jsonObject["event"]!!.jsonObject
        assertEquals("reply", event["name"]!!.jsonPrimitive.content); assertEquals("Yes", event["context"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("a button's label is a Text child", comps.any { it["id"] == button["child"] && it["component"]!!.jsonPrimitive.content == "Text" })
    }

    @Test fun TC_CARD_02_aWrittenCardReadsBackAsTheSameTree() {
        val w = SurfaceWriter("demo")
        w.card(w.column(listOf(w.text("Title", "h3"), w.option("Safest", "Wait a week", "Least to lose", UiAction(A2ui.REPLY, mapOf("text" to "more"))), w.divider(), w.step("3 Mar", "4 messages", "Ravi 3 · You 1"))), id = A2ui.ROOT)
        val s = SurfaceCodec.decode(SurfaceCodec.encode(w.messages()))!!
        assertEquals("demo", s.id)
        val col = (s.root as UiNode.Card).child as UiNode.Column
        assertEquals(listOf("Text", "Option", "Divider", "Step"), col.children.map { it::class.simpleName })
        assertEquals(listOf(UiAction("reply", mapOf("text" to "more"))), s.actions())
        assertTrue(SurfaceCodec.encode(w.messages()).let { Json.parseToJsonElement(it) is JsonObject })
    }

    @Test fun TC_CARD_03_boundTextIsReadFromTheDataModel() {
        val s = read("""[${create()},
            ${components("""{"id":"root","component":"Column","children":["a","b"]}""", """{"id":"a","component":"Text","text":{"@path":"/person/name"}}""", """{"id":"b","component":"Text","text":{"@path":"/missing"}}""")},
            {"version":"1.0","updateDataModel":{"surfaceId":"s","path":"/person","value":{"name":"Ravi","age":4}}},
            {"version":"1.0","updateDataModel":{"surfaceId":"s","path":"/person/age","value":null}}]""").single()
        assertEquals(listOf("Ravi", ""), texts(s.root))
    }

    @Test fun TC_CARD_04_aLaterUpdateReplacesAComponentAndDeleteRemovesTheSurface() {
        val base = """${create()},${components("""{"id":"root","component":"Text","text":"old"}""")}"""
        assertEquals(listOf("new"), texts(read("[$base,${components("""{"id":"root","component":"Text","text":"new"}""")}]").single().root))
        assertTrue(read("""[$base,{"version":"1.0","deleteSurface":{"surfaceId":"s"}}]""").isEmpty())
    }

    @Test fun TC_CARD_05_anythingNotFullyUnderstoodIsNotDrawn() {
        val root = """{"id":"root","component":"Column","children":["a"]}"""
        val ok = """{"id":"a","component":"Text","text":"hi"}"""
        assertEquals(1, read("[${create()},${components(root, ok)}]").size)
        assertTrue("a component outside the catalog", read("[${create()},${components(root, """{"id":"a","component":"WebView","url":"http://x"}""")}]").isEmpty())
        assertTrue("another catalog", read("[${create("https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json")},${components(root, ok)}]").isEmpty())
        assertTrue("a child that does not exist", read("[${create()},${components(root)}]").isEmpty())
        assertTrue("no root", read("[${create()},${components(ok)}]").isEmpty())
        assertTrue("a loop", read("[${create()},${components("""{"id":"root","component":"Card","child":"a"}""", """{"id":"a","component":"Card","child":"root"}""")}]").isEmpty())
        assertTrue("another version's messages are ignored", read("[${create()},${components(root, ok, version = "0.9")}]").isEmpty())
        assertTrue("components for a surface never created", read("[${components(root, ok)}]").isEmpty())
        val deep = ((0..12).map { i -> """{"id":"${if (i == 0) "root" else "n$i"}","component":"Card","child":"n${i + 1}"}""" } + """{"id":"n13","component":"Text","text":"x"}""").joinToString(",")
        assertTrue("too deep", read("[${create()},${components(deep)}]").isEmpty())
        assertTrue("not JSON at all", A2uiReader.surfaces("not json").isEmpty())
        assertNull(SurfaceCodec.decode(null))
    }

    @Test fun TC_CARD_06_veryLongTextIsCut() {
        val s = read("[${create()},${components("""{"id":"root","component":"Text","text":"${"x".repeat(5_000)}"}""")}]").single()
        assertEquals(A2ui.MAX_TEXT, (s.root as UiNode.Text).text.length)
    }

    // ---- Which card, when ----

    private val engage = NextQuestion(IdentifyGaps.THEIR_REPLY, IdentifyGaps.TEMPLATES.getValue(IdentifyGaps.THEIR_REPLY).replace("{name}", "Ravi"))
    private val top = listOf(
        TopOption(TopOptions.BEST, "Offer a fixed amount", TopOptions.TRADEOFFS.getValue(TopOptions.BEST)),
        TopOption(TopOptions.INSURANCE, "Wait a week", TopOptions.TRADEOFFS.getValue(TopOptions.INSURANCE))
    )

    @Test fun TC_CARD_07_quickAnswersSendAPlainSentenceNamingThePerson() {
        val s = surface(TurnCards.quickAnswers(engage, "Ravi")!!.messages)
        val replies = s.actions()
        assertEquals(3, replies.size); assertTrue(replies.all { it.isReply && it.text.isNotBlank() })
        assertTrue(replies[0].text.contains("Ravi") && replies[0].text.contains("engages"))
        assertTrue(replies[1].text.contains("digs in"))
        val decider = surface(TurnCards.quickAnswers(NextQuestion(IdentifyGaps.DECIDER, "Does Ravi make the final call, or does someone else decide?"), "Ravi")!!.messages)
        assertEquals(listOf("Quick answer", "Ravi decides", "Someone else", "Not sure"), texts(decider.root))
    }

    @Test fun TC_CARD_08_openQuestionsAndUnnamedPeopleGetNoQuickAnswers() {
        assertNull(TurnCards.quickAnswers(NextQuestion(IdentifyGaps.WALK_AWAY, "Where's the point you'd say no and walk away?"), "Ravi"))
        assertNull(TurnCards.quickAnswers(NextQuestion("goal", "What would a good outcome look like for you?"), "Ravi"))
        assertNull(TurnCards.quickAnswers(engage, null)); assertNull(TurnCards.quickAnswers(engage, " "))
    }

    @Test fun TC_CARD_09_aCardOnlyOffersWhatTheReplySaid() {
        val reply = "Offer a fixed amount is the engine's pick. Wait a week is the safest.\n\n${engage.text}"
        val both = TurnCards.underReply(reply, engage, "Ravi", top, listOf("Ravi: involved"))
        assertEquals(listOf(TurnCards.QUICK_ANSWERS, TurnCards.OPTIONS), both.map { it.kind })
        // The reply fell back to the engine's own text: no question in it and the options not named.
        val fallback = TurnCards.underReply("Here is where things stand.", engage, "Ravi", top, listOf("Ravi: involved"))
        assertEquals(listOf(TurnCards.PICKED_UP), fallback.map { it.kind })
        assertTrue(TurnCards.underReply("Here is where things stand.", engage, "Ravi", top, emptyList()).isEmpty())
        assertTrue(TurnCards.underReply(reply, null, null, null, emptyList()).isEmpty())
    }

    @Test fun TC_CARD_10_theOptionsCardNamesEachRoleAndAsksForMoreOnATap() {
        val s = surface(TurnCards.options(top)!!.messages)
        assertEquals(listOf("Your options", "Best balance", "Offer a fixed amount", "", "Safest", "Wait a week", ""), texts(s.root))
        assertEquals("Tell me more about this option: Wait a week", s.actions().last().text)
        assertNull("one option is not a choice", TurnCards.options(top.take(1)))
    }

    @Test fun TC_CARD_11_whatWasPickedUpIsListedWithAWayToFixIt() {
        val s = surface(TurnCards.pickedUp(listOf("Ravi: makes a choice that matters here", "Your options: Wait, Ask", "", "Ravi: makes a choice that matters here") + (1..6).map { "Line $it" })!!.messages)
        val t = texts(s.root)
        assertEquals("What I picked up", t.first()); assertEquals("Check or fix", t.last())
        assertEquals("five lines, then a count of the rest", "and 3 more", t[t.size - 2])
        assertEquals(listOf(UiAction(A2ui.OPEN_OVERVIEW)), s.actions())
        assertNull(TurnCards.pickedUp(listOf(" ", "")))
    }

    private fun chat(): String = buildString {
        for (d in 1..9) { append("0$d/03/2026, 09:1$d - Ravi: message $d about the loan\n"); append("0$d/03/2026, 21:1$d - You: reply $d\n") }
        append("20/03/2026, 10:00 - Ravi: are you there?\n")
    }

    @Test fun TC_CARD_12_aSharedConversationGetsATimelineFromItsBrief() {
        val brief = (FileIngest.prepare("WhatsApp Chat with Ravi.txt", chat().toByteArray()) as IngestResult.Ready).brief
        val file = FileBlock.files(FileBlock.compose("What do you make of this?", brief)).single()
        val s = surface(TurnCards.timeline(file)!!.messages)
        val steps = ((s.root as UiNode.Card).child as UiNode.Column).children.filterIsInstance<UiNode.Step>()
        assertTrue("one step per day or week, capped", steps.size in 2..8)
        assertTrue(steps.all { it.label.isNotBlank() && Regex("""\d+ (messages?|entr(y|ies))""").matches(it.title) })
        assertTrue("who spoke, without the list punctuation", steps.first().detail.contains("Ravi") && !steps.first().detail.contains(","))
        val t = texts(s.root)
        assertEquals("How it unfolded", t[0]); assertEquals("WhatsApp Chat with Ravi.txt", t[1])
        assertTrue("the ten quiet days are called out", t.contains("Long silences") && t.any { it.startsWith("10 days") })
        assertTrue("a timeline has nothing to tap", s.actions().isEmpty())
    }

    @Test fun TC_CARD_13_aFileWithNoTimelineGetsNoCard() {
        val table = (FileIngest.prepare("pipeline.csv", "Deal,Amount\nAcme,10\nBeta,20\n".toByteArray()) as IngestResult.Ready).brief
        assertNull(TurnCards.timeline(FileBlock.files(FileBlock.compose("", table)).single()))
    }

    @Test fun TC_CARD_14_noFixedCardWordingUsesABannedWord() {
        val cards = listOfNotNull(TurnCards.quickAnswers(engage, "Ravi"), TurnCards.quickAnswers(NextQuestion(IdentifyGaps.DECIDER, "x"), "Ravi"), TurnCards.options(top), TurnCards.pickedUp(listOf("a")))
        cards.forEach { c -> (texts(surface(c.messages).root) + surface(c.messages).actions().map { it.text }).forEach { assertFalse(it, BannedWords.containedIn(it)) } }
    }

    // ---- In the chat ----

    private fun card(seq: Int, kind: String, payload: String) =
        MessageEntity("m$seq", "c", seq, "t", MessageRole.TOOL_RESULT.name, "Card", toolName = kind, callId = "t", payloadJson = payload, uiKind = UiKind.SURFACE.name, createdAt = seq.toLong())
    private fun said(seq: Int, role: MessageRole, text: String) = MessageEntity("m$seq", "c", seq, "t", role.name, text, createdAt = seq.toLong())

    @Test fun TC_CARD_15_aSavedCardShowsUnderTheReplyAndRetiresOnceAnswered() {
        val payload = SurfaceCodec.encode(TurnCards.quickAnswers(engage, "Ravi")!!.messages)
        val before = listOf(said(1, MessageRole.USER, "hi"), said(2, MessageRole.ASSISTANT, "Reply. ${engage.text}"), card(3, TurnCards.QUICK_ANSWERS, payload))
        val turns = TalkUi.turns(MessageMapper.toChatItems(before, emptyList(), null))
        assertEquals(listOf("User", "Coach", "Surface"), turns.map { it::class.simpleName })
        assertFalse((turns.last() as TalkTurn.Surface).answered)
        val after = TalkUi.turns(MessageMapper.toChatItems(before + said(4, MessageRole.USER, "Ravi usually engages."), emptyList(), null))
        assertTrue((after[2] as TalkTurn.Surface).answered)
    }

    @Test fun TC_CARD_16_aCardThatCannotBeReadLeavesTheChatAsItWas() {
        val broken = listOf(said(1, MessageRole.USER, "hi"), card(2, "x", """{"messages":[{"version":"1.0","createSurface":{"surfaceId":"s","catalogId":"other"}}]}"""), card(3, "y", "{}"), card(4, "z", "not json"))
        assertEquals(listOf("UserText"), MessageMapper.toChatItems(broken, emptyList(), null).filter { it !is ChatItem.Activity }.map { it::class.simpleName })
    }
}
