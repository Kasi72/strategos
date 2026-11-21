package com.drkkr.strategos.server

import com.drkkr.strategos.server.pipeline.OptionCard
import com.drkkr.strategos.server.pipeline.QuickAnswer
import com.drkkr.strategos.server.pipeline.ReplyCards
import com.drkkr.strategos.server.persistence.StoreJson
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The contract the web UI reads: every field the page touches is present and the right shape, on every turn, across a
 * long conversation. Written after a stored card with no `options` key blanked the whole page (the encoder dropped empty
 * lists); the page now tolerates that, and the server no longer writes it.
 */
class WebContractTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    private suspend fun HttpClient.startChat(text: String) = post("/api/chats") {
        contentType(ContentType.Application.Json); setBody(buildJsonObject { put("message", text) }.toString())
    }

    private suspend fun HttpClient.say(threadId: String, text: String) = post("/api/threads/$threadId/messages") {
        contentType(ContentType.Application.Json); setBody(buildJsonObject { put("message", text) }.toString())
    }

    /** Every turn has what the page reads, and any stored cards carry both lists. */
    private fun assertTurnShape(t: JsonObject) {
        for (k in listOf("id", "threadId", "projectId", "userMessage", "assistantReply")) assertTrue("turn.$k is a non-empty string", t[k]!!.jsonPrimitive.content.isNotBlank())
        for (k in listOf("trustScore", "tensionScore")) assertTrue("turn.$k in 0..100", t[k]!!.jsonPrimitive.double in 0.0..100.0)
        assertTrue("turn.timestamp", t["timestamp"]!!.jsonPrimitive.long > 0)
        val cards = t["cardsJson"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        if (cards != null) {
            val c = json(cards).jsonObject
            assertTrue("stored cards always carry quickAnswers: $cards", c["quickAnswers"] is JsonArray)
            assertTrue("stored cards always carry options: $cards", c["options"] is JsonArray)
            c["quickAnswers"]!!.jsonArray.forEach { q -> assertTrue(q.jsonObject["label"]!!.jsonPrimitive.content.isNotBlank()); assertTrue(q.jsonObject["text"]!!.jsonPrimitive.content.isNotBlank()) }
            c["options"]!!.jsonArray.forEach { o -> assertTrue(o.jsonObject["move"]!!.jsonPrimitive.content.isNotBlank()) }
        }
        t["engineDirectiveJson"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { d ->
            assertTrue("the directive is a JSON object", json(d) is JsonObject)
            assertFalse("no raw ids leak into the directive the page shows", Fixtures.hasUuid(json(d).jsonObject["move_to_implement"]?.jsonPrimitive?.contentOrNull.orEmpty()))
        }
    }

    @Test
    fun `cards are stored with both lists even when one is empty`() {
        val onlyAnswers = ReplyCards(quickAnswers = listOf(QuickAnswer("Usually engages", "Maya usually engages.")))
        val s = StoreJson.encodeToString(ReplyCards.serializer(), onlyAnswers)
        val o = json(s).jsonObject
        assertEquals(JsonArray(emptyList()), o["options"])
        assertEquals(1, o["quickAnswers"]!!.jsonArray.size)
        val onlyOptions = StoreJson.encodeToString(ReplyCards.serializer(), ReplyCards(options = listOf(OptionCard("Best", "Talk", ""))))
        assertEquals(JsonArray(emptyList()), json(onlyOptions).jsonObject["quickAnswers"])
    }

    @Test
    fun `a long conversation keeps every turn readable by the page`() {
        val app = Fixtures.container(ScriptedLlm(listOf(
            Fixtures.personal(),
            Fixtures.personal(posture = "repair", reaction = "receptive"),
            Fixtures.personal(posture = "repair", reaction = "defensive"),
            Fixtures.personal(posture = "boundary"),
            Fixtures.personal(posture = "reactive", reaction = "defensive"),
            Fixtures.personal(),
            Fixtures.personal(repair = listOf("-", "0", "+")),
            Fixtures.personal(boundary = listOf("+", "+", "0")),
        )))
        testApplication {
            application { strategos(app) }
            val first = client.startChat("Maya and I fought last night and she went quiet. What should I do?")
            assertEquals(HttpStatusCode.Created, first.status)
            val r = json(first.bodyAsText()).jsonObject
            val threadId = r["threadId"]!!.jsonPrimitive.content
            val projectId = r["projectId"]!!.jsonPrimitive.content
            val follow = listOf(
                "I told her calmly what I need. She opened up a bit.",
                "Today she snapped at me again when I brought it up.",
                "I said I'd pause the talk until tomorrow.",
                "I sent her five messages and she didn't answer.",
                "What if I just wait a week?",
                "She usually digs in when I push on something like this.",
                "Ok. What exactly should I say tonight?",
            )
            for ((i, text) in follow.withIndex()) {
                val res = client.say(threadId, text)
                assertEquals("turn ${i + 2} succeeds", HttpStatusCode.OK, res.status)
                val body = json(res.bodyAsText()).jsonObject
                assertTrue("reply ${i + 2} is not empty", body["reply"]!!.jsonPrimitive.content.isNotBlank())
                assertEquals(threadId, body["threadId"]!!.jsonPrimitive.content)
            }
            val turns = json(client.get("/api/threads/$threadId/messages").bodyAsText()).jsonArray.map { it.jsonObject }
            assertEquals(follow.size + 1, turns.size)
            assertEquals("turns come back in order", (0..follow.size).toList(), turns.map { it["turnIndex"]!!.jsonPrimitive.int })
            assertEquals(follow.last(), turns.last()["userMessage"]!!.jsonPrimitive.content)
            turns.forEach(::assertTurnShape)

            val chat = json(client.get("/api/chats").bodyAsText()).jsonArray.single().jsonObject
            assertEquals(follow.size + 1, chat["turns"]!!.jsonPrimitive.int)
            assertEquals(threadId, chat["threadId"]!!.jsonPrimitive.content)

            val x = json(client.get("/api/projects/$projectId/xray").bodyAsText()).jsonObject
            assertTrue("the X-ray is ready after a long chat", x["ready"]!!.jsonPrimitive.boolean)
            assertEquals("the X-ray follows the latest turn's pick",
                json(turns.last()["engineDirectiveJson"]!!.jsonPrimitive.content).jsonObject["move_to_implement"]!!.jsonPrimitive.content,
                x["engineMove"]!!.jsonPrimitive.content)
        }
        app.close()
    }

    @Test
    fun `chats list newest first and can be renamed and deleted`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.personal())))
        testApplication {
            application { strategos(app) }
            val a = json(client.startChat("First thing on my mind.").bodyAsText()).jsonObject["projectId"]!!.jsonPrimitive.content
            Thread.sleep(5)
            val b = json(client.startChat("Second thing on my mind.").bodyAsText()).jsonObject["projectId"]!!.jsonPrimitive.content
            var chats = json(client.get("/api/chats").bodyAsText()).jsonArray.map { it.jsonObject["projectId"]!!.jsonPrimitive.content }
            assertEquals("newest first", listOf(b, a), chats)

            val renamed = client.patch("/api/projects/$a") { contentType(ContentType.Application.Json); setBody("""{"title":"Renamed chat"}""") }
            assertEquals(HttpStatusCode.OK, renamed.status)
            assertEquals("Renamed chat", json(client.get("/api/chats").bodyAsText()).jsonArray.first { it.jsonObject["projectId"]!!.jsonPrimitive.content == a }.jsonObject["title"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/projects/$b").status)
            chats = json(client.get("/api/chats").bodyAsText()).jsonArray.map { it.jsonObject["projectId"]!!.jsonPrimitive.content }
            assertEquals(listOf(a), chats)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/projects/$b/xray").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/projects/$b").status)
        }
        app.close()
    }

    @Test
    fun `bad input gets a clear 4xx with a message, never a 500`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.personal())))
        testApplication {
            application { strategos(app) }
            val empty = client.startChat("   ")
            assertEquals(HttpStatusCode.BadRequest, empty.status)
            assertTrue(json(empty.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content.isNotBlank())

            val garbage = client.post("/api/chats") { contentType(ContentType.Application.Json); setBody("{not json") }
            assertTrue("malformed JSON is a 4xx, was ${garbage.status}", garbage.status.value in 400..499)

            assertEquals(HttpStatusCode.NotFound, client.get("/api/projects/does-not-exist/xray").status)
            val noThread = client.say("does-not-exist", "hello")
            assertTrue("unknown thread is a 4xx, was ${noThread.status}", noThread.status.value in 400..499)

            val r = json(client.startChat("Something real happened today.").bodyAsText()).jsonObject
            val pid = r["projectId"]!!.jsonPrimitive.content
            for (bad in listOf("-0.1", "1.5", "\"high\"")) {
                val k = client.post("/api/projects/$pid/xray/keep") { contentType(ContentType.Application.Json); setBody("""{"odds":$bad}""") }
                assertTrue("odds $bad is rejected, was ${k.status}", k.status.value in 400..499)
            }
        }
        app.close()
    }

    @Test
    fun `unicode, long and pasted-chat messages round-trip intact`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.personal())))
        val pasted = buildString {
            repeat(60) { append("[10:${10 + it % 50}] Maya: ok fine whatever 🙄 — \"see you\" <b>tomorrow</b>\n") }
            append("I owe my friend ₹200 and he'll ask at the meetup.")
        }
        testApplication {
            application { strategos(app) }
            val r = client.startChat(pasted)
            assertEquals(HttpStatusCode.Created, r.status)
            val threadId = json(r.bodyAsText()).jsonObject["threadId"]!!.jsonPrimitive.content
            val turn = json(client.get("/api/threads/$threadId/messages").bodyAsText()).jsonArray.single().jsonObject
            assertEquals("stored exactly as typed", pasted, turn["userMessage"]!!.jsonPrimitive.content)
            assertTurnShape(turn)
            val title = json(client.get("/api/chats").bodyAsText()).jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content
            assertTrue("title is short: $title", title.length <= 49 && title.isNotBlank())
        }
        app.close()
    }

    @Test
    fun `the web page and its icons are served, and deep links fall back to the page`() {
        val dist = java.io.File("../web/dist")
        org.junit.Assume.assumeTrue("web/dist is built", java.io.File(dist, "index.html").isFile)
        val app = AppContainer(com.drkkr.strategos.server.config.ServerConfig(databaseUrl = "jdbc:sqlite::memory:", llmApiKey = null, webDir = dist.absolutePath),
            llm = ScriptedLlm(listOf(Fixtures.personal())), memoryOverride = com.drkkr.strategos.server.memory.InMemoryTemporalFallback())
        testApplication {
            application { strategos(app) }
            val index = client.get("/")
            assertEquals(HttpStatusCode.OK, index.status)
            assertTrue(index.bodyAsText().contains("<div id=\"root\">"))
            assertEquals(HttpStatusCode.OK, client.get("/favicon.svg").status)
            assertEquals(HttpStatusCode.OK, client.get("/manifest.webmanifest").status)
            assertTrue("unknown paths serve the app", client.get("/some/deep/link").bodyAsText().contains("<div id=\"root\">"))
            assertEquals("the API is not swallowed by the page", HttpStatusCode.OK, client.get("/api/health").status)
        }
        app.close()
    }
}
