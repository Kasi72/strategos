package com.drkkr.strategos.server

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.server.Fixtures.BOUNDARY
import com.drkkr.strategos.server.Fixtures.CAVE
import com.drkkr.strategos.server.Fixtures.REACTIVE
import com.drkkr.strategos.server.Fixtures.REPAIR
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.llm.OpenAiCompatibleProvider
import com.drkkr.strategos.server.memory.Episode
import com.drkkr.strategos.server.memory.FactInput
import com.drkkr.strategos.server.memory.InMemoryTemporalFallback
import com.drkkr.strategos.server.memory.MemoryGroups
import com.drkkr.strategos.server.persistence.DomainType
import com.drkkr.strategos.server.pipeline.DeterministicPayoffCalculator
import com.drkkr.strategos.server.pipeline.TurnPipeline
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** The eight acceptance checks of STRATEGOS_SDD_v4.0 §5, offline (fake model, in-memory SQLite, the deterministic memory fallback). */
class SddAcceptanceTest {
    private val day = 24 * 3_600_000L

    @Test
    fun `SDD-01 a reactive move that feels good now but ruptures the bond scores lower and is never recommended`() = runBlocking {
        Fixtures.container(ScriptedLlm(listOf(Fixtures.personal()))).use { app ->
            val p = app.service.createProject("Maya", DomainType.PERSONAL_RELATIONSHIP)
            val t = app.service.createThread(p.id, "Main")!!
            val r = app.service.sendMessage(t.id, "Maya and I fought and she went quiet. Part of me wants to keep texting her until she answers.")

            val scored = DeterministicPayoffCalculator.netPayoffs(app.projects.get(p.id)!!.scenario).associateBy { it.label }
            val reactive = scored.getValue(REACTIVE)
            assertEquals("reactive", reactive.posture)
            assertTrue("reactive ${reactive.net} < repair ${scored.getValue(REPAIR).net}", reactive.net < scored.getValue(REPAIR).net)
            assertTrue("reactive ${reactive.net} < boundary ${scored.getValue(BOUNDARY).net}", reactive.net < scored.getValue(BOUNDARY).net)

            val pick = r.directive!!.recommendedMove!!
            val action = app.projects.get(p.id)!!.scenario.actions.first { it.label == pick }
            assertNotEquals("the engine's pick is never reactive", "reactive", Postures.of(action))
            assertTrue("the reply carries the engine's pick: ${r.reply}", r.reply.contains(r.directive!!.moveToImplement!!))
        }
    }

    @Test
    fun `SDD-02 a fear said in week 1 is recalled in week 4 and handed to the coach as settled`() = runBlocking {
        val llm = ScriptedLlm(listOf(Fixtures.commercial))
        val memory = InMemoryTemporalFallback()
        Fixtures.container(llm, memory).use { app ->
            val p = app.service.createProject("FinCorp renewal", DomainType.COMMERCIAL_NEGOTIATION)
            val t = app.service.createThread(p.id, "Renewal")!!
            val group = MemoryGroups.project(p.id)
            val start = System.currentTimeMillis() - 28 * day
            memory.ingest(Episode(group, "thread_${t.id}_turn_0", "User: Ravi told me he's terrified of missing Q3 payroll.", "seed", start,
                listOf(FactInput("Ravi", "fears", "terrified of missing Q3 payroll"))))
            val noise = listOf("the logo colours", "the onboarding deck", "a typo in the invoice", "lunch at the conference", "the CRM migration")
            repeat(20) { i ->
                memory.ingest(Episode(group, "thread_${t.id}_turn_${i + 1}", "User: we talked about ${noise[i % noise.size]} again.", "seed",
                    start + (i + 1) * day, listOf(FactInput("Ravi", "mentioned", noise[i % noise.size]))))
            }
            repeat(4) { i -> memory.ingest(Episode(group, "x_$i", "User: weekly sync done.", "seed", start + (22 + i) * day)) }

            val ctx = memory.retrieveContext(listOf(group), "How do I pitch tomorrow's scope change?", listOf("Ravi"))
            assertTrue("week-1 fear is a current fact: ${ctx.currentFacts}", ctx.currentFacts.any { it.statement.contains("Q3 payroll") })

            app.service.sendMessage(t.id, "How do I pitch tomorrow's scope change to Ravi?")
            val coach = llm.coachRequests().last()
            assertEquals("system message 1 is the static coach prompt (prefix cache)", ArgonPrompts.ENGINE_COACH_PROMPT, coach.messages.first().content)
            val seen = coach.messages.last().content
            assertTrue("the coach is given the week-1 fear", seen.contains("terrified of missing Q3 payroll"))
            assertTrue("as settled, not to be asked again", seen.contains("never ask for them again"))
            assertTrue("the coach prompt forbids re-asking settled facts", ArgonPrompts.ENGINE_COACH_PROMPT.contains("never advise asking for it"))
        }
    }

