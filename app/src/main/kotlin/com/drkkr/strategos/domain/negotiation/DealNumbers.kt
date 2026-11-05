package com.drkkr.strategos.domain.negotiation

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * One figure from free text. A plain number (no symbol, no currency word) has `currency = null` and is read as compatible with
 * any currency (PLAN §3.1).
 */
data class Amount(val value: Double, val unit: Unit, val currency: String?) {
    enum class Unit { AMOUNT, PERCENT }
}

/**
 * Negotiation Numbers (PLAN §3.1): reads the walk-away, target, fallback and limit strings Call 1 saves into numbers. Pure Kotlin.
 * Never guesses: a range, two different figures, two different currencies or no figure at all is null, and the engine then
 * behaves exactly as it did before (I-N1).
 */
object DealNumbers {
    // A figure: Western (45,000) or Indian (1,20,000) digit groups, or plain digits; an optional decimal part; an optional suffix.
    // The suffix must end the word ("45 months" is 45, not 45 million).
    private val figure = Regex(
        """(?<![\d.,])(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d+)?\s*(%|k|thousand|lakhs?|lacs?|l|crores?|cr|millions?|mn|m)?(?![a-z\d])""",
        RegexOption.IGNORE_CASE
    )
    // "40-45k", "40 – 45k", "40 to 45k" and "between 40 and 45k".
    private val range = Regex("""\d\s*[a-z%]{0,8}\s*(?:-|–|—|to)\s*[₹$€£]?\s*\d|between\b[^\d]*\d.*\band\b[^\d]*\d""", RegexOption.IGNORE_CASE)

    private val currencies = listOf(
        "INR" to Regex("""₹|\brs\b\.?|\binr\b|\brupees?\b""", RegexOption.IGNORE_CASE),
        "USD" to Regex("""\$|\bdollars?\b|\busd\b""", RegexOption.IGNORE_CASE),
        "EUR" to Regex("""€|\beur\b|\beuros?\b""", RegexOption.IGNORE_CASE),
        "GBP" to Regex("""£|\bgbp\b|\bpounds?\b""", RegexOption.IGNORE_CASE)
    )
    private val symbol = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")

    private fun multiplier(suffix: String): Double = when (suffix.lowercase()) {
        "k", "thousand" -> 1e3
        "l", "lakh", "lakhs", "lac", "lacs" -> 1e5
        "cr", "crore", "crores" -> 1e7
        "m", "mn", "million", "millions" -> 1e6
        else -> 1.0
    }

    fun parse(text: String): Amount? {
        if (text.isBlank() || range.containsMatchIn(text)) return null
        val found = figure.findAll(text).toList().map { m ->
            val digits = m.groupValues[1].replace(",", "") + m.groupValues[2]
            val suffix = m.groupValues[3]
            val n = digits.toDoubleOrNull() ?: return null
            val unit = if (suffix == "%") Amount.Unit.PERCENT else Amount.Unit.AMOUNT
            unit to n * multiplier(suffix)
        }.toSet()
        if (found.size != 1) return null
        val named = currencies.filter { (_, r) -> r.containsMatchIn(text) }.map { it.first }
        if (named.size > 1) return null
        val (unit, v) = found.single()
        return Amount(v, unit, named.firstOrNull()?.takeIf { unit == Amount.Unit.AMOUNT })
    }

    /** All the figures in [text] (for checking a reply), each read the same way as [parse]; ranges count as their two ends. */
    fun figures(text: String): List<Amount> {
        val named = currencies.filter { (_, r) -> r.containsMatchIn(text) }.map { it.first }.singleOrNull()
        return figure.findAll(text).mapNotNull { m ->
            val n = (m.groupValues[1].replace(",", "") + m.groupValues[2]).toDoubleOrNull() ?: return@mapNotNull null
            val suffix = m.groupValues[3]
            if (suffix == "%") Amount(n, Amount.Unit.PERCENT, null) else Amount(n * multiplier(suffix), Amount.Unit.AMOUNT, named)
        }.toList()
    }

    /** "₹40,000", "$132,000", "110,000", "30%". Whole numbers lose the ".0"; others keep up to two decimals. */
    fun format(a: Amount): String {
        val digits = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).format(a.value)
        return if (a.unit == Amount.Unit.PERCENT) "$digits%" else (a.currency?.let { symbol[it] } ?: "") + digits
    }
}
