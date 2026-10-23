package com.drkkr.strategos.agent

import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmErrors
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.ModelInfo
import com.drkkr.strategos.llm.ModelListResult
import com.drkkr.strategos.llm.PriceTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where the API key lives. Backed by SecretStore (Keystore) in the app, by a map in tests. */
interface KeyVault {
    suspend fun put(key: String)
    suspend fun get(): String?
    suspend fun clear()
    suspend fun has(): Boolean
}

enum class KeyState { NONE, CHECKING, CONNECTED, REJECTED, OFFLINE, ERROR }

data class ModelRow(val id: String, val priceLabel: String, val recommended: Boolean, val current: Boolean)

data class AiSettingsUi(
    val enabled: Boolean = false,
    val hasKey: Boolean = false,
    val keyLast4: String? = null,
    val keyState: KeyState = KeyState.NONE,
    val message: String? = null,
    /** The key passed its test but the user hasn't accepted the privacy text yet. */
    val needsConsent: Boolean = false,
    val keyInvalid: Boolean = false,
    val model: String = AgentConfig.DEFAULT_MODEL,
    val models: List<ModelRow> = emptyList(),
    val modelMessage: String? = null
) {
    val ready: Boolean get() = enabled && hasKey && !needsConsent
}

/**
 * Everything on the AI settings screen (blueprint §11.5, §11.5.1, §11.5.2), without any Android types.
 * The provider is Groq (PROVIDER OVERRIDE), so texts say "Groq" where the blueprint says "xAI".
 */
