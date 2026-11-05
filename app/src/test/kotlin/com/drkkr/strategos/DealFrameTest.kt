package com.drkkr.strategos

import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negotiation Numbers, NG2: direction, consistency, zone, landing, opening, stopping rule and credibility. */
class DealFrameTest {
    private fun nc(walk: String = "", target: String = "", fallback: String = "", theirLimit: String = "", theirFallback: String = "") =
        NegotiationContext(batna = fallback, reservationPoint = walk, aspirationPoint = target, counterpartBatna = theirFallback, counterpartReservation = theirLimit)

    private fun frame(nc: NegotiationContext, ctx: HumanContext? = null, message: String = ""): DealFrame =
        DealFrame.build(nc, ctx, message) ?: throw AssertionError("no frame for $nc")

    @Test fun TC_NG_FRAME_01_aTargetAboveTheWalkAwayMeansHigherIsBetter() {
        assertEquals(Direction.HIGHER_IS_BETTER, frame(nc(walk = "100k", target = "130k")).direction)
    }

    @Test fun TC_NG_FRAME_02_aTargetBelowTheWalkAwayMeansLowerIsBetter() {
        assertEquals(Direction.LOWER_IS_BETTER, frame(nc(walk = "45000", target = "38000")).direction)
    }

    @Test fun TC_NG_FRAME_03_withOnlyAWalkAwayTheRelationshipGivesTheDirection() {
        assertEquals(Direction.LOWER_IS_BETTER, frame(nc(walk = "45,000"), HumanContext(relationship = "vendor")).direction)
        assertEquals(Direction.LOWER_IS_BETTER, frame(nc(walk = "45,000"), HumanContext(relationship = "landlord")).direction)
        assertEquals(Direction.HIGHER_IS_BETTER, frame(nc(walk = "45,000"), HumanContext(relationship = "manager")).direction)
        assertEquals(Direction.HIGHER_IS_BETTER, frame(nc(walk = "45,000"), HumanContext(relationship = "client")).direction)
        assertNull(DealFrame.build(nc(walk = "45,000"), HumanContext(relationship = "friend"), ""))
        assertNull(DealFrame.build(nc(walk = "45,000"), null, ""))
    }

    @Test fun TC_NG_FRAME_04_aPercentTargetWithAnAmountWalkAwayIsDroppedWithANote() {
        val f = frame(nc(walk = "100k", target = "30%"), HumanContext(relationship = "manager"))
        assertEquals(Direction.HIGHER_IS_BETTER, f.direction)
        assertEquals(100000.0, f.walkAway!!.value, 0.0)
        assertNull(f.target)
        assertNull(f.openAt)
        assertTrue(f.notes.toString(), f.notes.any { "target is a percentage" in it && "walk-away an amount" in it })
    }

    @Test fun TC_NG_FRAME_05_aTargetInAnotherCurrencyIsDroppedWithANote() {
        val f = frame(nc(walk = "$100k", target = "₹130k"), HumanContext(relationship = "manager"))
        assertNull(f.target)
        assertEquals("USD", f.walkAway!!.currency)
        assertTrue(f.notes.toString(), f.notes.any { "currenc" in it && "target" in it })
    }

    @Test fun TC_NG_FRAME_06_theZoneRunsFromTheWalkAwayToTheirLimitAndTheLandingIsItsMiddle() {
        val f = frame(nc(walk = "100k", target = "130k", theirLimit = "120k"))
        assertEquals(100000.0, f.zone!!.low.value, 0.0)
        assertEquals(120000.0, f.zone!!.high.value, 0.0)
        assertEquals(110000.0, f.landing!!.value, 0.01)
        assertFalse(f.noZone)
    }

