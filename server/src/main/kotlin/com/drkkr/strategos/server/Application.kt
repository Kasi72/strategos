package com.drkkr.strategos.server

import com.drkkr.strategos.server.config.ServerConfig
import com.drkkr.strategos.server.http.mcpHttp
import com.drkkr.strategos.server.http.strategosApi
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.llm.LlmRouter
import com.drkkr.strategos.server.mcp.StrategosMcpServer
import com.drkkr.strategos.server.memory.GraphitiMemoryAdapter
import com.drkkr.strategos.server.memory.InMemoryTemporalFallback
import com.drkkr.strategos.server.memory.MemoryService
import com.drkkr.strategos.server.persistence.*
import com.drkkr.strategos.server.pipeline.JournalScreener
import com.drkkr.strategos.server.pipeline.RollingSummaryService
import com.drkkr.strategos.server.pipeline.TurnFailed
import com.drkkr.strategos.server.pipeline.TurnPipeline
import com.drkkr.strategos.server.service.StrategosService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import java.io.File

/** Everything wired once from config. Tests build one with a fake [LlmProvider] and an in-memory database. */
class AppContainer(
    val config: ServerConfig,
    llm: LlmProvider? = LlmRouter.build(config.llmProvider, config.llmApiKey, config.llmModel, config.llmBaseUrl),
    memoryOverride: MemoryService? = null,
) : AutoCloseable {
    val db = Database(config.databaseUrl)
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val router = LlmRouter(llm).also { it.keyInMemory = config.llmApiKey }
    val projects = ProjectRepository(db)
    val threads = ThreadRepository(db)
    val turns = TurnRepository(db)
    val journal = JournalRepository(db, config.journalKey?.let { JournalCipher(it.toCharArray()) })
    val settings = SettingsRepository(db)
    val memory: MemoryService = memoryOverride ?: InMemoryTemporalFallback(db).let { local ->
        config.graphitiUrl?.let { GraphitiMemoryAdapter(it, local) } ?: local
    }
    val summaries = RollingSummaryService(router, threads, turns)
    val pipeline = TurnPipeline(router, memory, projects, threads, turns, journal, summaries, background)
    val screener = JournalScreener(router, projects, journal, memory, config.userId)
    val service = StrategosService(db, projects, threads, turns, journal, memory, router, pipeline, screener, background)
        .also { it.userIdValue = config.userId }
    val mcp = StrategosMcpServer(service)

    override fun close() = db.close()
}

@Serializable private data class ApiError(val error: String, val retryable: Boolean = false)

fun Application.strategos(app: AppContainer) {
    install(ContentNegotiation) { json(StoreJson) }
    install(CORS) {
        if (app.config.corsOrigins.isEmpty()) {
            allowHost("localhost:5173"); allowHost("127.0.0.1:5173")         // the Vite dev server
        } else app.config.corsOrigins.forEach { o -> allowHost(o.removePrefix("http://").removePrefix("https://"), listOf("http", "https")) }
        allowHeader(HttpHeaders.ContentType); allowHeader(HttpHeaders.Authorization); allowHeader("Mcp-Session-Id")
        allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Patch); allowMethod(HttpMethod.Delete)
    }
    install(StatusPages) {
        exception<NoSuchElementException> { call, e -> call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "Not found")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Bad request")) }
        exception<TurnFailed> { call, e -> call.respond(if (e.retryable) HttpStatusCode.ServiceUnavailable else HttpStatusCode.BadGateway, ApiError(e.message ?: "Failed", e.retryable)) }
        // Never echo internals (or user text) back in a 500.
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled ${e::class.simpleName}")
            call.respond(HttpStatusCode.InternalServerError, ApiError("Something went wrong"))
        }
    }
    // On a server, set STRATEGOS_AUTH_TOKEN: every /api and /mcp call then needs `Authorization: Bearer <token>`.
    app.config.authToken?.let { token ->
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            val open = path == "/api/health" || !(path.startsWith("/api") || path.startsWith("/mcp"))
            if (!open && call.request.header(HttpHeaders.Authorization) != "Bearer $token" && call.request.httpMethod != HttpMethod.Options) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Unauthorized")); finish()
            }
        }
    }
    routing {
        strategosApi(app.service, app.settings)
        mcpHttp(app.mcp)
        val web = File(app.config.webDir)
        if (web.isDirectory) singlePageApplication { filesPath = web.absolutePath; defaultPage = "index.html" }
    }
}

fun main() {
    val config = ServerConfig.fromEnv()
    val app = AppContainer(config)
    if (config.mcpTransport == "stdio") {
        // Claude Desktop / Claude Code launch this process; stdout carries only MCP messages.
        app.mcp.runStdio()
        app.close()
        return
    }
    val host = System.getenv("HOST") ?: "127.0.0.1"
    // Reachable by others (a LAN, a VPS) means a token: never serve the API and memory open to the network.
    require(host in setOf("127.0.0.1", "localhost", "::1") || config.authToken != null) {
        "HOST=$host is reachable from the network: set STRATEGOS_AUTH_TOKEN first."
    }
    embeddedServer(Netty, port = config.port, host = host) { strategos(app) }.start(wait = true)
}
