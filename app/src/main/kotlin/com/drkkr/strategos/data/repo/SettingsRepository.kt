package com.drkkr.strategos.data.repo

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.SettingEntity
import kotlinx.coroutines.flow.Flow

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

    suspend fun expertPin(): String? = dao.get(UI_EXPERT_PIN)
    suspend fun setExpertPin(ciphertext: String?) = if (ciphertext == null) dao.remove(UI_EXPERT_PIN) else put(UI_EXPERT_PIN, ciphertext)

    suspend fun supportText(): String = dao.get(SAFETY_SUPPORT_TEXT) ?: DEFAULT_SUPPORT_TEXT
    suspend fun setSupportText(v: String) = put(SAFETY_SUPPORT_TEXT, v)

    suspend fun legacyMigrated(): Boolean = dao.get(LEGACY_MIGRATED) == "1"
    suspend fun setLegacyMigrated() = put(LEGACY_MIGRATED, "1")

    suspend fun get(key: String): String? = dao.get(key)

    /** enabled AND consent given for the current consent text AND a key is stored. */
    suspend fun aiReady(): Boolean =
        aiEnabled() && consentVersion() == AgentConfig.CONSENT_VERSION && dao.get(SECRET_XAI_KEY) != null

    private suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))

    companion object {
        const val AI_ENABLED = "ai.enabled"
        const val AI_CONSENT_VERSION = "ai.consent_version"
        const val AI_MODEL = "ai.model"
        const val AI_MODEL_LIST_CACHE = "ai.model_list_cache"
        const val AI_INCLUDE_MEMORY = "ai.include_memory"
        const val AI_AUTO_CONTINUE = "ai.auto_continue"
        const val UI_MODE = "ui.mode"
        const val UI_EXPERT_PIN = "ui.expert_pin"
        const val SAFETY_SUPPORT_TEXT = "safety.support_text"
        const val LEGACY_MIGRATED = "legacy.migrated"
        const val SECRET_XAI_KEY = "secret.xai_api_key"

        // §17.6: the clinician must review this text and verify the helpline number before patients use the app.
        const val DEFAULT_SUPPORT_TEXT =
            "If you're in danger or thinking about harming yourself, contact your clinician or local emergency services now. " +
                "In India you can call Tele-MANAS on 14416."
    }
}
