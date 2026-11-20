package com.drkkr.strategos.server.mcp

import com.drkkr.strategos.server.persistence.DomainType
import com.drkkr.strategos.server.persistence.StoreJson
import com.drkkr.strategos.server.service.StrategosService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.PrintStream

/**
 * The parallel MCP server (SDD §4.2): the same [StrategosService] the REST API uses, as MCP tools. JSON-RPC 2.0 over stdio
 * (Claude Desktop / Claude Code) or Streamable HTTP at POST /mcp. Written directly against the protocol (initialize,
 * tools/list, tools/call, ping) because the official Kotlin SDK needs Kotlin 2, and the app is on 1.9.
 */
class StrategosMcpServer(private val service: StrategosService) {

    data class Tool(val name: String, val description: String, val schema: JsonObject, val call: suspend (JsonObject) -> JsonElement)

    private fun schema(required: List<String>, vararg props: Pair<String, String>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, d) -> putJsonObject(k) { put("type", if (k == "limit") "integer" else "string"); put("description", d) } } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun <T> enc(ser: KSerializer<T>, v: T): JsonElement = StoreJson.encodeToJsonElement(ser, v)

    val tools: List<Tool> = listOf(
        Tool("list_projects", "List the person's Strategos projects (relationships, negotiations, decisions) with trust and tension.",
            schema(emptyList())) { enc(kotlinx.serialization.builtins.ListSerializer(com.drkkr.strategos.server.service.ProjectSummary.serializer()), service.listProjects()) },
        Tool("create_project", "Create a project workspace. domain_type: PERSONAL_RELATIONSHIP, COMMERCIAL_NEGOTIATION, COALITION_ORG or GENERAL.",
            schema(listOf("title"), "title" to "Short name", "domain_type" to "Domain", "description" to "One line about the situation")) { a ->
            enc(com.drkkr.strategos.server.service.ProjectSummary.serializer(), service.createProject(a.str("title")!!,
                a.str("domain_type")?.let { runCatching { DomainType.valueOf(it.uppercase()) }.getOrNull() } ?: DomainType.GENERAL, a.str("description").orEmpty()))
        },
        Tool("get_project_state", "The authoritative state of a project: the engine's model, known facts, Bayesian responsiveness priors, scored options, journal post-notes and threads.",
            schema(listOf("project_id"), "project_id" to "Project id")) { a ->
            service.projectState(a.str("project_id")!!)?.let { enc(com.drkkr.strategos.server.service.ProjectState.serializer(), it) } ?: error("No such project")
        },
        Tool("list_threads", "List the named topic threads in a project.", schema(listOf("project_id"), "project_id" to "Project id")) { a ->
            enc(kotlinx.serialization.builtins.ListSerializer(com.drkkr.strategos.server.persistence.TopicThread.serializer()), service.listThreads(a.str("project_id")!!))
        },
        Tool("open_thread_context", "A thread's rolling summary, last turns and relevant long-term memory (optionally for a query).",
            schema(listOf("thread_id"), "thread_id" to "Thread id", "query" to "What you want to recall")) { a ->
            service.openThreadContext(a.str("thread_id")!!, a.str("query"))?.let { enc(com.drkkr.strategos.server.service.ThreadContext.serializer(), it) } ?: error("No such thread")
        },
        Tool("search_long_term_memory", "Search month-long temporal memory (facts with when they became true and whether they were replaced) in one project or across all.",
            schema(listOf("query"), "query" to "What to look for", "project_id" to "Limit to one project (optional)", "limit" to "Max results")) { a ->
            enc(kotlinx.serialization.builtins.ListSerializer(com.drkkr.strategos.server.memory.MemoryFact.serializer()),
                service.searchLongTermMemory(a.str("query")!!, a.str("project_id"), a["limit"]?.jsonPrimitive?.intOrNull ?: 10))
        },
        Tool("log_interaction_and_analyze", "Log a new real-world event or message and run the full engine. Updates the project's state and memory; returns the reply and the engine directive.",
            schema(listOf("project_id", "event"), "project_id" to "Project id", "event" to "What happened or what was said, in plain words", "thread_id" to "Thread (optional)")) { a ->
            enc(com.drkkr.strategos.server.pipeline.TurnResponse.serializer(), service.logInteractionAndAnalyze(a.str("project_id")!!, a.str("event")!!, a.str("thread_id")))
        },
        Tool("run_what_if", "Run a what-if against the current state (all three stages on a copy). Nothing is saved.",
            schema(listOf("project_id", "change"), "project_id" to "Project id", "change" to "The change to try, e.g. 'I offer a 3-month trial'")) { a ->
            enc(com.drkkr.strategos.server.pipeline.TurnResponse.serializer(), service.runWhatIf(a.str("project_id")!!, a.str("change")!!))
        },
        Tool("compare_moves", "Compare two of the person's moves head to head (engine only, nothing saved).",
            schema(listOf("project_id", "move_a", "move_b"), "project_id" to "Project id", "move_a" to "First move", "move_b" to "Second move")) { a ->
            service.compareMoves(a.str("project_id")!!, a.str("move_a")!!, a.str("move_b")!!) ?: error("The project has no moves to compare yet")
        },
        Tool("simulate_multi_step", "Look ahead from one move: the other side's likely replies and the best answer to each (engine only, nothing saved).",
            schema(listOf("project_id", "move"), "project_id" to "Project id", "move" to "The move to start from")) { a ->
            service.simulateMultiStep(a.str("project_id")!!, a.str("move")!!) ?: error("The project has no moves yet")
        },
        Tool("log_outcome_feedback", "Record how a move went. Updates the Bayesian priors (probSoftensOnRepair, probRespectsBoundary, probEscalatesOnReactive) and memory.",
            schema(listOf("thread_id", "tried_move", "counterpart_reaction"), "thread_id" to "Thread id", "tried_move" to "The move tried, or its posture (repair, boundary, reactive)",
                "counterpart_reaction" to "How they reacted (softened, withdrew_or_escalated, or in words)", "user_state" to "How the person feels (optional)")) { a ->
            enc(com.drkkr.strategos.server.service.QuickCheckResult.serializer(), service.quickCheck(a.str("thread_id")!!, a.str("tried_move")!!, a.str("counterpart_reaction")!!, a.str("user_state")))
        },
        Tool("log_journal_reflection", "Save a private reflection. Returns 'Recorded privately.' and any Do / Don't post-notes it added to projects.",
            schema(listOf("content"), "content" to "The reflection", "project_id" to "Related project (optional)")) { a ->
            val saved = service.saveJournal(a.str("content")!!, a.str("project_id"))
            service.awaitScreening(saved.id)
            buildJsonObject {
                put("message", saved.message)
                put("post_notes", service.journal.get(saved.id)?.let { e -> StoreJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(com.drkkr.strategos.server.persistence.PostNote.serializer()),
                    e.affectedProjectIds.flatMap { pid -> service.journal.postNotes(pid).filter { it.journalId == e.id } }) } ?: JsonArray(emptyList()))
            }
        },
    )

    private val byName = tools.associateBy { it.name }

    /** One JSON-RPC message in, at most one out (null for notifications). */
    suspend fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32600, "Invalid request")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null || id is JsonNull) return null      // a notification (e.g. notifications/initialized)
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL)
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") { put("name", "strategos"); put("version", "4.0.0") }
                put("instructions", "Strategos runs a deterministic game-theory engine over the person's projects. Never invent payoffs: call log_interaction_and_analyze or the simulation tools and report the engine's pick.")
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                putJsonArray("tools") { tools.forEach { t -> addJsonObject { put("name", t.name); put("description", t.description); put("inputSchema", t.schema) } } }
            })
            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull
                val tool = byName[name] ?: return error(id, -32602, "Unknown tool: $name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val out = runCatching { tool.call(args) }
                result(id, buildJsonObject {
                    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", out.fold({ it.toString() }, { it.message ?: "Failed" })) } }
                    out.getOrNull()?.let { if (it is JsonObject) put("structuredContent", it) }
                    put("isError", out.isFailure)
                })
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    /** stdio transport: one JSON-RPC message per line on stdin, replies on stdout. Logs go to stderr only. */
    fun runStdio(input: BufferedReader = System.`in`.bufferedReader(), output: PrintStream = System.out) = runBlocking {
        while (true) {
            val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
            if (line.isBlank()) continue
            val reply = runCatching { Json.parseToJsonElement(line) }.fold(
                { el -> when (el) {
                    is JsonArray -> el.mapNotNull { (it as? JsonObject)?.let { m -> handle(m) } }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
                    is JsonObject -> handle(el)
                    else -> error(null, -32600, "Invalid request")
                } },
                { error(null, -32700, "Parse error") })
            reply?.let { output.println(it.toString()); output.flush() }
        }
    }

    private fun result(id: JsonElement?, r: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); id?.let { put("id", it) }; put("result", r) }
    private fun error(id: JsonElement?, code: Int, msg: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonNull); putJsonObject("error") { put("code", code); put("message", msg) }
    }
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object { const val PROTOCOL = "2025-06-18" }
}
