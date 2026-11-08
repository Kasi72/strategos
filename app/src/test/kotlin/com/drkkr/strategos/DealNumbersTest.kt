package com.drkkr.strategos

import com.drkkr.strategos.domain.negotiation.Amount
import com.drkkr.strategos.domain.negotiation.DealNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Negotiation Numbers, NG1: reading a figure, its unit and its currency out of free text. */
class DealNumbersTest {
    private fun amount(text: String): Amount = DealNumbers.parse(text) ?: throw AssertionError("\"$text\" did not parse")
    private fun value(text: String) = amount(text).value

    @Test fun TC_NG_PARSE_01_plainDigitsAreAnAmountWithNoCurrency() {
        assertEquals(Amount(40000.0, Amount.Unit.AMOUNT, null), DealNumbers.parse("40000"))
    }

    @Test fun TC_NG_PARSE_02_westernGrouping() = assertEquals(45000.0, value("45,000"), 0.0)

    @Test fun TC_NG_PARSE_03_indianGrouping() = assertEquals(120000.0, value("1,20,000"), 0.0)

    @Test fun TC_NG_PARSE_04_thousandsSuffix() {
        assertEquals(45000.0, value("45k"), 0.0)
        assertEquals(45000.0, value("45K"), 0.0)
    }

    @Test fun TC_NG_PARSE_05_lakh() {
        assertEquals(300000.0, value("3 lakh"), 0.0)
        assertEquals(300000.0, value("3 lac"), 0.0)
        assertEquals(300000.0, value("3L"), 0.0)
    }

    @Test fun TC_NG_PARSE_06_crore() {
        assertEquals(15000000.0, value("1.5 crore"), 0.0)
        assertEquals(15000000.0, value("1.5cr"), 0.0)
    }

    @Test fun TC_NG_PARSE_07_million() {
        assertEquals(2500000.0, value("2.5 million"), 0.0)
        assertEquals(2500000.0, value("2.5m"), 0.0)
        assertEquals(2500000.0, value("2.5 mn"), 0.0)
    }

    @Test fun TC_NG_PARSE_08_currencyFromASymbolOrAWord() {
        assertEquals(Amount(40000.0, Amount.Unit.AMOUNT, "INR"), DealNumbers.parse("₹40,000"))
        assertEquals(Amount(60000.0, Amount.Unit.AMOUNT, "USD"), DealNumbers.parse("60,000 dollars"))
        assertEquals(Amount(42000.0, Amount.Unit.AMOUNT, "USD"), DealNumbers.parse("$42k"))
        assertEquals(Amount(500.0, Amount.Unit.AMOUNT, "GBP"), DealNumbers.parse("£500"))
        assertEquals("INR", amount("Rs. 40,000").currency)
        assertEquals("INR", amount("40000 rupees").currency)
        assertEquals("EUR", amount("€900").currency)
    }

    @Test fun TC_NG_PARSE_09_percent() {
        assertEquals(Amount(30.0, Amount.Unit.PERCENT, null), DealNumbers.parse("30%"))
        assertEquals(Amount(5.0, Amount.Unit.PERCENT, null), DealNumbers.parse("a 5 percent raise"))
    }

    @Test fun TC_NG_PARSE_10_rangesAreNotUsed() {
        assertNull(DealNumbers.parse("between 40 and 45k"))
        assertNull(DealNumbers.parse("40-45k"))
        assertNull(DealNumbers.parse("40 to 45k"))
        assertNull(DealNumbers.parse("40–45k"))
    }

    @Test fun TC_NG_PARSE_11_twoDifferentFiguresAreNull() {
        assertNull(DealNumbers.parse("60k now and 5k bonus"))
    }

    @Test fun TC_NG_PARSE_12_noFigureIsNullAndHedgeWordsAreIgnored() {
        assertNull(DealNumbers.parse("no idea yet"))
        assertNull(DealNumbers.parse(""))
        assertEquals(42000.0, value("about 42,000"), 0.0)
        // a word that merely starts with a suffix letter is not a suffix
        assertEquals(45.0, value("45 months"), 0.0)
        // the same figure said twice is still one figure
        assertEquals(42000.0, value("42k, yes 42k"), 0.0)
    }

    @Test fun formatWritesTheCurrencyAndGroupsTheDigits() {
        assertEquals("₹40,000", DealNumbers.format(Amount(40000.0, Amount.Unit.AMOUNT, "INR")))
        assertEquals("$132,000", DealNumbers.format(Amount(132000.0, Amount.Unit.AMOUNT, "USD")))
        assertEquals("110,000", DealNumbers.format(Amount(110000.0, Amount.Unit.AMOUNT, null)))
        assertEquals("30%", DealNumbers.format(Amount(30.0, Amount.Unit.PERCENT, null)))
        assertEquals("2.5%", DealNumbers.format(Amount(2.5, Amount.Unit.PERCENT, null)))
    }

    // FinCorp, 8 Nov: a length of time beside an amount is a term, not a second figure.
    @Test fun aDurationBesideAnAmountIsATerm_aloneItIsStillTheFigure() {
        assertEquals(2_750_000.0, value("27.5 lakh locked into a 2-year prepay with free analytics seats"), 0.0)
        assertEquals(40_000.0, value("another vendor at 40k for 2 years"), 0.0)
        assertEquals(45.0, value("45 months"), 0.0)
        assertTrue(DealNumbers.duration("a 2-month cash-flow gap"))
        assertFalse(DealNumbers.duration("another vendor at 40k for 2 years"))
        assertEquals(listOf(2_750_000.0), DealNumbers.figures("27.5 lakh for a 2-year prepay, 10 weeks notice").map { it.value })
    }
}
