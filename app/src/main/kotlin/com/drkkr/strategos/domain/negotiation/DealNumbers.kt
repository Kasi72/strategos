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
        """(?<![\d.,])(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d+)?\s*(%|percent|per cent|k|thousand|lakhs?|lacs?|l|crores?|cr|millions?|mn|m)?(?![a-z\d])""",
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

    /**
     * What may follow a figure that makes it a length of time ("2-year prepay", "10 weeks", "a 12-month term"), not an amount.
     * Text scanning ([figures], [replaceFigures]) skips such figures, so a fallback or walk-away of 2 never masks the "2" in
     * "2-year" (FinCorp, 8 Nov). [parse] still reads "45 months" as 45: a saved walk-away may be a duration.
     */
    private val durationAfter = Regex("""^\s*-?\s*(?:years?|yrs?|months?|mos?|weeks?|wks?|days?|hours?|hrs?|quarters?|qtrs?)\b""", RegexOption.IGNORE_CASE)

    private fun isDuration(text: String, end: Int) = durationAfter.containsMatchIn(text.substring(end))

    /** True when [text]'s figures are all lengths of time ("a 2-month gap"); "40k for 2 years" has an amount, so it is not. */
    fun duration(text: String): Boolean = figure.findAll(text).toList().let { f -> f.isNotEmpty() && f.all { isDuration(text, it.range.last + 1) } }

    /** True when [text]'s figure has no currency and no scale word ("2 other vendors"): a count, unless the context says money. */
    fun bare(text: String): Boolean {
        val m = figure.findAll(text).toList().singleOrNull() ?: return false
        return m.groupValues[3].isEmpty() && currencies.none { (_, r) -> r.containsMatchIn(text) }
    }

    private fun percent(suffix: String) = suffix == "%" || suffix.lowercase().startsWith("per")

    private fun multiplier(suffix: String): Double = when (suffix.lowercase()) {
        "k", "thousand" -> 1e3
        "l", "lakh", "lakhs", "lac", "lacs" -> 1e5
        "cr", "crore", "crores" -> 1e7
        "m", "mn", "million", "millions" -> 1e6
        else -> 1.0
    }

    fun parse(text: String): Amount? {
        if (text.isBlank() || range.containsMatchIn(text)) return null
        // A length of time beside an amount is a term, not a second figure: "27.5 lakh locked into a 2-year prepay" is 27.5 lakh
        // (FinCorp, 8 Nov: the "2" made it two figures, so no target and no deal frame). Alone, "45 months" is still 45.
        val all = figure.findAll(text).toList()
        val amounts = all.filterNot { isDuration(text, it.range.last + 1) }
        val found = amounts.ifEmpty { all }.map { m ->
            val digits = m.groupValues[1].replace(",", "") + m.groupValues[2]
            val suffix = m.groupValues[3]
            val n = digits.toDoubleOrNull() ?: return null
            val unit = if (percent(suffix)) Amount.Unit.PERCENT else Amount.Unit.AMOUNT
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
        return figure.findAll(text).filterNot { isDuration(text, it.range.last + 1) }.mapNotNull { m ->
            val n = (m.groupValues[1].replace(",", "") + m.groupValues[2]).toDoubleOrNull() ?: return@mapNotNull null
            val suffix = m.groupValues[3]
            if (percent(suffix)) Amount(n, Amount.Unit.PERCENT, null) else Amount(n * multiplier(suffix), Amount.Unit.AMOUNT, named)
        }.toList()
    }

    // A figure with any currency symbol or word written next to it, so a masked figure leaves no stray "$" or "dollars".
    private val withCurrency = Regex(
        """(?:[₹$€£]\s*|\b(?:rs\.?|inr|usd|eur|gbp)\s*)?""" + figure.pattern + """(?:\s*(?:dollars?|rupees?|usd|inr|euros?|eur|pounds?|gbp)\b)?""",
        RegexOption.IGNORE_CASE
    )

    /** [text] with each figure (and its currency) replaced by what [replace] returns for it; null keeps that figure as written. */
    fun replaceFigures(text: String, replace: (Amount) -> String?): String = withCurrency.replace(text) { m ->
        if (isDuration(text, m.range.last + 1)) return@replace m.value
        val inner = figure.find(m.value) ?: return@replace m.value
        val n = (inner.groupValues[1].replace(",", "") + inner.groupValues[2]).toDoubleOrNull() ?: return@replace m.value
        val suffix = inner.groupValues[3]
        val a = if (percent(suffix)) Amount(n, Amount.Unit.PERCENT, null)
                else Amount(n * multiplier(suffix), Amount.Unit.AMOUNT, currencies.firstOrNull { (_, r) -> r.containsMatchIn(m.value) }?.first)
        replace(a) ?: m.value
    }

    /** "₹40,000", "$132,000", "110,000", "30%". Whole numbers lose the ".0"; others keep up to two decimals. */
    fun format(a: Amount): String {
        val digits = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).format(a.value)
        return if (a.unit == Amount.Unit.PERCENT) "$digits%" else (a.currency?.let { symbol[it] } ?: "") + digits
    }
}
