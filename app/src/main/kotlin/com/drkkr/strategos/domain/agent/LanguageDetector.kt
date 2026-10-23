package com.drkkr.strategos.domain.agent

object LanguageDetector {
    private val tamil = '஀'..'௿'

    /** "en", "ta" or "ta-en" for mixed Tamil and English text. */
    fun detect(text: String): String {
        val hasTamil = text.any { it in tamil }
        val hasEnglish = text.any { it.isLetter() && it.code < 0x200 }
        return when {
            hasTamil && hasEnglish -> "ta-en"
            hasTamil -> "ta"
            else -> "en"
        }
    }
}
