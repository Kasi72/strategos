package com.drkkr.strategos.server.persistence

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM for journal text at rest, keyed from JOURNAL_ENCRYPTION_KEY (PBKDF2). Output: base64(salt | iv | ciphertext). */
class JournalCipher(private val passphrase: CharArray) {
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(salt), GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(salt + iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(encoded: String): String? = runCatching {
        val all = Base64.getDecoder().decode(encoded)
        val salt = all.copyOfRange(0, 16); val iv = all.copyOfRange(16, 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(salt), GCMParameterSpec(128, iv)) }
        String(c.doFinal(all.copyOfRange(28, all.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun key(salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, 120_000, 256)).encoded, "AES"
    )
}
