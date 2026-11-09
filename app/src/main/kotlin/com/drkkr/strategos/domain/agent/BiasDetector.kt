package com.drkkr.strategos.domain.agent

/**
 * Cognitive bias detector. Pure domain — no android.* or androidx.* imports.
 *
 * Scans user message texts for linguistic markers associated with:
 *  - Sunk cost fallacy
 *  - Availability bias
 *  - BATNA suppression
 *  - Anchoring
 *
 * Returns a list of flag IDs (e.g. ["sunk_cost", "anchoring"]) for any pattern detected.
 * Returns an empty list when no bias markers are found.
 */
object BiasDetector {

    private val SUNK_COST = Regex(
        """(?i)\b(?:already (?:invested|spent|put in|committed|paid|given)|too much (?:time|money|effort) (?:in|into|already)|can't stop now|wasted|sunk)\b"""
    )

    private val AVAILABILITY_BIAS = Regex(
        """(?i)\b(?:everyone I know|everyone says|all my (?:friends|colleagues|family)|always works|never works|always happens|nobody ever)\b"""
    )

    private val BATNA_SUPPRESSION = Regex(
        """(?i)\b(?:I (?:could|can) (?:just )?(?:walk away|leave|quit|walk out|say no) but|no other (?:choice|option|alternative)|have to (?:accept|agree)|no way out)\b"""
    )

    private val ANCHORING = Regex(
        """(?i)\b(?:the (?:first|initial|original) (?:offer|number|figure|price|proposal)|they (?:started|opened) (?:at|with)|anchor|anchoring)\b"""
    )

    private val patterns = listOf(
        "sunk_cost" to SUNK_COST,
        "availability_bias" to AVAILABILITY_BIAS,
        "batna_suppression" to BATNA_SUPPRESSION,
        "anchoring" to ANCHORING
    )

    /**
     * Scans [userTexts] (all USER messages in the current conversation) and returns a deduplicated
     * list of detected bias flag ids.
     */
    fun detect(userTexts: List<String>): List<String> {
        if (userTexts.isEmpty()) return emptyList()
        val combined = userTexts.takeLast(5).joinToString(" ")
        return patterns.mapNotNull { (id, regex) -> if (regex.containsMatchIn(combined)) id else null }
    }
}