    @Test fun TC_NG_FRAME_07_confirmedAlternativesShiftTheLandingAndPendingOnesDoNot() {
        val n = nc(walk = "100k", target = "130k", theirLimit = "120k")
        assertEquals(112000.0, frame(n, HumanContext(yourAlternative = "strong", theirAlternative = "weak")).landing!!.value, 0.01)
        assertEquals(108000.0, frame(n, HumanContext(yourAlternative = "weak", theirAlternative = "strong")).landing!!.value, 0.01)
        val pendingOnly = HumanContext(pending = mapOf("yourAlternative" to "strong", "theirAlternative" to "weak"))
        assertEquals(110000.0, frame(n, pendingOnly).landing!!.value, 0.01)
        // a buyer's "toward the user" is down
        val buyer = nc(walk = "45000", target = "38000", theirLimit = "40000")
        assertEquals(42500.0, frame(buyer).landing!!.value, 0.01)
        assertEquals(42000.0, frame(buyer, HumanContext(yourAlternative = "strong", theirAlternative = "none")).landing!!.value, 0.01)
    }

    @Test fun TC_NG_FRAME_08_noOverlapAtTheWalkAwayMeansNoZoneAndNoLanding() {
        val f = frame(nc(walk = "125k", target = "140k", theirLimit = "120k"))
        assertNull(f.zone)
        assertTrue(f.noZone)
        assertNull(f.landing)
        assertTrue(f.notes.toString(), f.notes.any { "no overlap at your walk-away" in it })
    }

    @Test fun TC_NG_FRAME_09_openAtTheTargetUnlessItIsFarBeyondTheirLimit() {
        assertEquals(130000.0, frame(nc(walk = "100k", target = "130k", theirLimit = "120k")).openAt!!.value, 0.01)
        assertEquals(132000.0, frame(nc(walk = "100k", target = "160k", theirLimit = "120k")).openAt!!.value, 0.01)
        assertEquals(38000.0, frame(nc(walk = "45000", target = "38000", theirLimit = "40000")).openAt!!.value, 0.01)
        assertEquals(36000.0, frame(nc(walk = "45000", target = "28000", theirLimit = "40000")).openAt!!.value, 0.01)
        assertNull(frame(nc(walk = "45000"), HumanContext(relationship = "vendor")).openAt)
    }

    @Test fun TC_NG_FRAME_10_acceptIfAtLeastTheTargetForASellerAndAtMostForABuyer() {
        val seller = frame(nc(walk = "100k", target = "130k"))
        assertEquals(130000.0, seller.acceptIfAtLeast!!.value, 0.0)
        assertNull(seller.acceptIfAtMost)
        val buyer = frame(nc(walk = "45000", target = "38000"))
        assertEquals(38000.0, buyer.acceptIfAtMost!!.value, 0.0)
        assertNull(buyer.acceptIfAtLeast)
    }

    @Test fun TC_NG_FRAME_11_walkingAwayIsCredibleOnlyWhenTheFallbackIsAtLeastAsGood() {
        assertEquals(true, frame(nc(walk = "100k", target = "130k", fallback = "105k")).walkAwayCredible)
        assertEquals(false, frame(nc(walk = "100k", target = "130k", fallback = "90k")).walkAwayCredible)
        assertNull(frame(nc(walk = "100k", target = "130k", fallback = "my current job")).walkAwayCredible)
        assertEquals(true, frame(nc(walk = "45000", target = "38000", fallback = "42000")).walkAwayCredible)
    }

    @Test fun TC_NG_FRAME_12_aStricterFigureInTheMessageWins() {
        assertEquals(110000.0, frame(nc(walk = "100k", target = "130k"), message = "I need at least 110k").walkAway!!.value, 0.0)
        // a looser one does not
        assertEquals(100000.0, frame(nc(walk = "100k", target = "130k"), message = "I need at least 90k").walkAway!!.value, 0.0)
        // "at least" is a seller's word: a buyer's walk-away is never moved by it
        assertEquals(45000.0, frame(nc(walk = "45000", target = "38000"), message = "at least 50,000 is what they asked").walkAway!!.value, 0.0)
    }

    @Test fun noNegotiationContextMeansNoFrame() {
        assertNull(DealFrame.build(null, HumanContext(relationship = "manager"), "I need at least 110k"))
        assertNull(DealFrame.build(nc(walk = "no idea yet"), HumanContext(relationship = "manager"), ""))
    }

    @Test fun allAmountsShareTheFrameCurrency() {
        val f = frame(nc(walk = "₹45,000", target = "38000", theirLimit = "40000"))
        assertEquals("INR", f.target!!.currency)
        assertEquals("INR", f.theirLimit!!.currency)
        assertNotNull(f.zone)
    }
}
