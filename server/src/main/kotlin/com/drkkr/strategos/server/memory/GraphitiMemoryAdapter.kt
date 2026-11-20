package com.drkkr.strategos.server.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Graphiti temporal memory (SDD §3.2) through the small `graphiti-service` in this repo (graphiti-service/app.py, run by
 * docker-compose). Graphiti extracts entities and time-bounded edges from each episode with DeepSeek and embeds them with a
 * local Ollama model.
 *
 * Every episode also goes to the [fallback], which keeps the deterministic fact ledger. Reads merge Graphiti's facts with the
 * ledger; when Graphiti is down or slow, the fallback answers alone, so a turn never fails because memory did.
 */
class GraphitiMemoryAdapter(
    private val baseUrl: String,
    private val fallback: MemoryService,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
) : MemoryService {
    override val backend = "graphiti"

    override suspend fun ingest(episode: Episode) {
        fallback.ingest(episode)
        val body = buildJsonObject {
            put("group_id", episode.groupId)
            put("name", episode.name)
            put("episode_body", episode.body + episode.facts.joinToString("") { "\n${it.subject} ${it.predicate}: ${it.statement}" })
            put("source_description", episode.sourceDescription)
            put("reference_time", Instant.ofEpochMilli(episode.referenceTime).toString())
        }
        post("/episodes", body)
    }

    override suspend fun retrieveContext(groupIds: List<String>, query: String, entities: List<String>, limit: Int): MemoryContext {
        val local = fallback.retrieveContext(groupIds, query, entities, limit)
        val remote = searchRemote(groupIds, (listOf(query) + entities).joinToString(" "), limit) ?: return local
        val current = (local.currentFacts + remote.filter { it.current }).distinctBy { InMemoryTemporalFallback.norm(it.statement) }
        val superseded = (local.superseded + remote.filter { !it.current }).distinctBy { InMemoryTemporalFallback.norm(it.statement) }
        return MemoryContext(current.take(limit * 2), superseded.take(limit), local.episodes, backend)
    }

    override suspend fun searchFacts(groupIds: List<String>, query: String, limit: Int, includeSuperseded: Boolean): List<MemoryFact> {
        val remote = searchRemote(groupIds, query, limit) ?: return fallback.searchFacts(groupIds, query, limit, includeSuperseded)
        return remote.filter { includeSuperseded || it.current }
            .sortedWith(compareByDescending<MemoryFact> { it.current }.thenByDescending { it.validFrom })
            .take(limit)
    }

    override suspend fun healthy(): Boolean = get("/health") != null

    private suspend fun searchRemote(groupIds: List<String>, query: String, limit: Int): List<MemoryFact>? {
        val res = post("/search", buildJsonObject {
            putJsonArray("group_ids") { groupIds.forEach { add(it) } }
            put("query", query); put("num_results", limit)
        }) ?: return null
        return res["facts"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val fact = o["fact"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MemoryFact(
                id = o["uuid"]?.jsonPrimitive?.contentOrNull ?: fact.hashCode().toString(),
                groupId = o["group_id"]?.jsonPrimitive?.contentOrNull ?: groupIds.first(),
                subject = o["source"]?.jsonPrimitive?.contentOrNull ?: "",
                predicate = o["name"]?.jsonPrimitive?.contentOrNull?.lowercase()?.replace('_', ' ') ?: "",
                statement = fact,
                validFrom = time(o["valid_at"]) ?: time(o["created_at"]) ?: 0L,
                invalidAt = time(o["invalid_at"]) ?: time(o["expired_at"]),
                episodeName = o["episodes"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "",
                source = "graphiti",
            )
        }
    }

    private fun time(e: JsonElement?): Long? = e?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it.let { s -> if (s.endsWith("Z") || s.contains('+')) s else "${s}Z" }).toEpochMilli() }.getOrNull() }

    private suspend fun post(path: String, body: JsonObject): JsonObject? = call(
        Request.Builder().url(baseUrl.trimEnd('/') + path).post(body.toString().toRequestBody(JSON)).build()
    )

    private suspend fun get(path: String): JsonObject? = call(Request.Builder().url(baseUrl.trimEnd('/') + path).get().build())

    private suspend fun call(req: Request): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null
                else r.body?.string()?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
            }
        }.getOrNull()
    }

    companion object { private val JSON = "application/json".toMediaType() }
}