    @Test
    fun `SDD-03 a later stance supersedes an earlier one and the old one is marked replaced`() = runBlocking {
        val memory = InMemoryTemporalFallback()
        val g = MemoryGroups.project("reloc")
        val week1 = 1_000L * day; val week3 = week1 + 14 * day
        memory.ingest(Episode(g, "w1", "Partner refuses to talk about relocation.", "seed", week1,
            listOf(FactInput("Partner", "stance on relocation", "refuses to talk about relocation"))))
        memory.ingest(Episode(g, "w3", "Partner apologized and asked what a 3-month trial schedule looks like.", "seed", week3,
            listOf(FactInput("Partner", "stance on relocation", "apologized and asked what a 3-month trial schedule looks like"))))

        val facts = memory.searchFacts(listOf(g), "partner relocation stance")
        assertTrue("current state first: ${facts.first()}", facts.first().statement.contains("3-month trial") && facts.first().current)
        val old = facts.first { it.statement.contains("refuses") }
        assertEquals("the week-1 refusal was closed when week 3 arrived", week3, old.invalidAt)

        val ctx = memory.retrieveContext(listOf(g), "relocation", listOf("Partner"))
        assertTrue(ctx.currentFacts.single { it.predicate == "stance on relocation" }.statement.contains("3-month trial"))
        assertTrue(ctx.superseded.any { it.statement.contains("refuses") })
        assertTrue("the coach is told it is no longer true", ctx.render().contains("NO LONGER TRUE"))
    }

    @Test
    fun `SDD-04 three repairs met with withdrawal drop the prior below 0_40 and flip the pick to a boundary`() = runBlocking {
        val steps = listOf(Fixtures.personal()) + List(3) { Fixtures.personal(posture = "repair", reaction = "withdrew_or_escalated") }
        Fixtures.container(ScriptedLlm(steps)).use { app ->
            val p = app.service.createProject("Maya", DomainType.PERSONAL_RELATIONSHIP)
            val t = app.service.createThread(p.id, "Main")!!
            val first = app.service.sendMessage(t.id, "Maya and I fought. I want to fix it.")
            fun postureOf(label: String?) = app.projects.get(p.id)!!.scenario.actions.first { it.label == label }.let(Postures::of)
            assertEquals("repair leads while she is expected to soften", "repair", postureOf(first.directive!!.recommendedMove))

            var last = first
            repeat(3) { last = app.service.sendMessage(t.id, "I apologised and reached out again. She pulled away again.") }
            val priors = app.service.projectState(p.id)!!.priors!!
            assertTrue("probSoftensOnRepair ${priors.probSoftensOnRepair} < 0.40", priors.probSoftensOnRepair < 0.40)
            assertEquals("the pick flipped to a boundary", "boundary", postureOf(last.directive!!.recommendedMove))
        }
    }

