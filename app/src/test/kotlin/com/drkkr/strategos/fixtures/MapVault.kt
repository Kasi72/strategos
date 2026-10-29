package com.drkkr.strategos.fixtures

import com.drkkr.strategos.agent.KeyVault
import com.drkkr.strategos.data.db.dao.SettingDao
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.SettingsRepository

/**
 * In-memory KeyVault for tests (the real one needs the Android Keystore). Like SecretStore it also writes the
 * `secret.xai_api_key` setting row (here as plain text) when given a [dao], because `aiReady()` reads that row.
 */
class MapVault(private val dao: SettingDao? = null, private var key: String? = null) : KeyVault {
    override suspend fun put(key: String) { this.key = key; dao?.put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, key)) }
    override suspend fun get(): String? = key
    override suspend fun clear() { key = null; dao?.remove(SettingsRepository.SECRET_XAI_KEY) }
    override suspend fun has(): Boolean = key != null
}
