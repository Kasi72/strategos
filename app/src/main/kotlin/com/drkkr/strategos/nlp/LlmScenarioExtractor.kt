package com.drkkr.strategos.nlp

import com.drkkr.strategos.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * LLM-backed scenario extraction via the Anthropic Messages API.
 *
 * Uses the platform HttpsURLConnection (no third-party HTTP dependency, no APK
 * bloat). The network layer and the pure JSON->domain mapping are separated so
 * [mapExtractionJson] can be unit-tested without a live call. Any failure
 * (no key, network error, malformed output) returns null; callers fall back to
 * the deterministic [ScenarioParser].
 */
object LlmScenarioExtractor {

    /** Default model. Override per call; e.g. "claude-haiku-5-5" for cheaper runs. */
    const val DEFAULT_MODEL = "claude-opus-5-5"

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Result(val scenario: Scenario?, val error: String?)

    // ── Wire DTOs ─────────────────────────────────────────────────────────────
    @Serializable private data class MsgRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<Msg>,
        val output_config: OutputConfig? = null
    )
    @Serializable private data class Msg(val role: String, val content: String)
    @Serializable private data class OutputConfig(val effort: String)

    @Serializable private data class MsgResponse(
        val content: List<Block> = emptyList(),
        val stop_reason: String? = null
    )
    @Serializable private data class Block(val type: String = "", val text: String = "")

    // Extraction schema we ask the model to return.
    @Serializable private data class Extraction(
        val title: String = "",
        val description: String = "",
        val stakeholders: List<ExStakeholder> = emptyList(),
        val actions: List<ExAction> = emptyList(),
        val constraints: List<ExConstraint> = emptyList(),
        val beliefs: List<ExBelief> = emptyList()
    )
    @Serializable private data class ExStakeholder(
        val name: String = "",
        val role: String = "",
        val authority: Int = 4,
        val influence: Int = 4,
        val canVeto: Boolean = false,
        val goals: List<String> = emptyList()
    )
    @Serializable private data class ExAction(
        val label: String = "",
        val owner: String = "",
        val description: String = ""
    )
    @Serializable private data class ExConstraint(val label: String = "", val kind: String = "POLICY")
    @Serializable private data class ExBelief(val label: String = "", val probability: Double = 0.5)

    private val systemPrompt = """
        You extract a structured decision scenario from free text for a game-theory
        decision-analysis app. Return ONLY a single JSON object (no markdown, no prose)
        with this exact shape:
        {
          "title": string,
          "description": string,
          "stakeholders": [{"name": string, "role": string, "authority": 0-10,
                            "influence": 0-10, "canVeto": boolean, "goals": [string]}],
          "actions": [{"label": string, "owner": string (a stakeholder name or ""),
                       "description": string}],
          "constraints": [{"label": string, "kind": "LEGAL|ETHICAL|SAFETY|POLICY|PHYSICAL|RESOURCE|USER_HARD"}],
          "beliefs": [{"label": string, "probability": 0.0-1.0}]
        }
        Infer authority/influence/canVeto from context. If something is not stated,
        omit it rather than inventing specifics. Output JSON only.
    """.trimIndent()

    // ── Public: full network extraction ───────────────────────────────────────
    suspend fun extract(
        rawText: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        existingId: String? = null
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result(null, "No API key configured")
        try {
            val body = json.encodeToString(
                MsgRequest(
                    model = model,
                    max_tokens = 4096,
                    system = systemPrompt,
                    messages = listOf(Msg("user", rawText)),
                    output_config = OutputConfig("low")
                )
            )
            val responseText = postMessages(body, apiKey)
            val extractedJson = extractAssistantText(responseText)
                ?: return@withContext Result(null, "Empty response from model")
            val scenario = mapExtractionJson(extractedJson, rawText, existingId)
                ?: return@withContext Result(null, "Could not parse extracted scenario")
            Result(scenario, null)
        } catch (e: Exception) {
            Result(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun postMessages(body: String, apiKey: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("Anthropic API HTTP $code: ${text.take(300)}")
        return text
    }

    /** Pull the assistant's text out of a Messages API response body. */
    fun extractAssistantText(responseBody: String): String? {
        val parsed = runCatching { json.decodeFromString<MsgResponse>(responseBody) }.getOrNull() ?: return null
        return parsed.content.firstOrNull { it.type == "text" }?.text?.takeIf { it.isNotBlank() }
    }

    // ── Public: pure JSON -> domain mapping (unit-testable, no network) ────────
    fun mapExtractionJson(jsonText: String, rawText: String, existingId: String? = null): Scenario? {
        val cleaned = stripFences(jsonText)
        val ex = runCatching { json.decodeFromString<Extraction>(cleaned) }.getOrNull() ?: return null
        if (ex.stakeholders.isEmpty() && ex.actions.isEmpty() && ex.title.isBlank()) return null

        val stakeholders = ex.stakeholders
            .filter { it.name.isNotBlank() }
            .map { s ->
                Stakeholder(
                    name = s.name.trim(),
                    role = s.role.trim(),
                    authority = s.authority.coerceIn(0, 10),
                    influence = s.influence.coerceIn(0, 10),
                    canVeto = s.canVeto,
                    goals = s.goals.map { it.trim() }.filter { it.isNotBlank() }
                )
            }

        val byName = stakeholders.associateBy { it.name.lowercase() }
        val actions = ex.actions
            .filter { it.label.isNotBlank() }
            .map { a ->
                val ownerId = byName[a.owner.trim().lowercase()]?.id ?: ""
                Action(stakeholderId = ownerId, label = a.label.trim(), description = a.description.trim())
            }

        val constraints = ex.constraints
            .filter { it.label.isNotBlank() }
            .map { c ->
                Constraint(label = c.label.trim(), kind = parseConstraintKind(c.kind))
            }

        val beliefs = ex.beliefs
            .filter { it.label.isNotBlank() }
            .map { b -> Belief(label = b.label.trim(), probability = b.probability.coerceIn(0.0, 1.0), status = FactStatus.AI_INFERRED) }

        return Scenario(
            id = existingId ?: UUID.randomUUID().toString(),
            title = ex.title.ifBlank { rawText.take(60) },
            description = ex.description.ifBlank { rawText },
            stakeholders = stakeholders,
            actions = actions,
            constraints = constraints,
            beliefs = beliefs,
            assumptionLog = listOf(
                AssumptionEntry("Scenario extracted by LLM from free text — review all fields", FactStatus.AI_INFERRED),
                AssumptionEntry("Payoffs not generated; complete the payoff matrix before analysis", FactStatus.UNKNOWN)
            )
        )
    }

    private fun parseConstraintKind(raw: String): Constraint.Kind =
        runCatching { Constraint.Kind.valueOf(raw.trim().uppercase()) }.getOrDefault(Constraint.Kind.POLICY)

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end).trim()
        }
        // Defensive: isolate the outermost JSON object if the model added stray text.
        val start = t.indexOf('{'); val last = t.lastIndexOf('}')
        return if (start >= 0 && last > start) t.substring(start, last + 1) else t
    }
}
