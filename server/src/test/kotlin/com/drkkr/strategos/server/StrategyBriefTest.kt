package com.drkkr.strategos.server

import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.pipeline.BriefPrompt
import com.drkkr.strategos.server.pipeline.BriefReply
import com.drkkr.strategos.server.pipeline.BriefTable
import com.drkkr.strategos.domain.agent.XRayGrid
import com.drkkr.strategos.domain.agent.XRayRow
import com.drkkr.strategos.domain.intent.Intent
import com.drkkr.strategos.domain.intent.ReplyMode
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Advice turns come back as a strategy brief: the X-ray's numbers, trade-offs per move, the pick and a plan. */
class StrategyBriefTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    /** The scripted model, with its brief answers rewritten by [edit] (the usual coach reply is left alone). */
    private class EditedBriefs(private val base: ScriptedLlm, private val edit: (JsonObject) -> JsonObject) : LlmProvider by base {
        override suspend fun chat(request: ChatRequest): ChatResult {
            val r = base.chat(request)
            val brief = request.purpose == "COACH" && request.messages.last().content.contains(BriefPrompt.TABLE_HEADER)
            return if (brief && r is ChatResult.Ok) ChatResult.Ok(edit(json(r.text).jsonObject).toString()) else r
        }
        private fun json(s: String) = Json.parseToJsonElement(s)
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    /** A field, or null when it is missing or JSON null. */
    private fun JsonObject.opt(key: String) = this[key]?.takeIf { it !is JsonNull }

    private fun start(llm: LlmProvider, check: suspend ApplicationTestBuilder.(JsonObject) -> Unit) {
        val app = Fixtures.container(llm)
        testApplication {
            application { strategos(app) }
            val first = client.post("/api/chats") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Maya and I fought last night and she went quiet. Should I keep texting her or give her space?"}""")
            }
            assertEquals(HttpStatusCode.Created, first.status)
            check(json(first.bodyAsText()).jsonObject)
        }
        app.close()
    }

    @Test
    fun `an advice turn is a brief whose numbers are the X-ray's own`() = start(ScriptedLlm(listOf(Fixtures.personal()))) { r ->
        val brief = r["cards"]!!.jsonObject.opt("brief")?.jsonObject
        assertNotNull("the turn carries a brief", brief)
        val pick = r["directive"]!!.jsonObject["moveToImplement"]!!.jsonPrimitive.content
        val rows = brief!!["rows"]!!.jsonArray.map { it.jsonObject }

        val x = json(client.get("/api/projects/${r["projectId"]!!.jsonPrimitive.content}/xray").bodyAsText()).jsonObject
        val xrows = x["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals("one row per X-ray move, same order", xrows.map { it["label"] }, rows.map { it["move"] })
        for (k in listOf("well", "bad", "expected", "kind")) assertEquals("$k matches the X-ray", xrows.map { it[k] }, rows.map { it[k] })
        assertEquals("the odds are the X-ray's", (x["coachOdds"]!!.jsonPrimitive.double * 100).toInt(), brief["oddsPct"]!!.jsonPrimitive.int)
        assertEquals("the recommended row is the engine's pick", pick, rows.single { it["recommended"]!!.jsonPrimitive.boolean }["move"]!!.jsonPrimitive.content)
        assertTrue("every move has trade-offs", rows.all { it["pros"]!!.jsonArray.isNotEmpty() && it["cons"]!!.jsonArray.isNotEmpty() })
        assertTrue("there is a plan", brief["plan"]!!.jsonArray.isNotEmpty() && brief["ifThen"]!!.jsonArray.isNotEmpty())
        assertTrue("the weights add up to about 100", brief["weights"]!!.jsonArray.sumOf { it.jsonObject["pct"]!!.jsonPrimitive.int } in 98..102)
        assertTrue("the brief replaces the plain options card", r["cards"]!!.jsonObject.opt("options")?.jsonArray.isNullOrEmpty())

        val reply = r["reply"]!!.jsonPrimitive.content
        assertTrue("the stored text stands on its own, with the numbers", reply.contains(pick) && reply.contains("${rows.first()["expected"]!!.jsonPrimitive.int}/100"))
        assertTrue(reply.contains("How to tackle it"))
    }

    @Test
    fun `a brief with a number the engine did not give is not shown`() = start(EditedBriefs(ScriptedLlm(listOf(Fixtures.personal()))) {
        it.with("why", JsonPrimitive("There's an 87% chance she comes round if you wait."))
    }) { r ->
        assertNull("no brief", r["cards"]!!.jsonObject.opt("brief"))
        val reply = r["reply"]!!.jsonPrimitive.content
        assertFalse("the invented figure never reaches the person", reply.contains("87"))
        assertTrue("the usual reply is shown instead", reply.contains(r["directive"]!!.jsonObject["moveToImplement"]!!.jsonPrimitive.content))
    }

    @Test
    fun `a brief that recommends a different move is not shown`() = start(EditedBriefs(ScriptedLlm(listOf(Fixtures.personal()))) {
        it.with("engine_move", JsonPrimitive(Fixtures.REACTIVE)).with("recommendation", JsonPrimitive(Fixtures.REACTIVE))
    }) { r ->
        assertNull(r["cards"]!!.jsonObject.opt("brief"))
        assertNotEquals(Fixtures.REACTIVE, r["directive"]!!.jsonObject["moveToImplement"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a brief never carries words to say, even when the model writes some`() = start(ScriptedLlm(listOf(Fixtures.personal()))) { r ->
        val brief = r["cards"]!!.jsonObject["brief"]!!.jsonObject
        assertNull("words are opt-in", brief.opt("say"))
        assertFalse(r["reply"]!!.jsonPrimitive.content.contains("What to say"))
        assertEquals("brief", r["cards"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    /** The personal fixture with Call 1 naming an intent (and its detail). */
    private fun asking(intent: String, detail: String = "null", kind: String = "situation") =
        Fixtures.personal().replaceFirst("{", """{"intent":"$intent","intent_detail":$detail,"message_kind":"$kind",""")

    private fun mode(r: JsonObject) = r["cards"]!!.jsonObject.opt("mode")?.jsonPrimitive?.content

    @Test
    fun `asking A or B gives a compare brief`() = start(ScriptedLlm(listOf(
        asking("compare", """{"move_a":"${Fixtures.REPAIR}","move_b":"${Fixtures.BOUNDARY}"}""")))) { r ->
        assertEquals("compare", mode(r))
        assertEquals("compare", r["cards"]!!.jsonObject["brief"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `asking what to say gives the classic card with the words`() = start(ScriptedLlm(listOf(asking("script")))) { r ->
        assertEquals("script", mode(r))
        assertNull(r["cards"]!!.jsonObject.opt("brief"))
        assertTrue(r["reply"]!!.jsonPrimitive.content.contains("I'd like to sort this out with you."))
    }

    @Test
    fun `asking how they will take it gives a direct answer, not a script`() = start(ScriptedLlm(listOf(
        asking("their_reaction", """{"move":"${Fixtures.REPAIR}"}""")))) { r ->
        assertEquals("answer", mode(r))
        val answer = r["cards"]!!.jsonObject["answer"]!!.jsonObject
        assertTrue(answer["bottomLine"]!!.jsonPrimitive.content.contains(r["directive"]!!.jsonObject["moveToImplement"]!!.jsonPrimitive.content))
        val reply = r["reply"]!!.jsonPrimitive.content
        assertTrue(reply.contains("Bottom line:")); assertFalse(reply.contains("What to say"))
    }

    @Test
    fun `an answer with a number the engine did not give falls back to the classic card`() {
        val base = ScriptedLlm(listOf(asking("their_reaction", """{"move":"${Fixtures.REPAIR}"}""")))
        val llm = object : LlmProvider by base {
            override suspend fun chat(request: ChatRequest): ChatResult {
                val r = base.chat(request)
                val answering = request.messages.any { it.role == "system" && it.content == BriefPrompt.ANSWER }
                return if (answering && r is ChatResult.Ok)
                    ChatResult.Ok(JsonObject(json(r.text).jsonObject + ("answer" to JsonPrimitive("There is a 93% chance she opens up if you wait."))).toString())
                else r
            }
        }
        start(llm) { r ->
            assertEquals("script", mode(r))
            assertFalse(r["reply"]!!.jsonPrimitive.content.contains("93"))
        }
    }

    @Test
    fun `Draft exact words gives the classic card without a new extraction`() {
        val llm = ScriptedLlm(listOf(Fixtures.personal()))
        start(llm) { r ->
            val extractions = llm.requests.count { it.purpose == "EXTRACT" }
            val res = client.post("/api/threads/${r["threadId"]!!.jsonPrimitive.content}/script")
            assertEquals(HttpStatusCode.OK, res.status)
            val s = json(res.bodyAsText()).jsonObject
            assertEquals("script", mode(s))
            assertEquals("no extraction call for a draft", extractions, llm.requests.count { it.purpose == "EXTRACT" })
            assertTrue(s["reply"]!!.jsonPrimitive.content.contains("I'd like to sort this out with you."))
            assertEquals("the same move as the turn before", r["directive"]!!.jsonObject["moveToImplement"], s["directive"]!!.jsonObject["moveToImplement"])
            val turns = json(client.get("/api/threads/${r["threadId"]!!.jsonPrimitive.content}/messages").bodyAsText()).jsonArray
            assertEquals(com.drkkr.strategos.server.pipeline.TurnPipeline.DRAFT_WORDS, turns.last().jsonObject["userMessage"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the reply mode follows what was asked`() {
        fun m(i: Intent, k: String? = null) = ReplyMode.of(i, k)
        assertEquals(ReplyMode.BRIEF, m(Intent.STANDARD)); assertEquals(ReplyMode.BRIEF, m(Intent.TOP_OPTIONS))
        assertEquals(ReplyMode.COMPARE, m(Intent.COMPARE)); assertEquals(ReplyMode.SCRIPT, m(Intent.SCRIPT))
        listOf(Intent.WHAT_IF, Intent.THEIR_REACTION, Intent.PLAN_AHEAD).forEach { assertEquals(ReplyMode.ANSWER, m(it)) }
        assertEquals(ReplyMode.ANSWER, m(Intent.STANDARD, "followup_question"))
        assertEquals("Call 1's word is read case-insensitively", Intent.SCRIPT, Intent.of("Script"))
    }

    @Test
    fun `when the pick is not the top score the coach is told, so it cannot claim otherwise`() {
        val rows = listOf(XRayRow("Push back", 96, 21, 59), XRayRow("Accept", 100, 0, 50), XRayRow("Trade", 82, 9, 46))
        val table = BriefTable(XRayGrid(rows, bestIndex = 1, robust = false, wellLabel = "Agree", badLabel = "Insist", odds = 0.5), "Manager takes it well", emptyList())
        assertFalse(50 in table.pickLeadsAt())
        assertTrue(table.render().contains("THE PICK IS NOT THE TOP SCORE AT THESE ODDS: \"Push back\" scores 59 against 50"))
        val leading = BriefTable(XRayGrid(rows, bestIndex = 0, robust = false, wellLabel = "Agree", badLabel = "Insist", odds = 0.5), "x", emptyList())
        assertTrue(leading.standing().startsWith("The pick has the top score at these odds"))
    }

    @Test
    fun `small counts and the person's own figures are allowed, other numbers are not`() {
        val words = BriefReply(recommendation = "Wait", plan = listOf("Wait 2 days", "Offer the 45k you mentioned"), why = "It scores 71 either way.")
        assertTrue(words.strayNumbers(allowed = setOf(71), theirWords = "they offered 45k").isEmpty())
        assertEquals(setOf(45), words.strayNumbers(allowed = setOf(71), theirWords = "no figures here"))
    }
}