class AiSettingsController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val vault: KeyVault,
    private val llm: LlmClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow(AiSettingsUi())
    val state: StateFlow<AiSettingsUi> = _state.asStateFlow()

    fun load(): Job = scope.launch { reload(); loadModels(force = false) }

    private suspend fun reload() {
        val has = vault.has()
        val consented = settings.consentVersion() == AgentConfig.CONSENT_VERSION
        _state.update {
            it.copy(
                enabled = settings.aiEnabled(), hasKey = has, keyLast4 = if (has) vault.get()?.takeLast(4) else null,
                keyState = if (has && it.keyState == KeyState.NONE) KeyState.CONNECTED else if (!has) KeyState.NONE else it.keyState,
                needsConsent = has && !consented && it.needsConsent, keyInvalid = settings.keyInvalid(), model = settings.model()
            )
        }
    }

    // ── Key (§11.5.1) ─────────────────────────────────────────────────────────

    /** Trim, strip quotes and stray whitespace from a pasted key. */
    fun normalize(raw: String): String = raw.trim().trim('"', '\'', '`', '“', '”').trim().replace(Regex("\\s+"), "")

    fun saveAndConnect(raw: String): Job = scope.launch {
        val key = normalize(raw)
        if (key.isEmpty()) {
            _state.update { it.copy(keyState = KeyState.ERROR, message = "Enter a key.") }
            return@launch
        }
        _state.update { it.copy(keyState = KeyState.CHECKING, message = null) }
        when (val r = llm.testKey(key, settings.model())) {
            is LlmResult.Ok -> store(key, note = null)
            is LlmResult.Unauthorized -> _state.update {
                it.copy(keyState = KeyState.REJECTED, message = "Groq didn't accept this key. Check it and try again.")
            }
            is LlmResult.Network -> _state.update {
                it.copy(keyState = KeyState.OFFLINE, message = "Couldn't reach Groq. Your key wasn't saved. Try again when you're online.")
            }
            is LlmResult.RateLimited -> store(key, note = "Saved. Groq is busy right now, so the first reply may be slow.")
            is LlmResult.HttpError ->
                if (r.status in 500..599 || LlmErrors.isCreditProblem(r)) store(key, note = "Saved. ${LlmErrors.userMessage(r)}")
                else _state.update { it.copy(keyState = KeyState.ERROR, message = LlmErrors.userMessage(r)) }
        }
    }

    private suspend fun store(key: String, note: String?) {
        vault.put(key)
        settings.setAiEnabled(true)
        settings.setKeyInvalid(false)
        val consented = settings.consentVersion() == AgentConfig.CONSENT_VERSION
        _state.update {
            it.copy(
                enabled = true, hasKey = true, keyLast4 = key.takeLast(4), keyState = KeyState.CONNECTED, message = note,
                needsConsent = !consented, keyInvalid = false
            )
        }
        loadModels(force = true)
    }

    fun acceptConsent(): Job = scope.launch {
        settings.setConsentVersion(AgentConfig.CONSENT_VERSION)
        settings.setAiEnabled(true)
        reload()
        _state.update { it.copy(needsConsent = false) }
    }

    /** Turning on without consent or a key leaves the toggle off and says why through [AiSettingsUi]. */
    fun setEnabled(on: Boolean): Job = scope.launch {
        if (!on) {
            settings.setAiEnabled(false)
        } else if (vault.has()) {
            val consented = settings.consentVersion() == AgentConfig.CONSENT_VERSION
            settings.setAiEnabled(consented)
            _state.update { it.copy(needsConsent = !consented) }
        }
        reload()
    }

    fun remove(): Job = scope.launch {
        vault.clear()
        settings.setAiEnabled(false)
        _state.update { it.copy(hasKey = false, keyLast4 = null, keyState = KeyState.NONE, message = null, needsConsent = false, enabled = false) }
    }

    // ── Model (§11.5.2) ───────────────────────────────────────────────────────

    fun refreshModels(force: Boolean = true): Job = scope.launch { loadModels(force) }

    private suspend fun loadModels(force: Boolean) {
        val cached = parseCache(settings.modelListCache())
        val fresh = cached != null && now() - cached.first < CACHE_FRESH_MS
        val list: List<ModelInfo> = if (!force && fresh) cached!!.second else when (val r = if (vault.has()) llm.listModels() else null) {
            is ModelListResult.Ok -> filterText(r.models).also { settings.setModelListCache(encodeCache(it)) }
            else -> cached?.takeIf { now() - it.first < CACHE_MAX_MS }?.second ?: AgentConfig.MODEL_OPTIONS.map { ModelInfo(it) }
        }
        _state.update { it.copy(models = rows(list, settings.model())) }
    }

    /** Pings the chosen model with the stored key; only a working model is saved. */
    fun selectModel(id: String): Job = scope.launch {
        val model = id.trim()
        val key = vault.get()
        if (model.isEmpty() || key == null) return@launch
        when (val r = llm.testKey(key, model)) {
            is LlmResult.Ok, is LlmResult.RateLimited -> {
                settings.setModel(model)
                _state.update { it.copy(model = model, modelMessage = "Now using $model.", models = rows(it.models.toModelInfo(model), model)) }
            }
            is LlmResult.Network -> _state.update { it.copy(modelMessage = "Couldn't check that model. Try again when you're online.") }
            is LlmResult.HttpError ->
                if (r.status == 404 || r.code == "model_not_found") _state.update { it.copy(modelMessage = "That model isn't available on your key.") }
                else _state.update { it.copy(modelMessage = LlmErrors.userMessage(r)) }
            is LlmResult.Unauthorized -> _state.update { it.copy(modelMessage = "Your key stopped working. Update it above.", keyInvalid = true) }
        }
    }

    private fun List<ModelRow>.toModelInfo(extra: String): List<ModelInfo> = (map { ModelInfo(it.id) } + ModelInfo(extra)).distinctBy { it.id }

    private fun rows(models: List<ModelInfo>, current: String): List<ModelRow> {
        val order = listOf(AgentConfig.DEFAULT_MODEL) + AgentConfig.MODEL_OPTIONS.filter { it != AgentConfig.DEFAULT_MODEL }
        val sorted = models.distinctBy { it.id }.sortedWith(compareBy({ order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.id }))
        return sorted.map { m ->
            val price = PriceTable.forModel(m.id)
            val label = when {
                m.inputPricePerM != null && m.outputPricePerM != null -> "$${fmt(m.inputPricePerM)} / $${fmt(m.outputPricePerM)} per 1M"
                price != null -> "$${fmt(price.input)} / $${fmt(price.output)} per 1M"
                else -> "price unknown"
            }
            ModelRow(m.id, label, recommended = m.id == AgentConfig.DEFAULT_MODEL, current = m.id == current)
        }
    }

    private fun fmt(v: Double) = if (v >= 1) "%.2f".format(v) else "%.3f".format(v).trimEnd('0').trimEnd('.')

    private fun filterText(models: List<ModelInfo>) = models.filter { m -> EXCLUDED.none { m.id.contains(it, ignoreCase = true) } }

    private fun encodeCache(models: List<ModelInfo>): String = buildJsonObject {
        put("ts", now())
        put("models", buildJsonArray {
            models.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id); m.inputPricePerM?.let { put("in", it) }; m.outputPricePerM?.let { put("out", it) }
                })
            }
        })
    }.toString()

    private fun parseCache(json: String?): Pair<Long, List<ModelInfo>>? = runCatching {
        val o = Json.parseToJsonElement(json ?: return null).jsonObject
        val ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: return null
        ts to (o["models"] as JsonArray).map { e ->
            val m = e.jsonObject
            ModelInfo(m.getValue("id").jsonPrimitive.content, (m["in"] as? JsonPrimitive)?.doubleOrNull, (m["out"] as? JsonPrimitive)?.doubleOrNull)
        }
    }.getOrNull()

    companion object {
        const val CACHE_FRESH_MS = 24 * 60 * 60 * 1000L
        const val CACHE_MAX_MS = 7 * CACHE_FRESH_MS
        /** Not chat models (image, audio, embeddings, safety classifiers). Groq's list also holds speech and guard models (API_NOTES S7). */
        val EXCLUDED = listOf("image", "vision", "video", "embed", "audio", "whisper", "orpheus", "prompt-guard", "safeguard", "tts")
    }
}
