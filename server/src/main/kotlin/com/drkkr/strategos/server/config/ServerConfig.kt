package com.drkkr.strategos.server.config

/**
 * Everything that differs between localhost and a server comes from the environment (SDD §1, "zero re-architecture").
 * Nothing secret has a default, and none of it is ever logged.
 */
data class ServerConfig(
    val port: Int = 8080,
    /** `jdbc:sqlite:./strategos.db` locally, `jdbc:postgresql://host/db?user=..&password=..` on a server. */
    val databaseUrl: String = "jdbc:sqlite:./strategos.db",
    val llmProvider: String = "deepseek",
    val llmApiKey: String? = null,
    val llmModel: String? = null,
    val llmBaseUrl: String? = null,
    /** The graphiti-service from docker-compose (e.g. http://localhost:8000). Null = the offline fallback only. */
    val graphitiUrl: String? = null,
    /** "http" serves /mcp next to the API; "stdio" runs the MCP server on stdin/stdout for Claude Desktop/Code. */
    val mcpTransport: String = "http",
    /** When set, every /api and /mcp call needs `Authorization: Bearer <token>` (on a server, always set it). */
    val authToken: String? = null,
    val corsOrigins: List<String> = emptyList(),
    /** A passphrase; when set, journal entries are encrypted at rest (AES-GCM). */
    val journalKey: String? = null,
    /** Where the built web UI lives (web/dist). Served at /. */
    val webDir: String = "web/dist",
    val userId: String = "local",
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() } ?: "jdbc:sqlite:./strategos.db",
            llmProvider = env["LLM_PROVIDER"]?.takeIf { it.isNotBlank() } ?: "deepseek",
            llmApiKey = (env["LLM_API_KEY"] ?: env["DEEPSEEK_API_KEY"])?.takeIf { it.isNotBlank() },
            llmModel = env["LLM_MODEL"]?.takeIf { it.isNotBlank() },
            llmBaseUrl = env["LLM_BASE_URL"]?.takeIf { it.isNotBlank() },
            graphitiUrl = env["GRAPHITI_URL"]?.takeIf { it.isNotBlank() },
            mcpTransport = env["MCP_TRANSPORT"]?.lowercase()?.takeIf { it.isNotBlank() } ?: "http",
            authToken = env["STRATEGOS_AUTH_TOKEN"]?.takeIf { it.isNotBlank() },
            corsOrigins = env["CORS_ORIGINS"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            journalKey = env["JOURNAL_ENCRYPTION_KEY"]?.takeIf { it.isNotBlank() },
            webDir = env["WEB_DIR"]?.takeIf { it.isNotBlank() } ?: "web/dist",
            userId = env["STRATEGOS_USER_ID"]?.takeIf { it.isNotBlank() } ?: "local",
        )
    }
}