    @Test
    fun `SDD-05 a B2B price talk gets no bond warning, never goes below the floor, and shows no ids`() = runBlocking {
        Fixtures.container(ScriptedLlm(listOf(Fixtures.commercial))).use { app ->
            val p = app.service.createProject("FinCorp", DomainType.COMMERCIAL_NEGOTIATION)
            val t = app.service.createThread(p.id, "Price")!!
            val r = app.service.sendMessage(t.id, "FinCorp renewal: my walk-away is 27 lakh and I'm aiming for 29 lakh. Ravi wants a lower price.")
            val d = r.directiveJson!!.toString()
            assertFalse("no bond-at-risk warning on a commercial deal: $d", d.contains("Bond", ignoreCase = false) || d.contains("bond at risk", ignoreCase = true))
            val pick = r.directive!!.recommendedMove!!
            assertNotEquals("never the move below the 27L floor", CAVE, pick)
            val lakh = Regex("""(\d+(?:\.\d+)?)\s*lakh""").find(pick)?.groupValues?.get(1)?.toDouble()
            assertTrue("pick $pick is at or above 27L", lakh == null || lakh >= 27.0)
            assertFalse("no raw ids in the directive", Fixtures.hasUuid(d))
            assertFalse("no raw ids in the reply", Fixtures.hasUuid(r.reply))
            assertTrue("dominated moves are readable labels: ${r.directive!!.blockedOrDominatedMoves}",
                r.directive!!.blockedOrDominatedMoves.none { Fixtures.hasUuid(it) })
        }
    }

