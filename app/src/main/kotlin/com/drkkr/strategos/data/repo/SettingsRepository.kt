package com.drkkr.strategos.data.repo

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SettingsRepository(private val db: StrategosDatabase) {

    private val dao get() = db.settingDao()

    suspend fun aiEnabled(): Boolean = dao.get(AI_ENABLED) == "true"
    suspend fun setAiEnabled(v: Boolean) = put(AI_ENABLED, v.toString())

    suspend fun consentVersion(): Int? = dao.get(AI_CONSENT_VERSION)?.toIntOrNull()
    suspend fun setConsentVersion(v: Int) = put(AI_CONSENT_VERSION, v.toString())

    suspend fun model(): String = dao.get(AI_MODEL) ?: AgentConfig.DEFAULT_MODEL
    suspend fun setModel(id: String) = put(AI_MODEL, id)

    suspend fun modelListCache(): String? = dao.get(AI_MODEL_LIST_CACHE)
    suspend fun setModelListCache(json: String) = put(AI_MODEL_LIST_CACHE, json)

    suspend fun includeMemory(): Boolean = (dao.get(AI_INCLUDE_MEMORY) ?: "true") == "true"
    suspend fun setIncludeMemory(v: Boolean) = put(AI_INCLUDE_MEMORY, v.toString())

    suspend fun autoContinue(): Boolean = (dao.get(AI_AUTO_CONTINUE) ?: "true") == "true"
    suspend fun setAutoContinue(v: Boolean) = put(AI_AUTO_CONTINUE, v.toString())

    suspend fun uiMode(): UiMode = dao.get(UI_MODE)?.let { runCatching { UiMode.valueOf(it) }.getOrNull() } ?: UiMode.GUIDED
    fun observeUiMode(): Flow<String?> = dao.observe(UI_MODE)
    suspend fun setUiMode(m: UiMode) = put(UI_MODE, m.name)

    /** Reveal the math: how much of the engine shows. On-tap is the default (board 16, open question #1). */
    suspend fun revealMath(): RevealMath = dao.get(UI_REVEAL_MATH)?.let { runCatching { RevealMath.valueOf(it) }.getOrNull() } ?: RevealMath.ON_TAP
    fun observeRevealMath(): Flow<String?> = dao.observe(UI_REVEAL_MATH)
    suspend fun setRevealMath(m: RevealMath) = put(UI_REVEAL_MATH, m.name)

    suspend fun expertPin(): String? = dao.get(UI_EXPERT_PIN)
    suspend fun setExpertPin(ciphertext: String?) = if (ciphertext == null) dao.remove(UI_EXPERT_PIN) else put(UI_EXPERT_PIN, ciphertext)

    suspend fun supportText(): String = dao.get(SAFETY_SUPPORT_TEXT) ?: DEFAULT_SUPPORT_TEXT

    /** The text for the phone's region: a saved override wins; otherwise the India text only in India. */
    suspend fun supportTextFor(country: String?): String = dao.get(SAFETY_SUPPORT_TEXT) ?: defaultSupportText(country)
    suspend fun setSupportText(v: String) = put(SAFETY_SUPPORT_TEXT, v)

    suspend fun quickMode(): Boolean = dao.get(QUICK_MODE) == "true"
    suspend fun setQuickMode(v: Boolean) = put(QUICK_MODE, v.toString())

    suspend fun legacyMigrated(): Boolean = dao.get(LEGACY_MIGRATED) == "1"
    suspend fun setLegacyMigrated() = put(LEGACY_MIGRATED, "1")

    // Power Mode (blueprint §5): per conversation, off by default (I-P1); the lock forces it off everywhere (I-P4).
    // BLUEPRINT-QUESTION: there is no single-conversation delete path to clear `power_mode:<cid>` from; orphaned keys are harmless.
    suspend fun isPowerMode(cid: String): Boolean = !powerLock() && dao.get(powerModeKey(cid)) == "1"
    suspend fun setPowerMode(cid: String, on: Boolean) = put(powerModeKey(cid), if (on) "1" else "0")
    fun observePowerMode(cid: String): Flow<Boolean> =
        combine(dao.observe(powerModeKey(cid)), dao.observe(POWER_MODE_LOCK)) { on, lock -> on == "1" && lock != "1" }
    suspend fun powerLock(): Boolean = dao.get(POWER_MODE_LOCK) == "1"
    suspend fun setPowerLock(on: Boolean) = put(POWER_MODE_LOCK, if (on) "1" else "0")

    suspend fun get(key: String): String? = dao.get(key)

    /** Set when a turn gets 401: the stored key stopped working. Cleared by a successful save (§11.5.1). */
    suspend fun keyInvalid(): Boolean = dao.get(AI_KEY_INVALID) == "true"
    suspend fun setKeyInvalid(v: Boolean) = put(AI_KEY_INVALID, v.toString())

    /** Emits whenever anything that decides `aiReady` changes, so screens update without a restart (§11.5.1). */
    fun observeAiFlags(): Flow<List<String?>> = combine(
        listOf(AI_ENABLED, AI_CONSENT_VERSION, SECRET_XAI_KEY, AI_KEY_INVALID, AI_MODEL).map { dao.observe(it) }
    ) { values -> values.toList() }

    /** enabled AND consent given for the current consent text AND a key is stored. */
    suspend fun aiReady(): Boolean =
        aiEnabled() && consentVersion() == AgentConfig.CONSENT_VERSION && dao.get(SECRET_XAI_KEY) != null

    private suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))

    companion object {
        const val AI_ENABLED = "ai.enabled"
        const val AI_CONSENT_VERSION = "ai.consent_version"
        const val AI_MODEL = "ai.model"
        const val AI_MODEL_LIST_CACHE = "ai.model_list_cache"
        const val AI_KEY_INVALID = "ai.key_invalid"
        const val AI_INCLUDE_MEMORY = "ai.include_memory"
        const val AI_AUTO_CONTINUE = "ai.auto_continue"
        const val UI_MODE = "ui.mode"
        const val UI_REVEAL_MATH = "ui.reveal_math"
        const val UI_EXPERT_PIN = "ui.expert_pin"
        const val SAFETY_SUPPORT_TEXT = "safety.support_text"
        const val LEGACY_MIGRATED = "legacy.migrated"
        const val QUICK_MODE = "ui.quick_mode"
        const val SECRET_XAI_KEY = "secret.xai_api_key"
        const val POWER_MODE_LOCK = "power_mode_lock"
        fun powerModeKey(cid: String) = "power_mode:$cid"

        // §17.6: review this text and verify the helpline number before release.
        const val DEFAULT_SUPPORT_TEXT =
            "If you're in danger or thinking about harming yourself, reach out to someone you trust or local emergency services now. " +
                "In India you can call Tele-MANAS on 14416."

        /** The India text (with the helpline) only for India; everywhere else it points to the local emergency number, with no specific number. */
        fun defaultSupportText(country: String?): String =
            if (country.equals("IN", ignoreCase = true)) DEFAULT_SUPPORT_TEXT
            else "If you're in danger or thinking about harming yourself, reach out to someone you trust or your local emergency number now."
    }
}
