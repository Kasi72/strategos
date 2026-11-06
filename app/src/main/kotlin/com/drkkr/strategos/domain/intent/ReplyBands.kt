package com.drkkr.strategos.domain.intent

/**
 * How likely they are to go along, in words only (BLUEPRINT §5.4; never a number or a percentage reaches the user).
 * Each bound has a source note (the pm6.1 rule: no unexplained constant).
 */
object ReplyBands {
    /** At or above this, "likely to go along": clearly better than even, the same as the "could go either way" band's top. */
    const val LIKELY = 0.60
    /** Below this, "likely to push back": the mirror of [LIKELY] around even odds would be 0.40; the blueprint widens it to 0.35. */
    const val UNLIKELY = 0.35

    const val GO_ALONG = "likely to go along"
    const val EITHER_WAY = "could go either way"
    const val PUSH_BACK = "likely to push back"

    val notes: Map<String, String> = mapOf(
        "LIKELY" to "BLUEPRINT §5.4: >= 0.60 reads as likely; ResponseModel.Likely already calls 0.4–0.6 'could go either way'",
        "UNLIKELY" to "BLUEPRINT §5.4: < 0.35 reads as likely to push back"
    )

    fun of(pReceptive: Double): String = when {
        pReceptive >= LIKELY -> GO_ALONG
        pReceptive >= UNLIKELY -> EITHER_WAY
        else -> PUSH_BACK
    }
}
