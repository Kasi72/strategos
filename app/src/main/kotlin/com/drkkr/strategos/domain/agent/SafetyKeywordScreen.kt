package com.drkkr.strategos.domain.agent

/**
 * Deterministic safety net (blueprint §17.4): runs on every USER message before it is sent, independent of the LLM.
 * A match shows the support card at once; the message is still sent.
 *
 * CLINICIAN REVIEW REQUIRED (§17.6): the default phrases below are a starting list written by the implementer.
 * They must be reviewed (English and Tamil) before patients use the app.
 */
object SafetyKeywordScreen {
    val DEFAULT_PHRASES: List<String> = listOf(
        // Self-harm and suicide
        "kill myself", "killing myself", "suicide", "suicidal", "end my life", "end it all", "take my own life",
        "want to die", "wanna die", "better off dead", "hurt myself", "harm myself", "self harm", "self-harm", "cut myself",
        // Harm to others
        "kill him", "kill her", "kill them", "hurt someone", "hurt him", "hurt her",
        // Abuse and danger
        "hits me", "beats me", "abusing me", "being abused", "not safe at home", "afraid for my life", "threatened to kill",
        // Tamil (தமிழ்)
        "தற்கொலை", "சாக வேண்டும்", "சாகணும்", "உயிரை மாய்த்து", "என்னை நானே காயப்படுத்த", "கொன்று விடுவேன்", "அடிக்கிறார்", "அடிக்கிறான்"
    )

    private val whitespace = Regex("\\s+")

    fun check(text: String, phrases: List<String> = DEFAULT_PHRASES): Boolean {
        val normalized = text.lowercase().replace(whitespace, " ")
        return phrases.any { p -> p.isNotBlank() && normalized.contains(p.lowercase().replace(whitespace, " ")) }
    }
}