    @Test
    fun `SDD-06 a journal entry is recorded privately at once and adds a Do and Don't to the commercial project`() {
        lateinit var commercialId: String
        val llm = ScriptedLlm(listOf(Fixtures.commercial), journal = {
            """{"mood_tag":"ANXIOUS","support":false,"impacts":[{"scenario_id":"$commercialId","significance":"HIGH",
              "habit_or_trigger":"Caving on price after 24 hours of client silence",
              "insight_headline":"Based on your journal entry: silence makes you cave on price",
              "do_this":"Wait 48 hours and restate your offer once, calmly.","dont_do_this":"Don't drop the price to end the silence."}]}"""
        })
        val app = Fixtures.container(llm)
        commercialId = app.service.createProject("FinCorp", DomainType.COMMERCIAL_NEGOTIATION).id
        app.service.createProject("Weekend plans", DomainType.PERSONAL_RELATIONSHIP)
        testApplication {
            application { strategos(app) }
            val res = client.post("/api/journal") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"I cave on price whenever a client goes silent for 24 hours."}""")
            }
            assertEquals(HttpStatusCode.Created, res.status)
            val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            assertEquals("Recorded privately.", body["message"]!!.jsonPrimitive.content)
            runBlocking { app.service.awaitScreening(body["id"]!!.jsonPrimitive.content) }
            val notes = app.journal.postNotes(commercialId)
            assertEquals(1, notes.size)
            assertTrue(notes.single().doThis.isNotBlank() && notes.single().dontDoThis.startsWith("Don't"))
            val state = Json.parseToJsonElement(runBlocking { client.get("/api/projects/$commercialId").bodyAsText() }).jsonObject
            assertEquals("the note is on the project state", 1, state["postNotes"]!!.jsonArray.size)
        }
        app.close()
    }

    @Test
    fun `SDD-07 swapping DeepSeek for another OpenAI-compatible provider changes nothing else`() = runBlocking {
        // The pipeline, memory and engine only ever see the LlmProvider interface.
        TurnPipeline::class.constructors.single().parameters.forEach { param ->
            val type = param.type.classifier
            assertFalse("TurnPipeline must not depend on a concrete provider: ${param.name}",
                type == OpenAiCompatibleProvider::class)
        }
        val first = ScriptedLlm(listOf(Fixtures.personal()), name = "deepseek")
        Fixtures.container(first).use { app ->
            val p = app.service.createProject("Maya", DomainType.PERSONAL_RELATIONSHIP)
            val t = app.service.createThread(p.id, "Main")!!
            assertTrue(app.service.sendMessage(t.id, "Maya and I fought.").directive!!.ready)

            // A real HTTP adapter for a different provider, served by a local mock of its /chat/completions.
            val coachEcho = ScriptedLlm(listOf(Fixtures.personal()))
            val server = MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
                        val system = msgs.first()["content"]!!.jsonPrimitive.content
                        val purpose = if (system == ArgonPrompts.EXTRACTION_PROMPT) "EXTRACT" else "COACH"
                        val text = runBlocking {
                            (coachEcho.chat(ChatRequest(msgs.map { com.drkkr.strategos.server.llm.ChatMessage(it["role"]!!.jsonPrimitive.content, it["content"]!!.jsonPrimitive.content) }, purpose = purpose)) as ChatResult.Ok).text
                        }
                        return MockResponse().setBody(buildJsonObject {
                            putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("role", "assistant"); put("content", text) } } }
                        }.toString())
                    }
                }
                start()
            }
            val alternate: LlmProvider = OpenAiCompatibleProvider("openai-compatible", server.url("/v1").toString(), "test-key", "some-model")
            app.router.swap(alternate)
            val second = app.service.sendMessage(t.id, "She still hasn't answered.")
            assertTrue("turn succeeds end to end on the new provider", second.directive!!.ready && second.reply.isNotBlank())
            assertEquals("openai-compatible", app.service.health().llmProvider)
            assertTrue("the alternate provider was really called", server.requestCount >= 2)
            server.shutdown()
        }
    }

    @Test
    fun `SDD-08 MCP and the web API read and write the same state`() {
        val app = Fixtures.container(ScriptedLlm(listOf(Fixtures.commercial)))
        testApplication {
            application { strategos(app) }
            suspend fun mcp(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) = Json.parseToJsonElement(client.post("/mcp") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString())
            }.bodyAsText()).jsonObject
            fun tool(name: String, args: JsonObject) = buildJsonObject { put("name", name); put("arguments", args) }

            val init = mcp("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") })
            assertEquals("strategos", init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val names = mcp("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue(names.containsAll(listOf("list_projects", "create_project", "get_project_state", "list_threads", "open_thread_context",
                "search_long_term_memory", "log_interaction_and_analyze", "run_what_if", "compare_moves", "simulate_multi_step",
                "log_outcome_feedback", "log_journal_reflection")))

            // Created over HTTP, analysed over MCP.
            val created = Json.parseToJsonElement(client.post("/api/projects") {
                contentType(ContentType.Application.Json); setBody("""{"title":"FinCorp","domainType":"COMMERCIAL_NEGOTIATION"}""")
            }.bodyAsText()).jsonObject
            val pid = created["id"]!!.jsonPrimitive.content
            val call = mcp("tools/call", tool("log_interaction_and_analyze", buildJsonObject {
                put("project_id", pid); put("event", "Ravi called: they want a lower price. My walk-away is 27 lakh, target 29 lakh.")
            }))["result"]!!.jsonObject
            assertFalse(call["isError"]!!.jsonPrimitive.boolean)
            val viaMcp = call["structuredContent"]!!.jsonObject

            // Read back over HTTP: the same turn, the same directive, the same model.
            val state = Json.parseToJsonElement(runBlocking { client.get("/api/projects/$pid").bodyAsText() }).jsonObject
            val threadId = state["threads"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
            val turns = Json.parseToJsonElement(runBlocking { client.get("/api/threads/$threadId/messages").bodyAsText() }).jsonArray
            assertEquals(1, turns.size)
            assertEquals("the directive stored for the web is the one MCP returned",
                viaMcp["directiveJson"]!!.toString(), Json.parseToJsonElement(turns.single().jsonObject["engineDirectiveJson"]!!.jsonPrimitive.content).toString())
            assertTrue("the model built over MCP is on the web project", state["options"]!!.jsonArray.size >= 2)

            val viaMcpState = mcp("tools/call", tool("get_project_state", buildJsonObject { put("project_id", pid) }), id = 9)["result"]!!.jsonObject["structuredContent"]!!
            assertEquals("get_project_state over MCP equals GET /api/projects/{id}", state, viaMcpState)
        }
        app.close()
    }
}
