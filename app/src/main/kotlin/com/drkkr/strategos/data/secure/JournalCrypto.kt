package com.drkkr.strategos.data.secure

import java.util.Base64

/** Encrypts journal text before it reaches SQLite. Kept an interface so JVM tests need no hardware Keystore. */
// BLUEPRINT-QUESTION: §6.1 says `fun interface` but gives it two abstract methods, which Kotlin rejects. Plain interface used.
interface TextCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/** AES-GCM through the Android Keystore, Base64 on the outside. */
class KeystoreTextCipher(private val cipher: KeystoreCipher = KeystoreCipher("strategos_journal_v1")) : TextCipher {
    override fun encrypt(plainText: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(plainText.toByteArray(Charsets.UTF_8)))

    override fun decrypt(cipherText: String): String =
        String(cipher.decrypt(Base64.getDecoder().decode(cipherText)), Charsets.UTF_8)
}

/** Deterministic obfuscation for unit tests only. Not secure. */
class XorBase64TestCipher : TextCipher {
    override fun encrypt(plainText: String): String =
        Base64.getEncoder().encodeToString(plainText.toByteArray(Charsets.UTF_8).map { (it.toInt() xor KEY).toByte() }.toByteArray())

    override fun decrypt(cipherText: String): String =
        String(Base64.getDecoder().decode(cipherText).map { (it.toInt() xor KEY).toByte() }.toByteArray(), Charsets.UTF_8)

    private companion object { const val KEY = 0x5A }
}
