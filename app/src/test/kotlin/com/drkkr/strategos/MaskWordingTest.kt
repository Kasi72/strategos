package com.drkkr.strategos

import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.DealNumbers
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID1.7: a masked line reads as a sentence (found at CP-I1: "name your your ceiling"). */
class MaskWordingTest {
    private val buyer = DealFrame.build(NegotiationContext(reservationPoint = "$45,000 a year", counterpartReservation = "$60,000 a year"), HumanContext(relationship = "vendor"), "")!!
    private val seller = DealFrame.build(NegotiationContext(reservationPoint = "40k", aspirationPoint = "50k"), null, "")!!
    private val doubled = Regex("""(?i)\b(\w+) \1\b""")

    private fun reads(t: String, frame: DealFrame) {
        assertFalse("doubled word: $t", doubled.containsMatchIn(t))
        assertFalse("a figure: $t", DealNumbers.figures(t).any { WalkAwayMask.secret(it, frame) } || Regex("""\d""").containsMatchIn(t))
        assertFalse("dangling article: $t", Regex("""(?i)\b(the|a|an) (your|my)\b""").containsMatchIn(t))
    }

    @Test fun TC_ID_MASK_01_aMaskedFigureLeavesNoDoubledWordNoDanglingArticleAndNoFigure() {
        val one = WalkAwayMask.text("Tell Karthik plainly to name your \$45,000 ceiling", buyer)
        assertEquals("Tell Karthik plainly to name your ceiling", one); reads(one, buyer)
        val two = WalkAwayMask.text("Tell her my floor is \$40k", seller)
        assertTrue(two, two.contains("my floor")); assertFalse(two.contains("my floor is my floor")); reads(two, seller)
        val three = WalkAwayMask.text("\$40k is my floor", seller)
        assertFalse(three.contains("my floor is my floor")); assertEquals("I have a firm floor", three); reads(three, seller)
        val four = WalkAwayMask.text("Ask for around 40–45k", seller)
        reads(four, seller); assertEquals("Ask for around your floor", four)
        // a figure after "my", "the", "a" or at the start
        assertEquals("Stick to my floor", WalkAwayMask.text("Stick to my 40k", seller).also { reads(it, seller) })
        assertEquals("Name a firm floor", WalkAwayMask.text("Name the 40k floor", seller).also { reads(it, seller) })
        assertEquals("Offer a firm ceiling", WalkAwayMask.text("Offer a \$45,000 ceiling", buyer).also { reads(it, buyer) })
        assertEquals("Your ceiling is where you stop.", WalkAwayMask.text("\$45,000 is where you stop.", buyer).also { reads(it, buyer) })
        assertEquals("Say your ceiling is firm", WalkAwayMask.text("Say your ceiling is \$45,000", buyer).also { reads(it, buyer) })
        // a range that doesn't touch the walk-away is left alone
        assertEquals("Ask for around 55–60k", WalkAwayMask.text("Ask for around 55–60k", seller))
    }
}
