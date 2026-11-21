package com.drkkr.strategos.server.http

import com.drkkr.strategos.server.llm.LlmRouter
import com.drkkr.strategos.server.mcp.StrategosMcpServer
import com.drkkr.strategos.server.persistence.DomainType
import com.drkkr.strategos.server.persistence.SettingsRepository
import com.drkkr.strategos.server.service.StrategosService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class CreateProject(val title: String, val domainType: DomainType = DomainType.GENERAL, val description: String = "")
@Serializable data class PatchProject(val title: String? = null, val domainType: DomainType? = null, val archived: Boolean? = null)
@Serializable data class CreateThread(val title: String)
@Serializable data class SendMessage(val message: String)
@Serializable data class QuickCheck(val triedMove: String, val counterpartReaction: String, val userState: String? = null)
@Serializable data class SaveJournal(val content: String, val projectId: String? = null)
@Serializable data class StartChat(val message: String, val domainType: DomainType = DomainType.GENERAL)
@Serializable data class KeepOdds(val odds: Double)
@Serializable data class LlmSettings(val provider: String = "deepseek", val model: String? = null, val baseUrl: String? = null, val apiKey: String? = null)
@Serializable data class LlmSettingsView(val provider: String, val model: String, val baseUrl: String?, val hasKey: Boolean)

fun Route.strategosApi(service: StrategosService, settings: SettingsRepository, xray: com.drkkr.strategos.server.service.XRayService) {
    route("/api") {
        get("/health") { call.respond(service.health()) }

        get("/chats") { call.respond(service.listChats()) }
        post("/chats") {
            val b = call.receive<StartChat>()
            call.respond(HttpStatusCode.Created, service.startChat(b.message, b.domainType))
        }
        get("/projects/{id}/xray") { call.respond(xray.of(call.id()) ?: throw NoSuchElementException("project")) }
        post("/projects/{id}/xray/keep") {
            val odds = call.receive<KeepOdds>().odds
            require(odds in 0.0..1.0) { "odds must be between 0 and 1" }
            call.respond(xray.keep(call.id(), odds) ?: throw NoSuchElementException("project"))
        }

        get("/projects") { call.respond(service.listProjects(call.parameters["archived"] == "true")) }
        post("/projects") {
            val b = call.receive<CreateProject>()
            call.respond(HttpStatusCode.Created, service.createProject(b.title, b.domainType, b.description))
        }
        get("/projects/{id}") { call.respond(service.projectState(call.id()) ?: throw NoSuchElementException("project")) }
        patch("/projects/{id}") {
            val b = call.receive<PatchProject>()
            call.respond(service.patchProject(call.id(), b.title, b.domainType, b.archived) ?: throw NoSuchElementException("project"))
        }
        delete("/projects/{id}") {
            if (service.deleteProject(call.id())) call.respond(HttpStatusCode.NoContent) else throw NoSuchElementException("project")
        }

        get("/projects/{id}/threads") { call.respond(service.listThreads(call.id())) }
        post("/projects/{id}/threads") {
            call.respond(HttpStatusCode.Created, service.createThread(call.id(), call.receive<CreateThread>().title) ?: throw NoSuchElementException("project"))
        }

        get("/threads/{threadId}/messages") { call.respond(service.threadTurns(call.parameters["threadId"]!!)) }
        post("/threads/{threadId}/messages") {
            call.respond(service.sendMessage(call.parameters["threadId"]!!, call.receive<SendMessage>().message))
        }
        // "Draft exact words": the words for the engine's current move, from the saved situation (no new extraction).
        post("/threads/{threadId}/script") { call.respond(service.draftWords(call.parameters["threadId"]!!)) }
        get("/threads/{threadId}/context") {
            call.respond(service.openThreadContext(call.parameters["threadId"]!!, call.parameters["q"]) ?: throw NoSuchElementException("thread"))
        }
        post("/threads/{threadId}/quick-check") {
            val b = call.receive<QuickCheck>()
            call.respond(service.quickCheck(call.parameters["threadId"]!!, b.triedMove, b.counterpartReaction, b.userState))
        }

        get("/memory/search") {
            call.respond(service.searchLongTermMemory(call.parameters["q"].orEmpty(), call.parameters["projectId"], call.parameters["limit"]?.toIntOrNull() ?: 10))
        }

        get("/journal") { call.respond(service.listJournal()) }
        post("/journal") {
            val b = call.receive<SaveJournal>()
            call.respond(HttpStatusCode.Created, service.saveJournal(b.content, b.projectId))
        }

        get("/settings/llm") { call.respond(view(service.llm, settings)) }
        put("/settings/llm") {
            val b = call.receive<LlmSettings>()
            // The key lives in memory only (never written to disk); without a new key the current one is kept.
            val key = b.apiKey?.takeIf { it.isNotBlank() } ?: service.llm.keyInMemory
            val provider = LlmRouter.build(b.provider, key, b.model, b.baseUrl)
            service.llm.swap(provider); service.llm.keyInMemory = key
            settings.put("llm.provider", b.provider); b.model?.let { settings.put("llm.model", it) }; b.baseUrl?.let { settings.put("llm.baseUrl", it) }
            call.respond(view(service.llm, settings))
        }
    }
}

fun Route.mcpHttp(mcp: StrategosMcpServer) {
    post("/mcp") {
        val body = runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull()
        val reply: JsonElement? = when (body) {
            is JsonObject -> mcp.handle(body)
            is JsonArray -> body.mapNotNull { (it as? JsonObject)?.let { m -> mcp.handle(m) } }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
            else -> buildJsonObject { put("jsonrpc", "2.0"); put("id", JsonNull); putJsonObject("error") { put("code", -32700); put("message", "Parse error") } }
        }
        if (reply == null) call.respond(HttpStatusCode.Accepted, "")
        else call.respondText(reply.toString(), ContentType.Application.Json)
    }
    // No server-initiated stream: this server only answers requests.
    get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
}

private fun ApplicationCall.id() = parameters["id"]!!

private fun view(llm: LlmRouter, settings: SettingsRepository) = LlmSettingsView(
    provider = llm.active()?.name ?: settings.get("llm.provider") ?: "deepseek",
    model = llm.active()?.model ?: settings.get("llm.model").orEmpty(),
    baseUrl = settings.get("llm.baseUrl"),
    hasKey = llm.active() != null,
)
