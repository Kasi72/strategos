package com.drkkr.strategos.domain.agent

/**
 * Injects domain-specific contextual anchors into the coach prompt (Call 2) based on the detected
 * domain and pattern-matched signals from the user's current message.
 *
 * Rules:
 *   NEGOTIATION  → salary / rate numbers, anchoring advice, BATNA reminder
 *   WORKPLACE    → org-power gap signals, face-saving framing
 *   COMMITMENT*  → available binding mechanisms, pre-commitment device note
 *
 * Returns null when no actionable domain context is available, so the caller skips the injection.
 * No Android imports — pure Kotlin, lives in domain/.
 */
object DomainContextInjector {

    private val SALARY_AMOUNT = Regex("""(?i)\$?\b(\d{2,3}(?:[,.]?\d{3})?)\s*[k]?\b""")
    private val RATE_LABEL    = Regex("""(?i)\b(?:salary|pay|rate|offer|comp(?:ensation)?|package|raise|bump)\b""")
    private val POWER_SIGNALS = listOf("manager", "boss", "director", "vp", "cto", "ceo", "head of", "senior", "lead", "supervisor", "hr")
    private val WRITTEN_SIGNALS  = listOf("written", "contract", "sign", "document", "email", "letter", "agreement", "proof")
    private val PUBLIC_SIGNALS   = listOf("public", "announce", "tell", "post", "share", "tweet", "broadcast", "everyone")
    private val FINANCIAL_SIGNALS = listOf("money", "deposit", "pay", "bond", "penalty", "fine", "stake", "fee", "escrow")

    /**
     * @param domain  The domain label from [ModelSketch.domain] (e.g. "NEGOTIATION", "WORKPLACE", "COMMITMENT_PROBLEM").
     * @param message The current user message text.
     * @return A short plain-text block to prepend to the coach's user message, or null.
     */
    fun build(domain: String?, message: String): String? {
        val d = domain?.uppercase() ?: return null
        val low = message.lowercase()
        return when {
            "NEGOTIATION" in d -> negotiation(message, low)
            "WORKPLACE"   in d -> workplace(low)
            "COMMITMENT"  in d -> commitment(low)
            else               -> null
        }
    }

    private fun negotiation(raw: String, low: String): String {
        // Only extract numbers that appear near salary/rate keywords to avoid spurious hits.
        val hasRateLabel = RATE_LABEL.containsMatchIn(low)
        val numbers = if (hasRateLabel)
            SALARY_AMOUNT.findAll(raw).map { it.groupValues[1].replace(",", "") }.distinct().take(3).toList()
        else emptyList()

        return if (numbers.isNotEmpty())
            "DOMAIN CONTEXT — negotiation: Numbers in message: ${numbers.joinToString(", ")}. Anchor effect is strong; first number on the table sets the frame. Recommend they anchor high (or first) and anchor on a specific number, not a range."
        else
            "DOMAIN CONTEXT — negotiation: Anchoring and BATNA (best alternative to negotiated agreement) are the highest-leverage variables in this exchange. Recommend they clarify their BATNA before making or accepting any offer."
    }

    private fun workplace(low: String): String {
        val powerGap = POWER_SIGNALS.any { it in low }
        return if (powerGap)
            "DOMAIN CONTEXT — workplace: Formal power gap present (manager/senior vs individual). The person with less authority typically has fewer exit options but more social capital. Recommend moves that protect their interests while maintaining professionalism and not forcing the superior into a defensive posture."
        else
            "DOMAIN CONTEXT — workplace: Workplace dynamics involve implicit norms around hierarchy and face-saving. Strategies that let all parties maintain dignity tend to outperform aggressive or confrontational approaches."
    }

    private fun commitment(low: String): String {
        val mechanisms = buildList {
            if (WRITTEN_SIGNALS.any { it in low })   add("written record")
            if (PUBLIC_SIGNALS.any { it in low })    add("public commitment")
            if (FINANCIAL_SIGNALS.any { it in low }) add("financial stake")
        }
        return if (mechanisms.isNotEmpty())
            "DOMAIN CONTEXT — commitment: Binding mechanisms visible in conversation: ${mechanisms.joinToString(", ")}. Recommend anchoring the agreement to one of these so backing out becomes costly for both sides."
        else
            "DOMAIN CONTEXT — commitment: No binding mechanism detected yet. Recommend identifying one — written record, public announcement, or financial stake. Paradoxically, limiting future freedom here is what makes the other side trust the commitment."
    }
}
