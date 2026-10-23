package com.drkkr.strategos.data.secure

import android.util.Base64
import com.drkkr.strategos.data.db.dao.SettingDao
import com.drkkr.strategos.data.db.entities.SettingEntity

/** Stores the xAI key as Keystore ciphertext inside the (already SQLCipher-encrypted) settings table. */
class SecretStore(
    private val settings: SettingDao,
    private val cipher: KeystoreCipher        // alias "strategos_secret_v1"
) {
    suspend fun putXaiKey(key: String) {
        val blob = Base64.encodeToString(cipher.encrypt(key.toByteArray()), Base64.NO_WRAP)
        settings.put(SettingEntity(KEY, blob))
    }

    suspend fun getXaiKey(): String? =
        settings.get(KEY)?.let { String(cipher.decrypt(Base64.decode(it, Base64.NO_WRAP))) }

    suspend fun clearXaiKey() = settings.remove(KEY)

    suspend fun hasXaiKey(): Boolean = settings.get(KEY) != null

    private companion object { const val KEY = "secret.xai_api_key" }
}
